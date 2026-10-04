package com.chaosthedude.explorerscompass.unified.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 只按水平距离排除历史位置，保持原有 512 格重复位置规则。 */
public final class ExclusionIndex {
    public record Point(int x, int z) {}
    private static final int SIZE = 512;
    private final Map<Long, List<Point>> buckets = new HashMap<>();
    public ExclusionIndex(Collection<Point> points) {
        for (Point point : points) buckets.computeIfAbsent(key(Math.floorDiv(point.x(), SIZE),
                Math.floorDiv(point.z(), SIZE)), ignored -> new ArrayList<>()).add(point);
    }
    public boolean excludes(int x, int z) {
        int bx = Math.floorDiv(x, SIZE), bz = Math.floorDiv(z, SIZE);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            for (Point point : buckets.getOrDefault(key(bx + dx, bz + dz), List.of())) {
                long px = (long)point.x() - x, pz = (long)point.z() - z;
                if (px * px + pz * pz < (long)SIZE * SIZE) return true;
            }
        return false;
    }
    private static long key(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }
}
