package com.chaosthedude.explorerscompass.unified.search;

import com.chaosthedude.explorerscompass.unified.CompassUnified;
import com.chaosthedude.explorerscompass.unified.mixin.FeatureOrderAccessor;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/** 只审核生成规则并预测随机前缀；不调用第三方地物或模板放置方法。 */
public final class GoldenCrateAdapter {
    public static final ResourceLocation KEY = new ResourceLocation("infinite_abyss:castle_feature");
    public static final ResourceLocation DIMENSION = new ResourceLocation("infinite_abyss:fourth_layer");
    public static final ResourceLocation BLOCK = new ResourceLocation("infinite_abyss:golden_crate");
    public record Slot(int step, int index) {}
    public record Snapshot(Set<ResourceLocation> biomes, List<Slot> slots, int chance, int minY, int maxY,
                    boolean predictive, String reason) {}
    private static final Map<ServerLevel, Snapshot> CACHE = new IdentityHashMap<>();
    private static final ThreadLocal<WorldgenRandom> RANDOM = ThreadLocal.withInitial(
            () -> new WorldgenRandom(new XoroshiroRandomSource(0)));
    private GoldenCrateAdapter() {}
    public static boolean supported() {
        return ModList.get().getModContainerById("infinite_abyss")
                .map(mod -> "1.8.4".equals(mod.getModInfo().getVersion().toString())).orElse(false);
    }
    public static boolean special(ServerLevel level, ResourceLocation id) {
        return KEY.equals(id) && supported()
                && !level.registryAccess().registryOrThrow(Registries.STRUCTURE).containsKey(KEY)
                && level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE).containsKey(KEY);
    }
    public static Set<ResourceLocation> biomes(ServerLevel level) { return snapshot(level).biomes(); }
    public static Snapshot snapshot(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("城堡目录必须在主线程读取");
        return CACHE.computeIfAbsent(level, GoldenCrateAdapter::prepare);
    }
    private static Snapshot prepare(ServerLevel level) {
        Set<ResourceLocation> biomes = new LinkedHashSet<>();
        List<Slot> slots = new ArrayList<>();
        int chance=2, minY=8, maxY=128; String reason="";
        try {
            if (!DIMENSION.equals(level.dimension().location())) throw new IllegalStateException("请进入诱惑之森（第4层）");
            var generator=level.getChunkSource().getGenerator();
            var placed=level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE).get(KEY);
            if (placed==null) throw new IllegalStateException("城堡地物未注册");
            for (var holder:generator.getBiomeSource().possibleBiomes())
                if (generator.getBiomeGenerationSettings(holder).features().stream()
                        .anyMatch(set -> set.stream().anyMatch(value -> value.value()==placed)))
                    holder.unwrapKey().ifPresent(key -> biomes.add(key.location()));
            if (!placed.feature().value().feature().getClass().getName().equals(
                    "net.mcreator.infiniteabyss.world.features.CastleFeatureFeature"))
                throw new IllegalStateException("城堡生成入口被替换");
            var ops=RegistryOps.create(JsonOps.INSTANCE,level.registryAccess());
            JsonObject configured=ConfiguredFeature.DIRECT_CODEC.encodeStart(ops,placed.feature().value())
                    .getOrThrow(false, message -> {}).getAsJsonObject().getAsJsonObject("config");
            if (!"infinite_abyss:castle".equals(configured.get("structure").getAsString())
                    || (configured.has("random_rotation") && configured.get("random_rotation").getAsBoolean())
                    || (configured.has("random_mirror") && configured.get("random_mirror").getAsBoolean())
                    || !configured.get("offset").equals(com.google.gson.JsonParser.parseString("[0,-4,0]"))
                    || !configured.get("ignored_blocks").equals(com.google.gson.JsonParser.parseString("\"minecraft:structure_block\"")))
                throw new IllegalStateException("城堡模板设置发生未经审核的修改");
            var template=level.getServer().getStructureManager().get(new ResourceLocation("infinite_abyss:castle")).orElseThrow();
            var crates=template.filterBlocks(BlockPos.ZERO,new StructurePlaceSettings(),block());
            if (!template.getSize().equals(new BlockPos(13,15,13)) || crates.size()!=1
                    || !crates.get(0).pos().equals(new BlockPos(6,5,6)))
                throw new IllegalStateException("城堡箱子位置或模板尺寸已改变");
            String[] types={"rarity_filter","in_square","height_range","surface_relative_threshold_filter","biome"};
            if (placed.placement().size()!=types.length) throw new IllegalStateException("城堡放置修饰器数量已改变");
            for (int i=0;i<types.length;i++) {
                JsonObject json=PlacementModifier.CODEC.encodeStart(ops,placed.placement().get(i))
                        .getOrThrow(false,message -> {}).getAsJsonObject();
                if (!json.get("type").getAsString().equals("minecraft:"+types[i]))
                    throw new IllegalStateException("未经审核的城堡放置修饰器");
                if (i==0) chance=json.get("chance").getAsInt();
                if (i==2) {
                    var height=json.getAsJsonObject("height");
                    if (!"minecraft:uniform".equals(height.get("type").getAsString()))
                        throw new IllegalStateException("未经审核的城堡高度分布");
                    minY=anchor(height.getAsJsonObject("min_inclusive"),level);
                    maxY=anchor(height.getAsJsonObject("max_inclusive"),level);
                }
            }
            if (chance<1 || minY>maxY) throw new IllegalStateException("城堡随机范围无效");
            if (generator.getClass()!=NoiseBasedChunkGenerator.class)
                throw new IllegalStateException("未经审核的城堡装饰生成器");
            var steps=((FeatureOrderAccessor)generator).unified$featuresPerStep().get();
            for (int step=0;step<steps.size();step++) {
                int index=steps.get(step).features().indexOf(placed);
                if (index>=0) slots.add(new Slot(step,index));
            }
            if (slots.isEmpty() || biomes.isEmpty()) throw new IllegalStateException("当前维度没有启用城堡地物");
        } catch (RuntimeException error) {
            reason=Objects.toString(error.getMessage(),error.toString());
            CompassUnified.LOGGER.warn("金色板条箱预测暂停，仅复核发现索引：{}",reason);
        }
        return new Snapshot(Set.copyOf(biomes),List.copyOf(slots),chance,minY,maxY,reason.isEmpty(),reason);
    }
    private static int anchor(JsonObject anchor,ServerLevel level) {
        return net.minecraft.world.level.levelgen.VerticalAnchor.CODEC.parse(JsonOps.INSTANCE,anchor)
                .getOrThrow(false,message -> {}).resolveY(new net.minecraft.world.level.levelgen.WorldGenerationContext(
                        level.getChunkSource().getGenerator(),level));
    }
    public static BlockPos predict(long seed,int x,int z,Slot slot,Snapshot rules) {
        WorldgenRandom random=RANDOM.get();
        long decoration=random.setDecorationSeed(seed,x*16,z*16);
        random.setFeatureSeed(decoration,slot.index(),slot.step());
        if (random.nextFloat()>=1.0F/rules.chance()) return null;
        int px=x*16+random.nextInt(16),pz=z*16+random.nextInt(16);
        int y=rules.minY()+random.nextInt(rules.maxY()-rules.minY()+1);
        // 无旋转/镜像：模板偏移 -4，加箱子局部坐标 [6,5,6]。
        return new BlockPos(px+6,y+1,pz+6);
    }
    static Block block() { return ForgeRegistries.BLOCKS.getValue(BLOCK); }
    public static void clear() { CACHE.clear(); }
}
