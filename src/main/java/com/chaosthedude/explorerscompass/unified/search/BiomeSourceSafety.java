package com.chaosthedude.explorerscompass.unified.search;

import com.mojang.datafixers.util.Pair;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraftforge.fml.ModList;

/** 只在主线程审查和预热；后台仍调用完整来源，绝不剥离模组群系规则。 */
final class BiomeSourceSafety {
    private static final String BLUEPRINT = "com.teamabnormals.blueprint.common.world.modification.ModdedBiomeSource";
    private static final String PROVIDER = "com.teamabnormals.blueprint.core.util.BiomeUtil$";
    private static final Map<ServerLevel, Result> WORLDS = new IdentityHashMap<>();
    private static final ClassValue<Map<String, AccessibleObject>> MEMBERS = new ClassValue<>() {
        @Override protected Map<String, AccessibleObject> computeValue(Class<?> type) { return new HashMap<>(); }
    };
    record Result(boolean allowed, String reason, String chain) {}
    private final ServerLevel level;
    private final List<String> chain = new ArrayList<>();
    private final Set<BiomeSource> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
    private BiomeSourceSafety(ServerLevel level) { this.level = level; }

    static Result prepare(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("群系来源审查只能在服务端线程执行");
        return WORLDS.computeIfAbsent(level, key -> new BiomeSourceSafety(key).inspect());
    }
    static void clear() { WORLDS.clear(); }

    private Result inspect() {
        var generator = level.getChunkSource().getGenerator();
        chain.add(generator.getClass().getName());
        try {
            if (generator.getClass().getName().equals("twilightforest.world.components.chunkgenerators.ChunkGeneratorTwilight")
                    && generator.getBiomeSource().getClass().getName().equals("twilightforest.world.components.biomesources.TFBiomeProvider")) {
                requireVersion("twilightforest", "4.3.2508");
                generator.getBiomeSource().possibleBiomes();
                return new Result(true, "", String.join(" -> ", chain));
            }
            if (generator.getClass() != NoiseBasedChunkGenerator.class) reject("未经核对的生成器 " + generator.getClass().getName());
            audit(generator.getBiomeSource());
            return new Result(true, "", String.join(" -> ", chain));
        } catch (ReflectiveOperationException | RuntimeException error) {
            return new Result(false, error.getMessage() == null ? error.toString() : error.getMessage(), String.join(" -> ", chain));
        }
    }
    private void audit(BiomeSource source) throws ReflectiveOperationException {
        if (visiting.size() >= 16 || !visiting.add(source)) reject("循环或过深的群系来源 " + source.getClass().getName());
        chain.add(source.getClass().getName());
        try {
            if (source.getClass() == FixedBiomeSource.class) { source.possibleBiomes(); return; }
            if (source.getClass() == MultiNoiseBiomeSource.class) { prepareTerraBlender(source); source.possibleBiomes(); return; }
            if (source.getClass() == TheEndBiomeSource.class) { prepareEnd(source); return; }
            if (!source.getClass().getName().equals(BLUEPRINT)) reject("未经核对的群系来源 " + source.getClass().getName());
            requireVersion("blueprint", "7.1.0");
            var original = (BiomeSource)field(source, "originalSource");
            audit(original);
            for (Object value : (Object[])field(source, "slices")) {
                Object provider = call(((Pair<?, ?>)value).getSecond(), "provider");
                String type = provider.getClass().getName();
                chain.add(type);
                if (type.equals(PROVIDER + "OriginalModdedBiomeProvider")) continue;
                if (type.equals(PROVIDER + "BiomeSourceModdedBiomeProvider")) {
                    audit((BiomeSource)call(provider, "biomeSource")); continue;
                }
                // 未核对 provider 不因同属 Blueprint 而放行，保留真实官方兼容验证。
                reject("未经核对的 Blueprint provider " + type);
            }
            source.possibleBiomes();
        } finally { visiting.remove(source); }
    }
    private void prepareTerraBlender(BiomeSource source) throws ReflectiveOperationException {
        if (!ModList.get().isLoaded("terrablender")) return;
        requireVersion("terrablender", "3.0.1.7");
        Object parameters = call(source, "parameters", "m_274409_");
        Class<?> api = Class.forName("terrablender.worldgen.IExtendedParameterList");
        if (!api.isInstance(parameters)) reject("TerraBlender 参数接口未应用 " + parameters.getClass().getName());
        if (!Boolean.TRUE.equals(method(api, "isInitialized").invoke(parameters))) reject("TerraBlender 参数树尚未完成初始化");
    }
    private void prepareEnd(BiomeSource source) throws ReflectiveOperationException {
        // possibleBiomes 同时完成 Fabric 的 Overrides 和布尔标识懒初始化。
        source.possibleBiomes();
        if (!ModList.get().isLoaded("fabric_biome_api_v1")) return;
        requireVersion("fabric_biome_api_v1", "13.0.14+dc36698e77");
        Class<?> api = Class.forName("net.fabricmc.fabric.impl.biome.MultiNoiseSamplerHooks");
        Object sampler = level.getChunkSource().randomState().sampler();
        if (!api.isInstance(sampler)) reject("Fabric 末地采样器接口未应用 " + sampler.getClass().getName());
        // 该版本 getter 的首次初始化无锁，必须在发布后台任务之前完成。
        method(api, "fabric_getEndBiomesSampler").invoke(sampler);
    }
    private static void requireVersion(String id, String expected) {
        String actual = ModList.get().getModContainerById(id).map(mod -> mod.getModInfo().getVersion().toString()).orElse("未安装");
        if (!actual.equals(expected)) reject("未经核对的 " + id + " 版本 " + actual + "（已核对 " + expected + "）");
    }
    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        var members = MEMBERS.get(owner.getClass());
        Field value = (Field)members.get("field:" + name);
        if (value == null) {
            value = owner.getClass().getDeclaredField(name);
            if (!value.trySetAccessible()) reject("无法读取来源字段 " + owner.getClass().getName() + "." + name);
            members.put("field:" + name, value);
        }
        return value.get(owner);
    }
    private static Object call(Object owner, String... names) throws ReflectiveOperationException {
        return method(owner.getClass(), names).invoke(owner);
    }
    private static Method method(Class<?> type, String... names) throws ReflectiveOperationException {
        String key = "method:" + names[0];
        var members = MEMBERS.get(type);
        var value = (Method)members.get(key);
        if (value != null) return value;
        for (String name : names) {
            try {
                value = type.getDeclaredMethod(name);
                if (!value.trySetAccessible()) reject("无法读取来源接口 " + type.getName() + "." + name);
                members.put(key, value); return value;
            } catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(type.getName() + "." + names[0]);
    }
    private static void reject(String reason) { throw new IllegalStateException(reason); }
}
