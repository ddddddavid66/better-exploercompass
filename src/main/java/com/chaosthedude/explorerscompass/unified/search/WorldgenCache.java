package com.chaosthedude.explorerscompass.unified.search;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;

/** 主线程创建不可变生成上下文；生命周期随世界与数据包重载结束。 */
final class WorldgenCache {
    private static final Map<ServerLevel, WorldgenCache> WORLDS = new IdentityHashMap<>();
    final StructurePredictor predictor;
    private record PredictionKey(Structure structure, long chunk) {}
    private record Prediction(net.minecraft.core.BlockPos point) {}
    private final com.chaosthedude.explorerscompass.unified.data.BoundedMemoizer<PredictionKey, Prediction> predictions =
            new com.chaosthedude.explorerscompass.unified.data.BoundedMemoizer<>(32_768);
    private final Map<Structure, Predicate<Holder<Biome>>> biomeRules = new IdentityHashMap<>();
    private final Map<Structure, DungeonsPrediction> dungeons = new IdentityHashMap<>();
    private WorldgenCache(ServerLevel level) {
        var state = level.getChunkSource().getGeneratorState();
        state.ensureStructuresGenerated();
        state.possibleStructureSets().forEach(set -> {
            if (set.value().placement() instanceof ConcentricRingsStructurePlacement rings) state.getRingPositionsFor(rings);
        });
        predictor = new StructurePredictor(level);
    }
    static WorldgenCache get(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("生成缓存只能由服务端线程创建");
        return WORLDS.computeIfAbsent(level, WorldgenCache::new);
    }
    Predicate<Holder<Biome>> biomeRule(Structure structure) {
        return biomeRules.computeIfAbsent(structure, StructurePredictor::biomeRule);
    }
    DungeonsPrediction dungeons(ServerLevel level, net.minecraft.resources.ResourceLocation key, Structure structure) {
        return dungeons.computeIfAbsent(structure, ignored -> DungeonsPrediction.of(level, key, structure));
    }
    net.minecraft.core.BlockPos predict(Structure structure, Predicate<Holder<Biome>> biomes, net.minecraft.world.level.ChunkPos chunk) {
        // 缓存正/负生成预测，绝不缓存真实区块核对；快照失效后旧任务只能写入自己的旧缓存。
        return predictions.get(new PredictionKey(structure, chunk.toLong()), ignored -> {
            var point = predictor.predict(structure, biomes, chunk);
            return new Prediction(point == null ? null : point.immutable());
        }).point();
    }
    static void clear() { WORLDS.clear(); BiomeSourceSafety.clear(); }
}
