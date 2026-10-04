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
    private final ResourceLocation target;
    private final List<BlockPos> excluded;
    private final int maxSamples, spacing, vertical, depthInterval;
    private final boolean async;
    private boolean cachedOrigin;
    public BiomeSearch(ServerLevel level, BlockPos start, ResourceLocation target, List<BlockPos> excluded,
                       int radius, int maxSamples, int spacing, int vertical, int depthInterval, boolean async) {
        super(level, start, radius); this.target = target; this.excluded = List.copyOf(excluded);
        this.maxSamples = maxSamples; this.spacing = spacing; this.vertical = vertical;
        this.depthInterval = depthInterval; this.async = async;
        quotaDisabled = radius > 0 && maxSamples <= 0;
        scan = new ScanCoordinator<>(createLanes(), SearchCandidate::distanceSquared, ignored -> true, (long)radius * radius, async)
                .withTieBreaker(Comparator.<SearchCandidate>comparingInt(candidate -> Math.abs(candidate.position().getY() - start.getY()))
                        .thenComparingInt(candidate -> candidate.position().getX())
                        .thenComparingInt(candidate -> candidate.position().getZ())
                        .thenComparingInt(candidate -> candidate.position().getY())).withBatch(1024, 4_000_000L)
                .withEagerCandidates(false).withUnconditionalConfirmation();
    }
    private List<ScanCoordinator.Lane<SearchCandidate>> createLanes() {
        if (radius <= 0 || maxSamples <= 0) return List.of();
        BlockPos origin = start;
        int maximumRadius = radius;
        var ignoredPositions = new com.chaosthedude.explorerscompass.unified.data.ExclusionIndex(excluded.stream()
                .map(point -> new com.chaosthedude.explorerscompass.unified.data.ExclusionIndex.Point(point.getX(), point.getZ())).toList());
        ResourceLocation key = target;
        var source = level.getChunkSource().getGenerator().getBiomeSource();
        var noise = level.getChunkSource().randomState().sampler();
        var biome = level.registryAccess().registryOrThrow(Registries.BIOME).get(target);
        if (biome == null || source.possibleBiomes().stream().noneMatch(holder -> holder.value() == biome)) return List.of();
        int initialY = Math.max(level.getMinBuildHeight(), Math.min(level.getMaxBuildHeight() - 1, start.getY()));
        List<Integer> heights = new ArrayList<>(); heights.add(initialY);
        if (vertical > 0) for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y += vertical)
            if (QuartPos.fromBlock(y) != QuartPos.fromBlock(initialY)) heights.add(y);
        heights.sort(Comparator.comparingInt(y -> Math.abs(y - initialY)));
        SamplingBudget budget = new SamplingBudget(maxSamples); budgets.add(budget);
        boolean safeSource = BiomeSourceSafety.prepare(level).allowed();
        boolean background = async && safeSource;
        var cache = safeSource ? WorldgenCache.get(level) : null;
        cachedOrigin=cache!=null && cache.peekBiome(QuartPos.fromBlock(origin.getX()),
                QuartPos.fromBlock(initialY),QuartPos.fromBlock(origin.getZ()))==biome;
        int workers = background ? SearchConfig.threads() : 1;
        List<ScanCoordinator.Lane<SearchCandidate>> lanes = new ArrayList<>();
        var geometry = new RectFrontier.Geometry(origin.getX(), origin.getZ(), spacing,
                origin.getX(), origin.getZ(), origin.getX(), origin.getZ());
        for (var partition : RectFrontier.partition(geometry.domain(radius), workers)) {
            var lane=new BiomeColumnLane(geometry, partition, (long)radius * radius, budget,
                    List.copyOf(heights), depthInterval, background, (dx, dz, y) -> {
                int x = origin.getX() + dx * spacing, z = origin.getZ() + dz * spacing;
                if (ignoredPositions.excludes(x, z)) return List.of();
                int qx = QuartPos.fromBlock(x), qy = QuartPos.fromBlock(y), qz = QuartPos.fromBlock(z);
                if ((cache == null ? source.getNoiseBiome(qx,qy,qz,noise).value() : cache.predictBiome(qx,qy,qz)) != biome)
                    return List.of();
                BlockPos position = new BlockPos(x, y, z);
                return List.of(new SearchCandidate(position, key, null, null, null, distance(origin, position)));
            });
            if(cache!=null)lane.withCachedSampler((dx,dz,y)-> {
                // 仅复用零水平距离列，远处群系继续并行，避免将大范围热扫描搬到主线程。
                if(dx!=0 || dz!=0)return null;
                int x=origin.getX()+dx*spacing,z=origin.getZ()+dz*spacing;
                if(ignoredPositions.excludes(x,z))return List.of();
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
    @Override public void prime() {if(!cachedOrigin)super.prime();}
    public SearchJob synchronousFallback() { return new BiomeSearch(level, start, target, excluded, radius, maxSamples, spacing, vertical, depthInterval, false); }
    public boolean biome() { return true; }
    public boolean asynchronous() { return async; }
}
