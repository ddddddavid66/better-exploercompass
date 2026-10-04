package com.chaosthedude.explorerscompass.unified.search;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.Executors;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.Test;

class PredictionHeightsTest {
    private final LevelHeightAccessor bounds = LevelHeightAccessor.create(-64, 384);
    private final Heightmap.Types surface = Heightmap.Types.WORLD_SURFACE_WG;

    @Test void keepsExactColumnsHeightmapAndWorldContextSeparate() {
        var cache = new PredictionHeights(null, null, bounds);
        var otherWorld = new PredictionHeights(null, null, bounds);
        cache.with(() -> {
            PredictionHeights.remember(null, -17, 33, surface, bounds, null, 77);
            assertEquals(77, PredictionHeights.cached(null, -17, 33, surface, bounds, null));
            assertNull(PredictionHeights.cached(null, -16, 33, surface, bounds, null));
            assertNull(PredictionHeights.cached(null, -17, 33, Heightmap.Types.OCEAN_FLOOR_WG, bounds, null));
            assertNull(PredictionHeights.cached(new Object(), -17, 33, surface, bounds, null));
            assertNull(PredictionHeights.cached(null, -17, 33, surface, LevelHeightAccessor.create(0, 128), null));
            otherWorld.with(() -> { assertNull(PredictionHeights.cached(null, -17, 33, surface, bounds, null)); return null; });
            assertEquals(77, PredictionHeights.cached(null, -17, 33, surface, bounds, null));
            return null;
        });
        assertNull(PredictionHeights.cached(null, -17, 33, surface, bounds, null));
    }

    @Test void exceptionsRestoreNestedAndOuterThreadScopes() {
        var outer = new PredictionHeights(null, null, bounds);
        var inner = new PredictionHeights(null, null, bounds);
        outer.with(() -> {
            PredictionHeights.remember(null, 1, 2, surface, bounds, null, 60);
            assertThrows(IllegalStateException.class, () -> inner.with(() -> { throw new IllegalStateException(); }));
            assertEquals(60, PredictionHeights.cached(null, 1, 2, surface, bounds, null));
            return null;
        });
        assertThrows(IllegalStateException.class, () -> outer.with(() -> { throw new IllegalStateException(); }));
        assertNull(PredictionHeights.cached(null, 1, 2, surface, bounds, null));
    }

    @Test void concurrentPredictionScopesShareValuesWithoutLeakingToUnscopedTasks() throws Exception {
        var cache = new PredictionHeights(null, null, bounds);
        var pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> cache.with(() -> { PredictionHeights.remember(null, 10, 11, surface, bounds, null, 64); return null; })).get();
            assertEquals(64, pool.submit(() -> cache.with(() -> PredictionHeights.cached(null, 10, 11, surface, bounds, null))).get());
            assertNull(pool.submit(() -> PredictionHeights.cached(null, 10, 11, surface, bounds, null)).get());
        } finally { pool.shutdownNow(); }
    }

    @Test void heightCacheDoesNotGrowBeyondItsGlobalCapacity() {
        var cache = new PredictionHeights(null, null, bounds);
        cache.with(() -> {
            for (int x = 0; x < 40_000; x++) PredictionHeights.remember(null, x, 0, surface, bounds, null, 72);
            return null;
        });
        assertEquals(32_768, cache.size());
    }
}
