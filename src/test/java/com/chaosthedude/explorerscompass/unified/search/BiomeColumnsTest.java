package com.chaosthedude.explorerscompass.unified.search;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class BiomeColumnsTest {
    record Sample(int x, int y, int z) {}
    @Test void combinedColumnsPreserveExactOldMultiHeightSamplingSet() {
        int radius = 512, spacing = 32;
        var geometry = new RectFrontier.Geometry(-17, 31, spacing, -17, 31, -17, 31);
        var heights = List.of(64, 0, 128, -64, 192, 256, 320);
        var actual = new HashSet<Sample>(); var expected = new HashSet<Sample>();
        var budget = new SamplingBudget(10000);
        for (var domain : RectFrontier.partition(geometry.domain(radius), 4)) {
            var lane = new BiomeColumnLane(geometry, domain, (long)radius * radius, budget, heights, 4, true,
                    (x, z, y) -> { assertTrue(actual.add(new Sample(x, y, z))); return List.of(); });
            while (lane.available((long)radius * radius)) lane.sample();
        }
        for (int y : heights) {
            int stride = y == 64 ? 1 : 4;
            for (int x = -16; x <= 16; x += stride) for (int z = -16; z <= 16; z += stride)
                if ((long)x * x * spacing * spacing + (long)z * z * spacing * spacing <= (long)radius * radius)
                    expected.add(new Sample(x, y, z));
        }
        assertEquals(expected, actual); assertEquals(actual.size(), budget.used());
    }
    @Test void quotaInMiddleOfColumnRetainsUnprocessedHeightInProof() {
        var geometry = new RectFrontier.Geometry(0, 0, 32, 0, 0, 0, 0);
        var budget = new SamplingBudget(1);
        var lane = new BiomeColumnLane(geometry, new RectFrontier.Rect(0, 0, 0, 0), 1024, budget,
                List.of(64, 0), 4, true, (x,z,y) -> List.of());
        lane.sample(); assertFalse(lane.available(1024)); assertEquals(0, lane.pendingLowerBound());
        assertTrue(lane.budgetLimited(1024));
    }
    @Test void disabledVerticalSamplingDoesNotAddAnyDepthGrid() {
        var geometry = new RectFrontier.Geometry(0, 0, 32, 0, 0, 0, 0); var calls = new int[1];
        var lane = new BiomeColumnLane(geometry, new RectFrontier.Rect(0,0,0,0), 1024, new SamplingBudget(2),
                List.of(64), 4, false, (x,z,y) -> { calls[0]++; return List.of(); });
        lane.sample(); assertEquals(1,calls[0]); assertEquals(Long.MAX_VALUE,lane.pendingLowerBound());
    }
}
