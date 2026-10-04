package com.chaosthedude.explorerscompass.unified.search;

import java.util.List;

/** 共享 X/Z 列游标；其他高度只采样旧网格的交点，保留每个 XYZ 与计数口径。 */
final class BiomeColumnLane implements ScanCoordinator.Lane<SearchCandidate> {
    interface Sampler { List<SearchCandidate> sample(int x,int z,int height); }
    private final RectFrontier frontier;
    private final RectFrontier.Geometry geometry;
    private final SamplingBudget budget;
    private final List<Integer> heights;
    private final int interval;
    private final boolean background;
    private final Sampler sampler;
    private Sampler cachedSampler;
    private int x, z, height;
    private boolean column;
    BiomeColumnLane(RectFrontier.Geometry geometry, RectFrontier.Rect domain, long maximum, SamplingBudget budget,
                    List<Integer> heights, int interval, boolean background, Sampler sampler) {
        this.geometry = geometry; frontier = new RectFrontier(geometry, domain, 16, maximum);
        this.budget = budget; this.heights = heights; this.interval = interval; this.background = background; this.sampler = sampler;
    }
    public long pendingLowerBound() {
        return Math.min(frontier.lowerBound(), column ? geometry.lowerBound(x, z, x, z) : Long.MAX_VALUE);
    }
    public boolean available(long limit) { return budget.available() && pendingLowerBound() <= limit; }
    public boolean budgetLimited(long maximum) { return !budget.available() && pendingLowerBound() <= maximum; }
    public boolean backgroundAllowed() { return background; }
    public List<SearchCandidate> sample() {
        return sample(Long.MAX_VALUE);
    }
    public List<SearchCandidate> sample(long verifiedUpper) {
        return sample(verifiedUpper,false);
    }
    public List<SearchCandidate> sampleCached(long verifiedUpper) {
        return cachedSampler==null?null:sample(verifiedUpper,true);
    }
    BiomeColumnLane withCachedSampler(Sampler cached) {cachedSampler=cached;return this;}
    private List<SearchCandidate> sample(long verifiedUpper,boolean cached) {
        if (column && geometry.lowerBound(x,z,x,z) > verifiedUpper) { column = false; return List.of(); }
        if (!column) {
            long point = frontier.next(); x = (int)(point >> 32); z = (int)point;
            if (!frontier.inRange(x, z, verifiedUpper)) return List.of();
            column = true; height = 0;
        }
        List<SearchCandidate> values=null;
        if(cached) {
            values=cachedSampler.sample(x,z,heights.get(height));
            if(values==null)return null; // 列和高度保留，后台接续同一个未查询 XYZ。
        }
        if (!budget.take()) return List.of();
        if(!cached)values=sampler.sample(x,z,heights.get(height));
        height++;
        if (height == heights.size() || Math.floorMod(x, interval) != 0 || Math.floorMod(z, interval) != 0) column = false;
        return values;
    }
}
