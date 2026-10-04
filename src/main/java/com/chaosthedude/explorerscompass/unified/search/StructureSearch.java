package com.chaosthedude.explorerscompass.unified.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

public final class StructureSearch extends SearchJob {
    private record Target(ResourceLocation key, Structure structure, Predicate<Holder<Biome>> biomes, DungeonsPrediction dungeons) {}
    private final List<ResourceLocation> keys;
    private final Set<Long> excluded;
    private final int maximumSamples;
    private final boolean async;
    private final boolean compatiblePrediction;
    private final StructureVerification verification;
    private final Map<SearchCandidate, SearchCandidate> verified = new java.util.HashMap<>();
    private long preparationNanos;
    private final List<DungeonsPrediction> preparations = new ArrayList<>();
    private boolean deferredPlacement;
    public StructureSearch(ServerLevel level, BlockPos start, List<ResourceLocation> keys,
                           Set<Long> excluded, int radius, int maximumSamples, boolean async) {
        this(level, start, keys, excluded, radius, maximumSamples, async, false);
    }
    private StructureSearch(ServerLevel level, BlockPos start, List<ResourceLocation> keys,
                           Set<Long> excluded, int radius, int maximumSamples, boolean async, boolean compatiblePrediction) {
        super(level, start, radius); this.keys = List.copyOf(keys); this.excluded = Set.copyOf(excluded);
        this.maximumSamples = maximumSamples; this.async = async;
        this.compatiblePrediction = compatiblePrediction;
        verification = new StructureVerification(level, start);
        quotaDisabled = radius > 0 && maximumSamples <= 0;
        scan = new ScanCoordinator<>(createLanes(), SearchCandidate::distanceSquared, ignored -> false,
                (long)radius * radius, async).withTieBreaker(Comparator.comparing((SearchCandidate candidate) -> candidate.key().toString())
                        .thenComparingInt(candidate -> candidate.position().getX()).thenComparingInt(candidate -> candidate.position().getZ()))
                .withBatch(256, 4_000_000L).withEagerCandidates(false).withConfirmation(this::confirm)
                .withCandidateLowerBound(this::candidateBound)
                .withResolvedCandidate(candidate -> verified.getOrDefault(candidate, candidate));
    }
    private List<ScanCoordinator.Lane<SearchCandidate>> createLanes() {
        if (radius <= 0 || maximumSamples <= 0) return List.of();
        BlockPos origin = start;
        int maximumRadius = radius;
        Set<Long> ignoredChunks = excluded;
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        WorldgenCache cached = WorldgenCache.get(level);
        Map<StructurePlacement, List<Target>> groups = new LinkedHashMap<>();
        for (ResourceLocation key : keys) registry.getHolder(net.minecraft.resources.ResourceKey.create(Registries.STRUCTURE, key))
                .ifPresent(holder -> state.getPlacementsForStructure(holder).forEach(placement ->
                    groups.computeIfAbsent(placement, ignored -> new ArrayList<>()).add(
                        new Target(key, holder.value(), cached.biomeRule(holder.value()),
                                !compatiblePrediction && placement.getClass().getName().equals(GelGridPlacement.TYPE)
                                        ? cached.dungeons(level, key, holder.value()) : null))));
        long seed = level.getSeed();
        boolean sourceSafe = BiomeSourceSafety.prepare(level).allowed();
        List<ScanCoordinator.Lane<SearchCandidate>> lanes = new ArrayList<>();
        for (var group : groups.entrySet()) {
            StructurePlacement placement = group.getKey(); List<Target> targets = List.copyOf(group.getValue());
            TwilightPlacement twilight = TwilightPlacement.of(placement);
            GelGridPlacement gel = GelGridPlacement.of(placement, level.registryAccess());
            for (Target target : targets) if (target.dungeons() != null && !preparations.contains(target.dungeons()))
                preparations.add(target.dungeons());
            if (gel != null && gel.exclusion) deferredPlacement = true;
            Set<Long> visited = java.util.concurrent.ConcurrentHashMap.newKeySet();
            var generator = level.getChunkSource().getGenerator();
            boolean supported = placement.getClass() == RandomSpreadStructurePlacement.class
                    || placement.getClass() == ConcentricRingsStructurePlacement.class;
            boolean predict = async && !compatiblePrediction && supported && sourceSafe;
            SamplingBudget budget = new SamplingBudget(maximumSamples); budgets.add(budget);
            // 闭包只引用不可变快照；未知规则的闭包始终在主线程采样。
            BiFunction<Integer, Integer, List<SearchCandidate>> sample = (x, z) -> {
                ChunkPos chunk = new ChunkPos(x, z);
                if (ignoredChunks.contains(chunk.toLong())) return List.of();
                // Gel 排除规则可递归调用其他模组，统一留给主线程。候选重新核对避免 offset==spacing 的边界误报。
                if (!(gel != null && gel.exclusion) && !(twilight == null
                        ? placement.isStructureChunk(state, x, z) : twilight.contains(generator, state, x, z))) return List.of();
                if (gel != null && !visited.add(chunk.toLong())) return List.of();
                BlockPos locate = placement.getLocatePos(chunk);
                // 最终锚点可能与 locate 不同，由候选下界和真实核对统一判断半径。
                List<SearchCandidate> found = new ArrayList<>();
                for (Target target : targets) {
                    DungeonsPrediction policy = target.dungeons();
                    BlockPos point = predict ? cached.predict(target.structure(), target.biomes(), chunk)
                            : gel != null && gel.backgroundAllowed && policy != null && policy.allowed()
                                ? policy.predict(() -> cached.predict(target.structure(), target.biomes(), chunk)) : locate;
                    if (point != null) found.add(new SearchCandidate(new BlockPos(locate.getX(), point.getY(), locate.getZ()),
                            target.key(), target.structure(), placement, chunk, distance(origin, locate)));
                }
                return found;
            };
            int stripes = predict ? SearchConfig.threads() : 1;
            if (gel != null) {
                boolean background = async && gel.backgroundAllowed && sourceSafe;
                int count = background ? SearchConfig.threads() : 1;
                BlockPos offset = placement.getLocatePos(new ChunkPos(0, 0));
                // 官方 offset 是包含端点的非负区块抖动，可能落入相邻区域。
                var geometry = geometry(gel.spacing * 16, offset, 0, gel.offset * 16, targets);
                lanes.addAll(RectLane.create(geometry, radius, 8, count, budget,
                        (rx, rz) -> gel.feature(state, rx, rz).map(chunk -> sample.apply(chunk.x, chunk.z)).orElseGet(List::of), background));
                continue;
            }
            if (twilight != null) {
                boolean background = async && twilight.backgroundAllowed(generator);
                int count = background ? SearchConfig.threads() : 1;
                BlockPos offset = placement.getLocatePos(new ChunkPos(0, 0));
                // 官方中心算法的区块抖动是 [-3,3]；调用公开方法，不复制随机算法。
                var geometry = geometry(256, offset, -48, 48, targets);
                lanes.addAll(RectLane.create(geometry, radius, 8, count, budget, (rx, rz) -> {
                    BlockPos center = twilight.center(rx, rz);
                    return sample.apply(center.getX() >> 4, center.getZ() >> 4);
                }, background));
                continue;
            }
            if (placement instanceof ConcentricRingsStructurePlacement rings) {
                List<ChunkPos> chunks = new ArrayList<>(state.getRingPositionsFor(rings));
                chunks.sort(Comparator.comparingLong(chunk -> chunkBound(chunk, placement)));
                for (int stripe = 0; stripe < stripes; stripe++) lanes.add(ringLane(chunks, stripe, stripes, placement, budget, sample, supported && !compatiblePrediction && sourceSafe));
            } else {
                int spacing = placement instanceof RandomSpreadStructurePlacement spread ? spread.spacing() : 1;
                BiFunction<Integer, Integer, List<SearchCandidate>> region = (rx, rz) -> {
                    if (placement instanceof RandomSpreadStructurePlacement spread) {
                        ChunkPos chunk = spread.getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
                        return sample.apply(chunk.x, chunk.z);
                    }
                    return sample.apply(rx, rz);
                };
                BlockPos offset = placement.getLocatePos(new ChunkPos(0, 0));
                int jitter = placement instanceof RandomSpreadStructurePlacement spread
                        ? (spread.spacing() - spread.separation() - 1) * 16 : 0;
                var geometry = geometry(spacing * 16, offset, 0, jitter, targets);
                lanes.addAll(RectLane.create(geometry, radius, 8, stripes, budget, region,
                        supported && !compatiblePrediction && sourceSafe));
            }
        }
        return lanes;
    }
    private RectFrontier.Geometry geometry(int step, BlockPos offset, int minJitter, int maxJitter, List<Target> targets) {
        boolean treasure = targets.stream().anyMatch(target -> target.key().toString().equals("minecraft:buried_treasure"));
        long minX = minJitter + (treasure ? Math.min(0, offset.getX()) : offset.getX());
        long minZ = minJitter + (treasure ? Math.min(0, offset.getZ()) : offset.getZ());
        long maxX = maxJitter + (treasure ? Math.max(15, offset.getX()) : offset.getX());
        long maxZ = maxJitter + (treasure ? Math.max(15, offset.getZ()) : offset.getZ());
        return new RectFrontier.Geometry(start.getX(), start.getZ(), step, minX, minZ, maxX, maxZ);
    }
    private long candidateBound(SearchCandidate candidate) {
        if (!candidate.key().toString().equals("minecraft:buried_treasure")) return candidate.distanceSquared();
        long dx = RectFrontier.gap(start.getX(), candidate.chunk().getMinBlockX(), candidate.chunk().getMaxBlockX());
        long dz = RectFrontier.gap(start.getZ(), candidate.chunk().getMinBlockZ(), candidate.chunk().getMaxBlockZ());
        return dx * dx + dz * dz;
    }
    private long chunkBound(ChunkPos chunk, StructurePlacement placement) {
        long exact = distance(start, placement.getLocatePos(chunk));
        if (!keys.contains(new ResourceLocation("minecraft:buried_treasure"))) return exact;
        long dx = RectFrontier.gap(start.getX(), chunk.getMinBlockX(), chunk.getMaxBlockX());
        long dz = RectFrontier.gap(start.getZ(), chunk.getMinBlockZ(), chunk.getMaxBlockZ());
        return Math.min(exact, dx * dx + dz * dz);
    }
    private ScanCoordinator.Lane<SearchCandidate> ringLane(List<ChunkPos> chunks, int stripe, int stripes,
            StructurePlacement placement, SamplingBudget budget, BiFunction<Integer, Integer, List<SearchCandidate>> sample, boolean background) {
        return new ScanCoordinator.Lane<>() {
            private int index = stripe;
            private volatile long covered = stripe < chunks.size()
                    ? chunkBound(chunks.get(stripe), placement) : Long.MAX_VALUE;
            public boolean available(long limit) { return budget.available() && index < chunks.size()
                    && chunkBound(chunks.get(index), placement) <= limit; }
            public List<SearchCandidate> sample() {
                if (!budget.take()) return List.of();
                ChunkPos chunk = chunks.get(index); index += stripes;
                covered = index < chunks.size() ? chunkBound(chunks.get(index), placement) : Long.MAX_VALUE;
                return sample.apply(chunk.x, chunk.z);
            }
            public boolean backgroundAllowed() { return background; }
            public long pendingLowerBound() { return covered; }
            public boolean budgetLimited(long maximum) { return !budget.available() && index < chunks.size()
                    && chunkBound(chunks.get(index), placement) <= maximum; }
        };
    }
    private ScanCoordinator.Confirmation confirm(SearchCandidate candidate) {
        var state = verification.verify(candidate);
        if (state == ScanCoordinator.Confirmation.ACCEPT) verified.put(candidate, verification.confirmed());
        return state;
    }
    @Override public SearchCandidate result() {
        var candidate = super.result();
        return candidate == null ? null : verified.getOrDefault(candidate, candidate);
    }
    @Override public void cancel() { verification.cancel(); super.cancel(); }
    @Override public void prime() {
        if (preparations.stream().noneMatch(DungeonsPrediction::preparing)) super.prime();
    }
    @Override public long preparationNanos() { return preparationNanos; }
    @Override public long confirmationNanos() { return verification.mainNanos(); }
    @Override public long waitingNanos() { return verification.waitingNanos(); }
    @Override public boolean step() throws Exception {
        if (scan.cancelled()) return false;
        for (DungeonsPrediction policy : preparations) if (policy.preparing()) {
            long begin = System.nanoTime();
            try { policy.prepareOne(); return true; }
            finally { long used = System.nanoTime() - begin; mainNanos += used; preparationNanos += used; }
        }
        try { return super.step(); }
        finally {
            confirmations = verification.confirmations(); chunkLoads = verification.chunkRequests();
            if (scan.done()) verification.cancel();
        }
    }
    @Override public String stage() {
        return preparations.stream().anyMatch(DungeonsPrediction::preparing) ? "preparing_templates" : super.stage();
    }
    @Override public String mode() {
        String mode = super.mode();
        return mode.equals("async") && (compatiblePrediction || deferredPlacement || preparations.stream().anyMatch(policy -> !policy.allowed())) ? "mixed" : mode;
    }
    public SearchJob synchronousFallback() {
        // 已知网格保留后台候选，只撤掉失败的预测；再次异常不能无限重试。
        boolean gel = keys.stream().map(key -> level.registryAccess().registryOrThrow(Registries.STRUCTURE).getHolder(
                net.minecraft.resources.ResourceKey.create(Registries.STRUCTURE, key)).orElse(null))
                .filter(java.util.Objects::nonNull).anyMatch(holder -> level.getChunkSource().getGeneratorState()
                        .getPlacementsForStructure(holder).stream().anyMatch(placement -> placement.getClass().getName().equals(GelGridPlacement.TYPE)));
        return gel ? new StructureSearch(level, start, keys, excluded, radius, maximumSamples, true, true)
                : new StructureSearch(level, start, keys, excluded, radius, maximumSamples, false);
    }
    public boolean biome() { return false; }
    public boolean asynchronous() { return async && !compatiblePrediction; }
}
