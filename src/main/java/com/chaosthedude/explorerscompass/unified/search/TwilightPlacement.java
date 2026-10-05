package com.chaosthedude.explorerscompass.unified.search;

import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

/** 可选适配：仅引用官方公开方法，不把暮色类放入扩展或设为硬依赖。 */
final class TwilightPlacement {
    private static final String TYPE = "twilightforest.world.components.structures.placements.BiomeForcedLandmarkPlacement";
    private final StructurePlacement placement;
    private final Method check, center;
    private TwilightPlacement(StructurePlacement placement) throws ReflectiveOperationException {
        this.placement = placement;
        check = placement.getClass().getMethod("isTFPlacementChunk", ChunkGenerator.class,
                ChunkGeneratorStructureState.class, int.class, int.class);
        center = Class.forName("twilightforest.util.LegacyLandmarkPlacements", false,
                placement.getClass().getClassLoader()).getMethod("getNearestCenterXZ", int.class, int.class);
    }
    static TwilightPlacement of(StructurePlacement placement) {
        if (!placement.getClass().getName().equals(TYPE)) return null;
        try { return new TwilightPlacement(placement); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("暮色森林放置接口不兼容", error); }
    }
    boolean backgroundAllowed(ChunkGenerator generator) {
        // 4.3.2508 原生群系缓存有同步保护；未知包装器不做后台调用。
        return generator.getClass().getName().equals("twilightforest.world.components.chunkgenerators.ChunkGeneratorTwilight")
                && generator.getBiomeSource().getClass().getName().equals("twilightforest.world.components.biomesources.TFBiomeProvider");
    }
    BlockPos center(int regionX, int regionZ) {
        try { return (BlockPos)center.invoke(null, regionX * 16, regionZ * 16); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("暮色中心计算失败", error); }
    }
    boolean contains(ChunkGenerator generator, ChunkGeneratorStructureState state, int x, int z) {
        try {
            return (boolean)check.invoke(placement, generator, state, x, z) || placement.isStructureChunk(state, x, z);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("暮色放置判断失败", error); }
    }
}
