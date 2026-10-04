package com.chaosthedude.explorerscompass.unified.search;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RectFrontierTest {
    @Test void radiusInsidePlacementGapProducesEmptyDomain() {
        var geometry = new RectFrontier.Geometry(510, 510, 512, 0, 0, 496, 496);
        assertTrue(RectFrontier.partition(geometry.domain(1), 4).isEmpty());
    }
    @Test void partitionAndTraversalMatchFiniteBruteForceWithNegativeCoordinates() {
        Random random = new Random(789123);
        for (int trial = 0; trial < 100; trial++) {
            int step = 1 + random.nextInt(32), radius = 20 + random.nextInt(100);
            long x = random.nextInt(256) - 128, z = random.nextInt(256) - 128;
            int min = random.nextInt(40) - 30, max = min + random.nextInt(50);
            var geometry = new RectFrontier.Geometry(x, z, step, min, min, max, max);
            var expected = new HashSet<Long>();
            for (int ix = -300; ix <= 300; ix++) for (int iz = -300; iz <= 300; iz++) {
                long dx = RectFrontier.gap(x, (long)ix * step + min, (long)ix * step + max);
                long dz = RectFrontier.gap(z, (long)iz * step + min, (long)iz * step + max);
                if (dx * dx + dz * dz <= (long)radius * radius) expected.add(((long)ix << 32) | (iz & 0xffffffffL));
            }
            var actual = new HashSet<Long>();
            for (var domain : RectFrontier.partition(geometry.domain(radius), 4)) {
                var frontier = new RectFrontier(geometry, domain, 8, (long)radius * radius);
                while (frontier.available((long)radius * radius)) {
                    long point = frontier.next();
                    if (frontier.inRange((int)(point >> 32), (int)point)) assertTrue(actual.add(point), "duplicate");
                }
            }
            assertEquals(expected, actual, "trial " + trial);
        }
    }
    @Test void domainIncludesJitterBeyondCellAndOffsetEqualToSpacing() {
        var geometry = new RectFrontier.Geometry(-17, 31, 320, -19, 29, 320 + 29, 320 + 29);
        var domain = geometry.domain(40000);
        for (int x = domain.minX(); x <= domain.maxX(); x++) for (int z = domain.minZ(); z <= domain.maxZ(); z++) {
            long lower = geometry.lowerBound(x, z, x, z);
            for (int jitter : new int[]{0, 320}) {
                long dx = (long)x * 320 - 19 + jitter + 17, dz = (long)z * 320 + 29 + jitter - 31;
                assertTrue(lower <= dx * dx + dz * dz);
            }
        }
    }
    @Test void completeFortyThousandBiomeGridKeepsOriginalCoverage() {
        long main = countGrid(32), depth = countGrid(128);
        assertEquals(bruteCircle(32), main); assertEquals(bruteCircle(128), depth);
        assertTrue(main + 6 * depth < 8_000_000L);
    }
    private long bruteCircle(int spacing) {
        long count = 0;
        for (int x = -40000 / spacing; x <= 40000 / spacing; x++)
            for (int z = -40000 / spacing; z <= 40000 / spacing; z++)
                if ((long)x * x * spacing * spacing + (long)z * z * spacing * spacing <= 1_600_000_000L) count++;
        return count;
    }
    private long countGrid(int spacing) {
        var geometry = new RectFrontier.Geometry(0, 0, spacing, 0, 0, 0, 0);
        var frontier = new RectFrontier(geometry, geometry.domain(40000), 16, 1_600_000_000L);
        long count = 0;
        while (frontier.available(1_600_000_000L)) {
            long point = frontier.next();
            if (frontier.inRange((int)(point >> 32), (int)point)) count++;
        }
        return count;
    }
    @Test void lowerBoundUsesLongAndRejectsOverflow() {
        var geometry = new RectFrontier.Geometry(30_000_000, 30_000_000, 16, 0, 0, 15, 15);
        assertTrue(geometry.lowerBound(-1_875_000, -1_875_000, -1_875_000, -1_875_000) > Integer.MAX_VALUE);
        var huge = new RectFrontier.Geometry(Long.MIN_VALUE, 0, 1, 0, 0, 0, 0);
        assertThrows(ArithmeticException.class, () -> huge.lowerBound(1, 1, 1, 1));
    }
    @Test void partialLeafRemainsInNearestProof() {
        var geometry = new RectFrontier.Geometry(0, 0, 1, 0, 0, 0, 0);
        var frontier = new RectFrontier(geometry, new RectFrontier.Rect(-3, -3, 3, 3), 8, 100);
        frontier.next(); assertEquals(0, frontier.lowerBound());
    }
}
