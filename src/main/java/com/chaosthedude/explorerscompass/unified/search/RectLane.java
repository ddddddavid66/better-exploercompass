package com.chaosthedude.explorerscompass.unified.search;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/** 单个矩形分区的串行游标；调度器保证一个分区同时只有一个采样任务。 */
public final class RectLane<T> implements ScanCoordinator.Lane<T> {
    private final RectFrontier frontier;
    private final RectFrontier.Geometry geometry;
    private long unsampledBound=Long.MAX_VALUE;
    private final SamplingBudget budget;
    private final BiFunction<Integer, Integer, List<T>> sampler;
    private final boolean background;
    private BiFunction<Integer,Integer,List<T>> cachedSampler;
    private boolean held;
    private int heldX,heldZ;
    public RectLane(RectFrontier.Geometry geometry, RectFrontier.Rect domain, int leafSize,
                    long maximum, SamplingBudget budget, BiFunction<Integer, Integer, List<T>> sampler, boolean background) {
        frontier = new RectFrontier(geometry, domain, leafSize, maximum);
        this.geometry=geometry;
        this.budget = budget; this.sampler = sampler; this.background = background;
    }
    public boolean available(long limit) {
        long bound=pendingLowerBound();return budget.available() && bound!=Long.MAX_VALUE && bound<=limit;
    }
    public List<T> sample() {
        return sample(Long.MAX_VALUE);
    }
    public List<T> sample(long verifiedUpper) {
        nextPoint();int x=heldX,z=heldZ;held=false;
        if (!frontier.inRange(x, z, verifiedUpper)) return List.of();
        if (!budget.take()) {
            // 其他分片可能抢走最后一个额度；游标已推进，这个未查询点必须继续参与证明。
            unsampledBound=Math.min(unsampledBound,geometry.lowerBound(x,z,x,z));
            return List.of();
        }
        return sampler.apply(x, z);
    }
    private void nextPoint() {
        if(held)return;
        long point=frontier.next();heldX=(int)(point>>32);heldZ=(int)point;held=true;
    }
    public List<T> sampleCached(long verifiedUpper) {
        if(cachedSampler==null)return null;
        nextPoint();
        if(!frontier.inRange(heldX,heldZ,verifiedUpper)) {held=false;return List.of();}
        List<T> values=cachedSampler.apply(heldX,heldZ);
        if(values==null)return null; // 保留尚未采样点，后台从同一个位置继续。
        if(!budget.take())return List.of();
        held=false;return values;
    }
    public long pendingLowerBound() {
        long bound=Math.min(unsampledBound,frontier.lowerBound());
        return held?Math.min(bound,geometry.lowerBound(heldX,heldZ,heldX,heldZ)):bound;
    }
    public boolean budgetLimited(long maximum) {
        long bound=pendingLowerBound();return !budget.available() && bound!=Long.MAX_VALUE && bound<=maximum;
    }
    public boolean backgroundAllowed() { return background; }
    public static <T> List<ScanCoordinator.Lane<T>> create(RectFrontier.Geometry geometry, int radius, int leaf,
            int parallelism, SamplingBudget budget, BiFunction<Integer, Integer, List<T>> sampler, boolean background) {
        return create(geometry,radius,leaf,parallelism,budget,sampler,background,null);
    }
    public static <T> List<ScanCoordinator.Lane<T>> create(RectFrontier.Geometry geometry,int radius,int leaf,
            int parallelism,SamplingBudget budget,BiFunction<Integer,Integer,List<T>> sampler,boolean background,
            BiFunction<Integer,Integer,List<T>> cachedSampler) {
        List<ScanCoordinator.Lane<T>> lanes = new ArrayList<>();
        for (var partition : RectFrontier.partition(geometry.domain(radius), parallelism)) {
            var lane=new RectLane<>(geometry, partition, leaf, (long)radius * radius, budget, sampler, background);
            lane.cachedSampler=cachedSampler;lanes.add(lane);
        }
        return lanes;
    }
}
