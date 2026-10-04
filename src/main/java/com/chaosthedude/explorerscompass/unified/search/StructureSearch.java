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
    private static final ResourceLocation TREASURE=new ResourceLocation("minecraft:buried_treasure");
    record Target(ResourceLocation key, Structure structure, Predicate<Holder<Biome>> biomes,
                  DungeonsPrediction dungeons) {}
    record Group(StructurePlacement placement, List<Target> targets) {}
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
    private WorldgenCache context;
    private final List<SearchCandidate> hints = new ArrayList<>();
    private java.util.concurrent.ConcurrentLinkedQueue<SearchCandidate> predictedPrefix;
    private java.util.concurrent.atomic.AtomicInteger prefixCount;
    private WorldgenCache.Prefix prefix;
    private boolean prefixEligible,outerReady;
    private volatile boolean prefixStored;
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
                (long)radius * radius, async).withTieBreaker(Comparator.comparing(SearchCandidate::key,
                        (first,second)->first.equals(second)?0:first.toString().compareTo(second.toString()))
                        .thenComparingInt(candidate -> candidate.position().getX()).thenComparingInt(candidate -> candidate.position().getZ()))
                .withBatch(256, 4_000_000L).withEagerCandidates(false).withConfirmation(this::confirm)
                .withCandidateLowerBound(this::candidateBound)
                .withResolvedCandidate(candidate -> verified.getOrDefault(candidate, candidate));
        if(prefix!=null)scan.withCoveredPrefix(prefix.upper).withDeferredLanes(()-> {
            outerReady=true;return createLanes();
        });
        hints.forEach(scan::withCandidateHint);
    }
    private List<ScanCoordinator.Lane<SearchCandidate>> createLanes() {
        if (radius <= 0 || maximumSamples <= 0) return List.of();
        BlockPos origin = start;
        Set<Long> ignoredChunks = excluded;
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        WorldgenCache cached = WorldgenCache.get(level);
        context=cached;
        List<Group> groups=cached.structurePlan(keys,compatiblePrediction,()-> {
            Map<StructurePlacement,List<Target>> grouped=new LinkedHashMap<>();
            for(ResourceLocation key:keys) registry.getHolder(net.minecraft.resources.ResourceKey.create(Registries.STRUCTURE,key))
                    .ifPresent(holder->state.getPlacementsForStructure(holder).forEach(placement->
                            grouped.computeIfAbsent(placement,ignored->new ArrayList<>()).add(new Target(key,holder.value(),
                                    cached.biomeRule(holder.value()),!compatiblePrediction && placement.getClass().getName().equals(GelGridPlacement.TYPE)
                                            ?cached.dungeons(level,key,holder.value()):null))));
            return grouped.entrySet().stream().map(entry->new Group(entry.getKey(),List.copyOf(entry.getValue()))).toList();
        });
        long seed = level.getSeed();
        boolean sourceSafe = BiomeSourceSafety.prepare(level).allowed();
        prefixEligible=async && !compatiblePrediction && sourceSafe && groups.size()==1
                && cached.prefixRule(groups.get(0).placement(),level);
        if(prefixEligible && !outerReady)prefix=cached.prefix(keys,origin,ignoredChunks,(long)radius*radius,maximumSamples);
        if(prefix!=null && !outerReady) {
            prefixStored=true;
            for(SearchCandidate candidate:prefix.candidates)if(!ignoredChunks.contains(candidate.chunk().toLong()))hints.add(candidate);
            // 其他请求可能新收录了旧区块位置；它不属于静态生成前缀，仍须加入本次真实核对。
            Group group=groups.get(0);
            for(Target target:group.targets()) {
                SearchCandidate known=cached.hint(target.structure());
                if(known==null || known.placement()!=group.placement() || ignoredChunks.contains(known.chunk().toLong()))continue;
                boolean duplicate=false;
                for(SearchCandidate candidate:hints)if(candidate.key().equals(target.key()) && candidate.chunk().equals(known.chunk())) {duplicate=true;break;}
                if(!duplicate)hints.add(new SearchCandidate(known.position(),target.key(),target.structure(),group.placement(),
                        known.chunk(),distance(origin,known.position())));
            }
            var budget=new SamplingBudget(maximumSamples);
            if(!budget.take(prefix.cost))throw new IllegalStateException("Prefix sample cost changed");
            budgets.add(budget);return List.of();
        }
        if(prefixEligible && !prefixStored) {
            predictedPrefix=new java.util.concurrent.ConcurrentLinkedQueue<>();prefixCount=new java.util.concurrent.atomic.AtomicInteger();
        }
        List<ScanCoordinator.Lane<SearchCandidate>> lanes = new ArrayList<>();
        for (Group group : groups) {
            StructurePlacement placement=group.placement();List<Target> targets=group.targets();
            // 放置分组可共享；排除记录和提示必须是每次请求的快照，不能进入共享计划。
            Map<Structure,SearchCandidate> hinted=new java.util.IdentityHashMap<>();
            for (Target target : targets) {
                if(prefix!=null)continue;
                SearchCandidate hint=cached.hint(target.structure());
                if (hint!=null && hint.placement()==placement && !ignoredChunks.contains(hint.chunk().toLong())) {
                    hinted.put(target.structure(),hint);
                    SearchCandidate current=new SearchCandidate(hint.position(),target.key(),target.structure(),placement,
                            hint.chunk(),distance(origin,hint.position()));
                    hints.add(current);capturePrefix(current);
                }
            }
            TwilightPlacement twilight = TwilightPlacement.of(placement);
            GelGridPlacement gel = GelGridPlacement.of(placement, level.registryAccess());
            for (Target target : targets) if (target.dungeons() != null && !preparations.contains(target.dungeons()))
                preparations.add(target.dungeons());
            if (gel != null && gel.exclusion) deferredPlacement = true;
            Set<Long> visited = gel==null?null:java.util.concurrent.ConcurrentHashMap.newKeySet();
            var generator = level.getChunkSource().getGenerator();
            boolean supported = placement.getClass() == RandomSpreadStructurePlacement.class
                    || placement.getClass() == ConcentricRingsStructurePlacement.class;
            boolean predict = async && !compatiblePrediction && supported && sourceSafe;
            SamplingBudget budget=outerReady?budgets.get(0):new SamplingBudget(maximumSamples);
            if(!outerReady)budgets.add(budget);
            // 闭包只引用不可变快照；未知规则的闭包始终在主线程采样。
            BiFunction<Integer, Integer, List<SearchCandidate>> sample = (x, z) -> {
                ChunkPos chunk = new ChunkPos(x, z);
                if(ignoredChunks.contains(chunk.toLong()))return List.of();
                // Gel 排除规则可递归调用其他模组，统一留给主线程。候选重新核对避免 offset==spacing 的边界误报。
                if (!(gel != null && gel.exclusion) && !(twilight == null
                        ? placement.isStructureChunk(state, x, z) : twilight.contains(generator, state, x, z))) return List.of();
                if (gel != null && !visited.add(chunk.toLong())) return List.of();
                BlockPos locate = placement.getLocatePos(chunk);
                // 最终锚点可能与 locate 不同，由候选下界和真实核对统一判断半径。
                List<SearchCandidate> found = new ArrayList<>();
                for (Target target : targets) {
                    SearchCandidate hint=hinted.get(target.structure());
                    if(hint!=null && hint.chunk().equals(chunk))continue;
                    DungeonsPrediction policy = target.dungeons();
                    BlockPos point = predict ? cached.predict(target.structure(), target.biomes(), chunk)
                            : gel != null && gel.backgroundAllowed && policy != null && policy.allowed()
                                ? policy.predict(() -> cached.predict(target.structure(), target.biomes(), chunk)) : locate;
                    if(point!=null) {
                        SearchCandidate candidate=new SearchCandidate(new BlockPos(locate.getX(), point.getY(), locate.getZ()),
                                target.key(), target.structure(), placement, chunk, distance(origin,locate));
                        found.add(candidate);capturePrefix(candidate);
                    }
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
    private void capturePrefix(SearchCandidate candidate) {
        if(prefixEligible && !prefixStored && prefixCount.incrementAndGet()<=1024)predictedPrefix.add(candidate);
    }
    private void storePrefix() {
        if(!prefixEligible || prefixStored || !scan.done() || scan.result()==null || prefixCount.get()>1024)return;
        long before=System.nanoTime();
        try {
            // 完整最近性证明已覆盖此距离以内所有生成规则候选，不保存区块是否存在的结论。
            long upper=scan.result().distanceSquared();var candidates=new ArrayList<SearchCandidate>();
            for(SearchCandidate candidate:predictedPrefix)if(candidateBound(candidate)<=upper)candidates.add(candidate);
            context.rememberPrefix(keys,new WorldgenCache.Prefix(start.getX(),start.getZ(),upper,samples(),excluded,List.copyOf(candidates)));
            prefixStored=true;
        }finally{mainNanos+=System.nanoTime()-before;}
    }
    private RectFrontier.Geometry geometry(int step, BlockPos offset, int minJitter, int maxJitter, List<Target> targets) {
        boolean treasure = targets.stream().anyMatch(target -> TREASURE.equals(target.key()));
        long minX = minJitter + (treasure ? Math.min(0, offset.getX()) : offset.getX());
        long minZ = minJitter + (treasure ? Math.min(0, offset.getZ()) : offset.getZ());
        long maxX = maxJitter + (treasure ? Math.max(15, offset.getX()) : offset.getX());
        long maxZ = maxJitter + (treasure ? Math.max(15, offset.getZ()) : offset.getZ());
        return new RectFrontier.Geometry(start.getX(), start.getZ(), step, minX, minZ, maxX, maxZ);
    }
    private long candidateBound(SearchCandidate candidate) {
        if (!TREASURE.equals(candidate.key())) return candidate.distanceSquared();
        long dx = RectFrontier.gap(start.getX(), candidate.chunk().getMinBlockX(), candidate.chunk().getMaxBlockX());
        long dz = RectFrontier.gap(start.getZ(), candidate.chunk().getMinBlockZ(), candidate.chunk().getMaxBlockZ());
        return dx * dx + dz * dz;
    }
    private long chunkBound(ChunkPos chunk, StructurePlacement placement) {
        long exact = distance(start, placement.getLocatePos(chunk));
        if (!keys.contains(TREASURE)) return exact;
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
        if (state == ScanCoordinator.Confirmation.ACCEPT) {
            SearchCandidate actual=verification.confirmed();
            verified.put(candidate,actual);context.rememberHint(actual);
        }
        return state;
    }
    @Override public SearchCandidate result() {
        var candidate = super.result();
        return candidate == null ? null : verified.getOrDefault(candidate, candidate);
    }
    @Override public void cancel() { verification.cancel(); super.cancel(); }
    @Override public void prime() {
        // 有提示时先在正常 tick 中核对，再按真实上界启动，避免首批计算无关远处位置。
        if (hints.isEmpty() && preparations.stream().noneMatch(DungeonsPrediction::preparing)) super.prime();
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
            storePrefix();
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
