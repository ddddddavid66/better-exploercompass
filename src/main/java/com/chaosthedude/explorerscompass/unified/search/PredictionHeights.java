package com.chaosthedude.explorerscompass.unified.search;

import com.chaosthedude.explorerscompass.unified.data.BoundedMemoizer;
import java.util.function.Supplier;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/** 每个生成上下文独立的精确列高缓存，只在显式预测作用域内启用。 */
public final class PredictionHeights {
    private record Key(int x, int z, Heightmap.Types type) {}
    private static final ThreadLocal<PredictionHeights> CURRENT = new ThreadLocal<>();
    private final ChunkGenerator generator;
    private final RandomState noise;
    private final int minimum, height;
    private final BoundedMemoizer<Key, Integer> values = new BoundedMemoizer<>(32_768);

    PredictionHeights(ChunkGenerator generator, RandomState noise, LevelHeightAccessor bounds) {
        this.generator = generator; this.noise = noise;
        minimum = bounds.getMinBuildHeight(); height = bounds.getHeight();
    }
    private boolean matches(Object generator, RandomState noise, LevelHeightAccessor bounds) {
        return this.generator == generator && this.noise == noise
                && minimum == bounds.getMinBuildHeight() && height == bounds.getHeight();
    }
    public static Integer cached(Object generator, int x, int z, Heightmap.Types type,
                                 LevelHeightAccessor bounds, RandomState noise) {
        var cache = CURRENT.get();
        return cache != null && cache.matches(generator, noise, bounds)
                ? cache.values.peek(new Key(x, z, type)) : null;
    }
    public static void remember(Object generator, int x, int z, Heightmap.Types type,
                                LevelHeightAccessor bounds, RandomState noise, int value) {
        var cache = CURRENT.get();
        if (cache != null && cache.matches(generator, noise, bounds))
            cache.values.remember(new Key(x, z, type), value);
    }
    <T> T with(Supplier<T> action) {
        var previous = CURRENT.get(); CURRENT.set(this);
        try { return action.get(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
    int size() { return values.size(); }
}
