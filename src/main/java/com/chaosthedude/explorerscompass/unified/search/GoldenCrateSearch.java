package com.chaosthedude.explorerscompass.unified.search;

import com.chaosthedude.explorerscompass.unified.mixin.ChunkMapAccessor;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;

/** 装饰随机数候选与已发现索引共用矩形最近搜索；只返回真实存在的箱子。 */
public final class GoldenCrateSearch extends SearchJob {
    private static final TicketType<UUID> TICKET=TicketType.create("unified_golden_crate",Comparator.<UUID>naturalOrder());
    private static final int TICKET_LEVEL=ChunkLevel.byStatus(ChunkStatus.FEATURES);
    private final UUID ticketId=UUID.randomUUID();
    private final Set<Long> excluded;
    private final long maximum;
    private final boolean async;
    private final GoldenCrateAdapter.Snapshot rules;
    private SearchCandidate attempt;
    private boolean ticket;
    private long waiting,waitStart,verificationNanos;
    public GoldenCrateSearch(ServerLevel level,BlockPos start,Set<Long> excluded,int radius,long maximum,boolean async) {
        super(level,start,radius);this.excluded=Set.copyOf(excluded);this.maximum=maximum;this.async=async;
        rules=GoldenCrateAdapter.snapshot(level);quotaDisabled=maximum<=0;
        scan=new ScanCoordinator<>(lanes(),SearchCandidate::distanceSquared,ignored -> false,(long)radius*radius,async)
                .withTieBreaker(Comparator.comparingInt((SearchCandidate value)->value.position().getX())
                        .thenComparingInt(value->value.position().getZ()).thenComparingInt(value->value.position().getY()))
                .withBatch(256,4_000_000L).withConfirmation(this::verify);
    }
    private List<ScanCoordinator.Lane<SearchCandidate>> lanes() {
        List<ScanCoordinator.Lane<SearchCandidate>> lanes=new ArrayList<>();
        var known=GoldenCrateIndex.get(level).positions().stream().filter(pos -> !excluded.contains(pos.asLong())
                && distance(start,pos)<=(long)radius*radius).map(pos -> candidate(pos,new ChunkPos(pos))).toList();
        if (!known.isEmpty()) lanes.add(new ScanCoordinator.Lane<>() {
            boolean pending=true;
            public boolean available(long upper) { return pending; }
            public long pendingLowerBound() { return pending?0:Long.MAX_VALUE; }
            public List<SearchCandidate> sample() { pending=false;return known; }
            public boolean backgroundAllowed() { return false; }
        });
        if (!rules.predictive() || maximum<=0 || radius<=0) return lanes;
        SamplingBudget budget=new SamplingBudget(maximum);budgets.add(budget);
        long seed=level.getSeed();Set<Long> visited=java.util.concurrent.ConcurrentHashMap.newKeySet();
        // 源区块内坐标 [0,15] 加箱子偏移 6，包围矩形必须包含跨区块锚点。
        var geometry=new RectFrontier.Geometry(start.getX(),start.getZ(),16,6,6,21,21);
        lanes.addAll(RectLane.create(geometry,radius,8,async?SearchConfig.threads():1,budget,(x,z) -> {
            List<SearchCandidate> found=new ArrayList<>();
            for (var slot:rules.slots()) {
                BlockPos pos=GoldenCrateAdapter.predict(seed,x,z,slot,rules);
                if (pos!=null && !excluded.contains(pos.asLong()) && distance(start,pos)<=(long)radius*radius
                        && visited.add(pos.asLong())) found.add(candidate(pos,new ChunkPos(x,z)));
            }
            return found;
        },async));
        return lanes;
    }
    private SearchCandidate candidate(BlockPos pos,ChunkPos source) {
        return new SearchCandidate(pos,GoldenCrateAdapter.KEY,null,null,source,distance(start,pos));
    }
    private ScanCoordinator.Confirmation verify(SearchCandidate value) {
        long begin=System.nanoTime();
        try {
            if (!value.equals(attempt)) {release();attempt=value;confirmations++;}
            if (!ticket) {
                if (!VerificationSlots.acquire(this)) return ScanCoordinator.Confirmation.WAIT;
                ticket=true;chunkLoads++;waitStart=System.nanoTime();
                for (int dx=-1;dx<=1;dx++) for (int dz=-1;dz<=1;dz++)
                    level.getChunkSource().chunkMap.getDistanceManager().addTicket(TICKET,
                            new ChunkPos(value.chunk().x+dx,value.chunk().z+dz),TICKET_LEVEL,ticketId);
            }
            // 等待源区块完成真实装饰；箱子可跨到邻区块，九区块全部就绪再读取。
            ChunkAccess target=null;
            for (int dx=-1;dx<=1;dx++) for (int dz=-1;dz<=1;dz++) {
                var pos=new ChunkPos(value.chunk().x+dx,value.chunk().z+dz);
                var holder=((ChunkMapAccessor)level.getChunkSource().chunkMap).unified$getUpdatingChunkIfPresent(pos.toLong());
                if (holder==null || holder.getTicketLevel()>TICKET_LEVEL) return waiting();
                var ready=holder.getOrScheduleFuture(ChunkStatus.FEATURES,level.getChunkSource().chunkMap).getNow(null);
                if (ready==null) return waiting();
                if (ready.left().isEmpty()) throw new IllegalStateException("城堡区块准备失败 "+pos);
                if (pos.equals(new ChunkPos(value.position()))) target=ready.left().get();
            }
            boolean exists=target!=null && !level.isOutsideBuildHeight(value.position())
                    && target.getBlockState(value.position()).is(GoldenCrateAdapter.block());
            if (exists) GoldenCrateIndex.get(level).remember(value.position());
            else GoldenCrateIndex.get(level).forget(value.position());
            release();return exists?ScanCoordinator.Confirmation.ACCEPT:ScanCoordinator.Confirmation.REJECT;
        } catch (RuntimeException|Error error) {release();throw error;}
        finally {verificationNanos+=System.nanoTime()-begin;}
    }
    private ScanCoordinator.Confirmation waiting() {
        if (waitStart!=0 && System.nanoTime()-waitStart>60_000_000_000L)
            throw new IllegalStateException("城堡候选区块等待超过60秒");
        return ScanCoordinator.Confirmation.WAIT;
    }
    private void release() {
        if (ticket && attempt!=null) for (int dx=-1;dx<=1;dx++) for (int dz=-1;dz<=1;dz++)
            level.getChunkSource().chunkMap.getDistanceManager().removeTicket(TICKET,
                    new ChunkPos(attempt.chunk().x+dx,attempt.chunk().z+dz),TICKET_LEVEL,ticketId);
        VerificationSlots.release(this);ticket=false;attempt=null;
        if (waitStart!=0) {waiting+=System.nanoTime()-waitStart;waitStart=0;}
    }
    @Override public boolean step() throws Exception {try {return super.step();} finally {if (done()) release();}}
    @Override public void cancel() {release();super.cancel();}
    @Override public long confirmationNanos() {return verificationNanos;}
    @Override public long waitingNanos() {return waiting+(waitStart==0?0:System.nanoTime()-waitStart);}
    @Override public String mode() {return rules.predictive()?super.mode():"fallback";}
    @Override public String stage() {return !rules.predictive() && result()==null?"feature_index_only":super.stage();}
    @Override public String diagnostics() {return "golden_crate="+(rules.predictive()?"decoration_prefix":rules.reason());}
    @Override public SearchJob synchronousFallback() {return new GoldenCrateSearch(level,start,excluded,radius,maximum,false);}
    @Override public boolean biome() {return false;}
    @Override public boolean asynchronous() {return async;}
}
