package com.chaosthedude.explorerscompass.unified.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

public final class BiomeSearch extends SearchJob {
    private static final Comparator<SearchCandidate> COORDINATES=Comparator
            .<SearchCandidate>comparingInt(candidate->candidate.position().getX())
            .thenComparingInt(candidate->candidate.position().getZ()).thenComparingInt(candidate->candidate.position().getY());
    private final ResourceLocation target;
    private final List<BlockPos> excluded;
    private final long maxSamples;
    private final int spacing, depthInterval, innerRadius;
    private final boolean async,exact;
    private final BiomeSamplingProfile profile;
    private boolean cachedOrigin;
    private WorldgenCache context;
    private List<?> memoKey;
    private WorldgenCache.Prefix memo;
    public BiomeSearch(ServerLevel level, BlockPos start, ResourceLocation target, List<BlockPos> excluded,
                       int radius, long maxSamples, int spacing, int vertical, int depthInterval, boolean async) {
        this(level,start,target,excluded,radius,maxSamples,spacing,vertical,depthInterval,async,false);
    }
    public BiomeSearch(ServerLevel level,BlockPos start,ResourceLocation target,List<BlockPos> excluded,
                       int radius,long maxSamples,int spacing,int vertical,int depthInterval,boolean async,boolean exact) {
        this(level,start,target,excluded,radius,maxSamples,spacing,vertical,depthInterval,async,exact,-1);
    }
    public BiomeSearch(ServerLevel level,BlockPos start,ResourceLocation target,List<BlockPos> excluded,
                       int radius,long maxSamples,int spacing,int vertical,int depthInterval,boolean async,boolean exact,int innerRadius) {
        this(level,start,target,excluded,radius,maxSamples,async,exact,innerRadius,
                BiomeSamplingProfile.legacy(level.getMinBuildHeight(),level.getMaxBuildHeight(),start.getY(),spacing,vertical,depthInterval));
    }
    BiomeSearch(ServerLevel level,BlockPos start,ResourceLocation target,List<BlockPos> excluded,
                int radius,long maxSamples,boolean async,boolean exact,int innerRadius,BiomeSamplingProfile profile) {
        super(level, start, radius); this.target = target; this.excluded = List.copyOf(excluded);this.exact=exact;this.innerRadius=innerRadius;
        this.maxSamples = maxSamples;this.profile=profile;this.spacing = profile.spacing();
        this.depthInterval = profile.depthInterval(); this.async = async;
        quotaDisabled = radius > 0 && maxSamples <= 0;
        scan = new ScanCoordinator<>(createLanes(), SearchCandidate::distanceSquared, ignored -> true, (long)radius * radius, async)
                .withTieBreaker(Comparator.<SearchCandidate>comparingInt(candidate -> Math.abs(candidate.position().getY() - start.getY()))
                        .thenComparing(COORDINATES)).withBatch(1024, 4_000_000L)
                .withEagerCandidates(false).withUnconditionalConfirmation();
        if(memo!=null)scan.withProvenSample(memo.candidates.get(0));
    }
    private List<ScanCoordinator.Lane<SearchCandidate>> createLanes() {
        if (radius <= 0 || maxSamples <= 0) return List.of();
        BlockPos origin = start;
        int maximumRadius = radius;
        boolean safeSource = BiomeSourceSafety.prepare(level).allowed();
        boolean background = async && safeSource;
        var cache = safeSource ? WorldgenCache.get(level) : null;
        SamplingBudget budget = new SamplingBudget(maxSamples); budgets.add(budget);
        if(background && innerRadius<0) {
            context=cache;
            // 精确绑定起点 XYZ、采样设置和完整排除快照，不推断相邻起点的最近群系。
            memoKey=List.of(target,start,profile,exact,excluded);
            var known=cache.prefix(memoKey);
            if(known!=null && known.upper<=(long)radius*radius && known.cost<=maxSamples && budget.take(known.cost)) {
                memo=known;return List.of();
            }
        }
        var ignoredPositions = new com.chaosthedude.explorerscompass.unified.data.ExclusionIndex(excluded.stream()
                .map(point -> new com.chaosthedude.explorerscompass.unified.data.ExclusionIndex.Point(point.getX(), point.getZ())).toList());
        var failed=new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for(var point:excluded)failed.add(point.asLong());
        ResourceLocation key = target;
        var source = level.getChunkSource().getGenerator().getBiomeSource();
        var noise = level.getChunkSource().randomState().sampler();
        var biome = level.registryAccess().registryOrThrow(Registries.BIOME).get(target);
        if (biome == null || source.possibleBiomes().stream().noneMatch(holder -> holder.value() == biome)) return List.of();
        int initialY = Math.max(level.getMinBuildHeight(), Math.min(level.getMaxBuildHeight() - 1, start.getY()));
        List<Integer> heights = profile.heights();
        cachedOrigin=cache!=null && cache.peekBiome(QuartPos.fromBlock(origin.getX()),
                QuartPos.fromBlock(initialY),QuartPos.fromBlock(origin.getZ()))==biome;
        int workers = background ? SearchConfig.threads() : 1;
        List<ScanCoordinator.Lane<SearchCandidate>> lanes = new ArrayList<>();
        var geometry = new RectFrontier.Geometry(origin.getX(), origin.getZ(), spacing,
                origin.getX(), origin.getZ(), origin.getX(), origin.getZ());
        for (var partition : RectFrontier.partition(geometry.domain(radius), workers)) {
            // 每个分片只持有当前列的气候快照，不在不同后台线程之间共享可变游标。
            final class ColumnSampler implements BiomeColumnLane.Sampler {
                private int lastX=Integer.MIN_VALUE,lastZ=Integer.MIN_VALUE;
                private WorldgenCache.StreamingColumn column;
                private WorldgenCache.BiomeColumn hotColumn;
                public List<SearchCandidate> sample(int dx,int dz,int y) {
                    int x=origin.getX()+dx*spacing,z=origin.getZ()+dz*spacing;
                    if(exact?failed.contains(BlockPos.asLong(x,y,z)):ignoredPositions.excludes(x,z))return List.of();
                    int qx=QuartPos.fromBlock(x),qy=QuartPos.fromBlock(y),qz=QuartPos.fromBlock(z);
                    if(cache!=null && (column==null || lastX!=qx || lastZ!=qz)) {
                        column=cache.streamingColumn(qx,qz);hotColumn=dx==0 && dz==0?cache.biomeColumn(qx,qz):null;lastX=qx;lastZ=qz;
                    }
                    // 仅原点列写入热点缓存；远处一次性样本不产生LRU条目。
                    var actual=cache==null?source.getNoiseBiome(qx,qy,qz,noise).value():
                            hotColumn!=null?hotColumn.predict(qy):column.predict(qy);
                    if(actual!=biome)return List.of();
                    BlockPos position=new BlockPos(x,y,z);
                    return List.of(new SearchCandidate(position,key,null,null,null,distance(origin,position)));
                }
            }
            var lane=new BiomeColumnLane(geometry, partition, (long)radius * radius, budget,
                    heights, depthInterval, background, new ColumnSampler()).withPrimaryHeights(profile.primaryHeights());
            lane.innerRadius(innerRadius);
            if(cache!=null)lane.withCachedSampler((dx,dz,y)-> {
                // 仅复用零水平距离列，远处群系继续并行，避免将大范围热扫描搬到主线程。
                if(dx!=0 || dz!=0)return null;
                int x=origin.getX()+dx*spacing,z=origin.getZ()+dz*spacing;
                if(exact?failed.contains(BlockPos.asLong(x,y,z)):ignoredPositions.excludes(x,z))return List.of();
                var known=cache.peekBiome(QuartPos.fromBlock(x),QuartPos.fromBlock(y),QuartPos.fromBlock(z));
                if(known==null)return null;
                if(known!=biome)return List.of();
                BlockPos point=new BlockPos(x,y,z);
                return List.of(new SearchCandidate(point,key,null,null,null,distance(origin,point)));
            });
            lanes.add(lane);
        }
        return lanes;
    }
    @Override public boolean step() throws Exception {
        boolean complete=super.step();
        if(complete && memoKey!=null && memo==null && scan.result()!=null) {
            long before=System.nanoTime();
            try {
                memo=new WorldgenCache.Prefix(start.getX(),start.getZ(),scan.result().distanceSquared(),
                        samples(),java.util.Set.of(),List.of(scan.result()));
                context.rememberPrefix(memoKey,memo);
            }finally{mainNanos+=System.nanoTime()-before;}
        }
        return complete;
    }
    @Override public void prime() {if(memo==null && !cachedOrigin)super.prime();}
    @Override public String mode() {return memo!=null?"async":super.mode();}
    public SearchJob synchronousFallback() { return new BiomeSearch(level,start,target,excluded,radius,maxSamples,false,exact,innerRadius,profile); }
    public String samplingPlan(){return profile.description()+";limit="+maxSamples+";exact="+exact+";excluded="+excluded.size();}
    public boolean biome() { return true; }
    public boolean asynchronous() { return async; }
}
