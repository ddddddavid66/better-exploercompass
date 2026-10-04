package com.chaosthedude.explorerscompass.unified.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 有限整数域的增量矩形遍历；下界来自所有可能定位锚点的包围范围。 */
public final class RectFrontier {
    public record Rect(int minX, int minZ, int maxX, int maxZ) {
        public Rect {
            if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("Empty rectangle");
        }
        long width() { return (long)maxX - minX + 1; }
        long depth() { return (long)maxZ - minZ + 1; }
        Rect[] split() {
            if (width() >= depth() && width() > 1) {
                int middle = (int)(minX + (width() - 1) / 2);
                return new Rect[]{new Rect(minX, minZ, middle, maxZ), new Rect(middle + 1, minZ, maxX, maxZ)};
            }
            int middle = (int)(minZ + (depth() - 1) / 2);
            return new Rect[]{new Rect(minX, minZ, maxX, middle), new Rect(minX, middle + 1, maxX, maxZ)};
        }
    }
    /** index*step+[minOffset,maxOffset] 必须包含预测和真实核对后的全部锚点。 */
    public record Geometry(long originX, long originZ, int step,
                           long minOffsetX, long minOffsetZ, long maxOffsetX, long maxOffsetZ) {
        public Geometry {
            if (step < 1 || minOffsetX > maxOffsetX || minOffsetZ > maxOffsetZ)
                throw new IllegalArgumentException("Invalid geometry");
        }
        public long lowerBound(Rect rect) {
            return lowerBound(rect.minX, rect.minZ, rect.maxX, rect.maxZ);
        }
        public long lowerBound(int minX, int minZ, int maxX, int maxZ) {
            long dx = gap(originX, (long)minX * step + minOffsetX, (long)maxX * step + maxOffsetX);
            long dz = gap(originZ, (long)minZ * step + minOffsetZ, (long)maxZ * step + maxOffsetZ);
            // Minecraft 世界边界内平方和不会溢出 long；异常数据显式拒绝而非绕回负数。
            return Math.addExact(Math.multiplyExact(dx, dx), Math.multiplyExact(dz, dz));
        }
        public Rect domain(int radius) {
            if (radius < 0) throw new IllegalArgumentException();
            int minX = indexCeil(originX - radius - maxOffsetX), minZ = indexCeil(originZ - radius - maxOffsetZ);
            int maxX = indexFloor(originX + radius - minOffsetX), maxZ = indexFloor(originZ + radius - minOffsetZ);
            return minX > maxX || minZ > maxZ ? null : new Rect(minX, minZ, maxX, maxZ);
        }
        private int indexFloor(long value) { return Math.toIntExact(Math.floorDiv(value, step)); }
        private int indexCeil(long value) { return Math.toIntExact(-Math.floorDiv(-value, step)); }
        long xGap(int index) { return gap(originX, (long)index * step + minOffsetX, (long)index * step + maxOffsetX); }
        long zGap(int index) { return gap(originZ, (long)index * step + minOffsetZ, (long)index * step + maxOffsetZ); }
        int nearestX(int min, int max) { return nearest(originX, minOffsetX, maxOffsetX, min, max, true); }
        int nearestZ(int min, int max) { return nearest(originZ, minOffsetZ, maxOffsetZ, min, max, false); }
        private int nearest(long origin, long low, long high, int min, int max, boolean x) {
            long center = low + (high - low) / 2;
            long index = Math.floorDiv(origin - center, step);
            int first = (int)Math.max(min, Math.min(max, index)), second = (int)Math.max(min, Math.min(max, index + 1));
            return (x ? xGap(first) <= xGap(second) : zGap(first) <= zGap(second)) ? first : second;
        }
    }
    private final Geometry geometry;
    private final int leafSize;
    private final long maximum;
    // 原始类型二叉堆：不为每个分块分配 Node/Rect，也不为每个采样点建树。
    private int[] minXs = new int[16], minZs = new int[16], maxXs = new int[16], maxZs = new int[16];
    private long[] bounds = new long[16];
    private int size, x, z, leafMinX, leafMaxX, leafMinZ, leafMaxZ, rowStartX, leftX, rightX, leftZ, rightZ;
    private boolean leaf;
    private long leafBound;

    public RectFrontier(Geometry geometry, Rect domain, int leafSize, long maximumSquared) {
        if (leafSize < 1 || maximumSquared < 0) throw new IllegalArgumentException();
        this.geometry = geometry; this.leafSize = leafSize; maximum = maximumSquared;
        add(domain.minX, domain.minZ, domain.maxX, domain.maxZ);
    }
    private void add(int minX, int minZ, int maxX, int maxZ) {
        long bound = geometry.lowerBound(minX, minZ, maxX, maxZ);
        if (bound > maximum) return;
        if (size == bounds.length) {
            int capacity = size * 2;
            minXs = java.util.Arrays.copyOf(minXs, capacity); minZs = java.util.Arrays.copyOf(minZs, capacity);
            maxXs = java.util.Arrays.copyOf(maxXs, capacity); maxZs = java.util.Arrays.copyOf(maxZs, capacity);
            bounds = java.util.Arrays.copyOf(bounds, capacity);
        }
        int index = size++;
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            if (!before(bound, minX, minZ, bounds[parent], minXs[parent], minZs[parent])) break;
            copy(parent, index); index = parent;
        }
        put(index, minX, minZ, maxX, maxZ, bound);
    }
    private static boolean before(long bound, int x, int z, long other, int ox, int oz) {
        return bound < other || bound == other && (x < ox || x == ox && z < oz);
    }
    private void put(int index, int minX, int minZ, int maxX, int maxZ, long bound) {
        minXs[index] = minX; minZs[index] = minZ; maxXs[index] = maxX; maxZs[index] = maxZ; bounds[index] = bound;
    }
    private void copy(int from, int to) { put(to, minXs[from], minZs[from], maxXs[from], maxZs[from], bounds[from]); }
    private void removeRoot() {
        int last = --size;
        if (last == 0) return;
        int minX = minXs[last], minZ = minZs[last], maxX = maxXs[last], maxZ = maxZs[last]; long bound = bounds[last];
        int index = 0;
        while (index * 2 + 1 < size) {
            int child = index * 2 + 1;
            if (child + 1 < size && before(bounds[child + 1], minXs[child + 1], minZs[child + 1], bounds[child], minXs[child], minZs[child])) child++;
            if (!before(bounds[child], minXs[child], minZs[child], bound, minX, minZ)) break;
            copy(child, index); index = child;
        }
        put(index, minX, minZ, maxX, maxZ, bound);
    }
    /** 下界不依赖采样额度。额度为零时未覆盖范围仍然存在。 */
    public long lowerBound() {
        return Math.min(leaf ? leafBound : Long.MAX_VALUE, size == 0 ? Long.MAX_VALUE : bounds[0]);
    }
    public boolean available(long limit) { return lowerBound() <= limit; }
    public long next() {
        if (!leaf) {
            while (size > 0) {
                int minX = minXs[0], minZ = minZs[0], maxX = maxXs[0], maxZ = maxZs[0]; long bound = bounds[0];
                removeRoot();
                long width = (long)maxX - minX + 1, depth = (long)maxZ - minZ + 1;
                if (width > leafSize || depth > leafSize) {
                    if (width >= depth && width > 1) {
                        int middle = (int)(minX + (width - 1) / 2);
                        add(minX, minZ, middle, maxZ); add(middle + 1, minZ, maxX, maxZ);
                    } else {
                        int middle = (int)(minZ + (depth - 1) / 2);
                        add(minX, minZ, maxX, middle); add(minX, middle + 1, maxX, maxZ);
                    }
                    continue;
                }
                leaf = true; leafBound = bound; leafMinX = minX; leafMaxX = maxX; leafMinZ = minZ; leafMaxZ = maxZ;
                rowStartX = geometry.nearestX(minX, maxX); x = rowStartX; leftX = x - 1; rightX = x + 1;
                z = geometry.nearestZ(minZ, maxZ); leftZ = z - 1; rightZ = z + 1; break;
            }
        }
        if (!leaf) throw new IllegalStateException("Exhausted rectangle domain");
        long point = ((long)x << 32) | (z & 0xffffffffL);
        // 叶内从最接近查询的行开始，每行也从近到远。没有圆环、条带或平方根游标。
        if (leftX >= leafMinX || rightX <= leafMaxX) {
            x = rightX > leafMaxX || leftX >= leafMinX && geometry.xGap(leftX) <= geometry.xGap(rightX) ? leftX-- : rightX++;
        } else if (leftZ >= leafMinZ || rightZ <= leafMaxZ) {
            z = rightZ > leafMaxZ || leftZ >= leafMinZ && geometry.zGap(leftZ) <= geometry.zGap(rightZ) ? leftZ-- : rightZ++;
            x = rowStartX; leftX = x - 1; rightX = x + 1;
            long dx = gap(geometry.originX, (long)leafMinX * geometry.step + geometry.minOffsetX,
                    (long)leafMaxX * geometry.step + geometry.maxOffsetX);
            long dz = geometry.zGap(z);
            leafBound = Math.addExact(Math.multiplyExact(dx, dx), Math.multiplyExact(dz, dz));
        } else leaf = false;
        return point;
    }
    public boolean inRange(int x, int z) { return geometry.lowerBound(x, z, x, z) <= maximum; }
    public boolean inRange(int x, int z, long verifiedUpper) { return geometry.lowerBound(x,z,x,z) <= Math.min(maximum, verifiedUpper); }
    public static long gap(long value, long min, long max) {
        return value < min ? min - value : value > max ? value - max : 0;
    }
    /** 初始分区同样二分矩形，不按 X 条带划分；每点恰好归属一个任务。 */
    public static List<Rect> partition(Rect root, int count) {
        if (count < 1) throw new IllegalArgumentException();
        if (root == null) return List.of();
        List<Rect> parts = new ArrayList<>(); parts.add(root);
        while (parts.size() < count) {
            Rect largest = parts.stream().max(Comparator.comparingLong(rect -> rect.width() * rect.depth())).orElseThrow();
            if (largest.width() == 1 && largest.depth() == 1) break;
            parts.remove(largest); parts.addAll(List.of(largest.split()));
        }
        return List.copyOf(parts);
    }
}
