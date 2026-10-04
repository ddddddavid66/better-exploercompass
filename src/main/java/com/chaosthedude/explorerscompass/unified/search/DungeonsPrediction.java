package com.chaosthedude.explorerscompass.unified.search;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraftforge.fml.ModList;

/** 仅放行已经逐方法核对的 Dungeons Plus 生成入口，第三方能力不因继承同一类而被放行。 */
final class DungeonsPrediction {
    private static final String PREFIX = "com.legacy.dungeons_plus.structures.";
    private static final Map<String, String> SIMPLE = Map.of("tower", "tower.TowerStructure",
            "snowy_temple", "snowy_temple.SnowyTempleStructure", "soul_prison", "soul_prison.SoulPrisonStructure");
    private static final Set<String> JIGSAW = Set.of("leviathan", "end_ruins", "warped_garden",
            "reanimated_ruins_mossy", "reanimated_ruins_mesa", "reanimated_ruins_frozen");
    private static final Set<String> ELEMENTS = Set.of("minecraft:single_pool_element", "minecraft:legacy_single_pool_element",
            "structure_gel:gel_single_pool_element", "minecraft:empty_pool_element");
    private final ServerLevel level;
    private final ResourceLocation key;
    private final List<ResourceLocation> pending = new ArrayList<>();
    private final Map<ResourceLocation, StructureTemplate> loading = new LinkedHashMap<>();
    private Map<ResourceLocation, StructureTemplate> snapshot = Map.of();
    private int index;
    private boolean allowed = true;
    private Object deferredLeviathan;
    private DungeonsPrediction(ServerLevel level, ResourceLocation key, Structure structure) {
        this.level = level; this.key = key;
        try { inspect(structure); }
        catch (Exception error) { reject(error.toString()); }
    }
    static DungeonsPrediction of(ServerLevel level, ResourceLocation key, Structure structure) {
        return new DungeonsPrediction(level, key, structure);
    }
    private void inspect(Structure structure) throws ReflectiveOperationException {
        if (!version("dungeons_plus", "1.5.0") || !version("structure_gel", "2.16.2")) {
            reject("未经核对的 Dungeons Plus / Structure Gel 版本"); return;
        }
        var source = BiomeSourceSafety.prepare(level);
        if (!source.allowed()) { reject(source.reason() + "；来源链：" + source.chain()); return; }
        String simple = SIMPLE.get(key.getPath());
        if (key.getNamespace().equals("dungeons_plus") && simple != null && structure.getClass().getName().equals(PREFIX + simple)) return;
        if (!key.getNamespace().equals("dungeons_plus") || !JIGSAW.contains(key.getPath())
                || !structure.getClass().getName().equals("com.legacy.structure_gel.api.structure.ExtendedJigsawStructure")) {
            reject("未经核对的结构类型"); return;
        }
        String family = key.getPath().startsWith("reanimated_ruins_") ? "reanimated_ruins" : key.getPath();
        String title = switch (family) {
            case "leviathan" -> "Leviathan"; case "end_ruins" -> "EndRuins";
            case "warped_garden" -> "WarpedGarden"; default -> "ReanimatedRuins";
        };
        var capability = (java.util.Optional<?>)structure.getClass().getMethod("getCapability").invoke(structure);
        if (capability.isEmpty() || !capability.get().getClass().getName().equals(PREFIX + family + "." + title + "Structure$Capability")) {
            reject("未经核对的拼接能力"); return;
        }
        if (family.equals("leviathan")) deferredLeviathan = capability.get();
        var ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
        var json = Structure.DIRECT_CODEC.encodeStart(ops, structure).getOrThrow(false, message -> {
            throw new IllegalStateException(message);
        }).getAsJsonObject();
        // 带命名起始拼接的分支可能更新模板内部方块索引缓存，留在主线程处理。
        if (json.has("start_jigsaw_name")) { reject("自定义命名起始拼接"); return; }
        var height = json.get("start_height");
        if (height.isJsonObject() && height.getAsJsonObject().has("type")
                && !Set.of("minecraft:constant", "minecraft:uniform").contains(height.getAsJsonObject().get("type").getAsString())) {
            reject("未经核对的高度提供器"); return;
        }
        var poolKey = new ResourceLocation(json.get("start_pool").getAsString());
        var pool = level.registryAccess().registryOrThrow(Registries.TEMPLATE_POOL).get(poolKey);
        if (pool == null) { reject("起始模板池不存在"); return; }
        var poolJson = StructureTemplatePool.DIRECT_CODEC.encodeStart(ops, pool).getOrThrow(false, message -> {
            throw new IllegalStateException(message);
        }).getAsJsonObject();
        for (var weighted : poolJson.getAsJsonArray("elements")) {
            var element = weighted.getAsJsonObject().getAsJsonObject("element");
            String type = element.get("element_type").getAsString();
            if (!ELEMENTS.contains(type)) { reject("未知起始模板元素 " + type); return; }
            if (!type.equals("minecraft:empty_pool_element")) {
                if (!element.has("location") || !element.get("location").isJsonPrimitive()) { reject("内联或未知模板"); return; }
                var template = new ResourceLocation(element.get("location").getAsString());
                if (!pending.contains(template)) pending.add(template);
            }
        }
    }
    private static boolean version(String mod, String expected) {
        return ModList.get().getModContainerById(mod).map(container -> expected.equals(container.getModInfo().getVersion().toString())).orElse(false);
    }
    private void reject(String reason) {
        allowed = false;
        ExplorersCompass.LOGGER.info("{} 保留网格候选，转入兼容验证: {}", key, reason);
    }
    boolean preparing() { return allowed && index < pending.size(); }
    boolean allowed() { return allowed && !preparing(); }
    void prepareOne() {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("模板只能在主线程准备");
        if (!preparing()) return;
        var id = pending.get(index++);
        try {
            var source = level.getServer().getStructureManager().get(id);
            if (source.isEmpty()) { reject("模板不存在: " + id); return; }
            // 起始片段只查尺寸与包围盒，保存几何即可，不复制整份调色板和实体 NBT。
            loading.put(id, new TemplateGeometry(source.get().getSize()));
            if (!preparing()) snapshot = Map.copyOf(loading);
        } catch (RuntimeException error) { reject("模板准备失败: " + error); }
    }
    <T> T predict(Supplier<T> action) {
        return PredictionTemplates.with(snapshot, () -> deferredLeviathan == null ? action.get() : LeviathanPrecheck.predict(deferredLeviathan, action));
    }
}
