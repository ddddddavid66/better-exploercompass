package com.chaosthedude.explorerscompass.unified.search;

import com.chaosthedude.explorerscompass.unified.mixin.ChunkMapAccessor;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;

/** 速度优先流水线：噪声评分不删候选，首个通过真实方块核对的箱子立即返回。 */
public final class GoldenCrateSearch extends SearchJob {
    private static final int CAPACITY=128;
    private static final TicketType<UUID> TICKET=TicketType.create("unified_golden_crate",Comparator.<UUID>naturalOrder());
    private static final int TICKET_LEVEL=ChunkLevel.byStatus(ChunkStatus.FEATURES);
    private record Hint(SearchCandidate candidate,int score,boolean indexed) {}
    private final UUID ticketId=UUID.randomUUID();
    private final Set<Long> excluded,seen=new HashSet<>();
    private final long maximum,levelSeed;
    private final boolean async;
    private final GoldenCrateAdapter.Snapshot rules;
    private final GoldenCrateTerrain terrain;
    private final SamplingBudget budget;
    private final Iterator<BlockPos> indexed;
    private final Semaphore capacity=new Semaphore(CAPACITY);
    private final ArrayDeque<Hint> inbox=new ArrayDeque<>();
    private final CrateCandidateOrder<Hint> ready=new CrateCandidateOrder<>(
            Comparator.comparingLong((Hint h)->h.candidate().distanceSquared())
                    .thenComparingInt(h->h.candidate().position().getX())
                    .thenComparingInt(h->h.candidate().position().getZ())
                    .thenComparingInt(h->h.candidate().position().getY()),Hint::indexed,Hint::score);
    private final List<Lane> lanes=new ArrayList<>();
    private final Work[] checks={new Work(),new Work()};
    private final AtomicBoolean stopped=new AtomicBoolean(),limited=new AtomicBoolean();
    private final AtomicLong workerNanos=new AtomicLong(),prefixNanos=new AtomicLong(),batches=new AtomicLong(),candidates=new AtomicLong();
    private final CrateChunkRefs tickets;
    private volatile Throwable failure;
    private SearchCandidate result;
    private boolean finished,fallback;
    private long verificationNanos,waitNanos;
    private int highWater;
    public GoldenCrateSearch(ServerLevel level,BlockPos start,Set<Long> excluded,int radius,long maximum,boolean async) {
        super(level,start,radius);this.excluded=Set.copyOf(excluded);this.maximum=maximum;this.async=async;this.levelSeed=level.getSeed();
        rules=GoldenCrateAdapter.snapshot(level);terrain=rules.predictive()?new GoldenCrateTerrain(level):null;
        quotaDisabled=maximum<=0;budget=new SamplingBudget(Math.max(0,maximum));budgets.add(budget);
        indexed=GoldenCrateIndex.get(level).positions().stream().filter(pos->!excluded.contains(pos.asLong())
                && distance(start,pos)<=(long)radius*radius).iterator();
        tickets=new CrateChunkRefs(chunk->{chunkLoads++;level.getChunkSource().chunkMap.getDistanceManager()
                .addTicket(TICKET,new ChunkPos(chunk),TICKET_LEVEL,ticketId);},
                chunk->level.getChunkSource().chunkMap.getDistanceManager().removeTicket(TICKET,new ChunkPos(chunk),TICKET_LEVEL,ticketId));
        if(rules.predictive() && maximum>0 && radius>0) {
            var geometry=new RectFrontier.Geometry(start.getX(),start.getZ(),16,6,6,21,21);
            for(var region:RectFrontier.partition(geometry.domain(radius),async?SearchConfig.threads():1))
                lanes.add(new Lane(new RectFrontier(geometry,region,8,(long)radius*radius)));
        }
    }
    private final class Lane {
        final RectFrontier frontier;final AtomicBoolean running=new AtomicBoolean();
        volatile boolean exhausted;boolean point;int x,z,slot;
        Lane(RectFrontier frontier){this.frontier=frontier;}
        void batch(long slice) {
            long begin=System.nanoTime();
            try {
                for(int count=0;count<256 && !stopped.get() && !Thread.currentThread().isInterrupted();count++) {
                    if(!capacity.tryAcquire())return;
                    boolean published=false;
                    try {
                        if(!point) {
                            if(!frontier.available((long)radius*radius)){exhausted=true;return;}
                            if(!budget.take()){limited.set(true);exhausted=true;return;}
                            long packed=frontier.next();x=(int)(packed>>32);z=(int)packed;slot=0;point=true;
                        }
                        long prefix=System.nanoTime();
                        BlockPos pos=GoldenCrateAdapter.predict(levelSeed,x,z,rules.slots().get(slot++),rules);
                        prefixNanos.addAndGet(System.nanoTime()-prefix);
                        if(slot>=rules.slots().size())point=false;
                        if(pos!=null && !excluded.contains(pos.asLong()) && distance(start,pos)<=(long)radius*radius) {
                            candidates.incrementAndGet();
                            int score=terrain==null?0:terrain.score(pos);
                            synchronized(inbox) {
                                if(!stopped.get()) {inbox.addLast(new Hint(candidate(pos,new ChunkPos(x,z)),score,false));published=true;}
                            }
                        }
                    } finally {if(!published)capacity.release();}
                    if(System.nanoTime()-begin>=slice)return;
                }
            } catch(Throwable error) {failure=error;exhausted=true;}
            finally {workerNanos.addAndGet(System.nanoTime()-begin);if(stopped.get() && terrain!=null)terrain.clear();}
        }
    }
    // 种子在请求线程冻结，后台闭包不访问ServerLevel。
    private SearchCandidate candidate(BlockPos pos,ChunkPos source) {
        return new SearchCandidate(pos,GoldenCrateAdapter.KEY,null,null,source,distance(start,pos));
    }
    private boolean collect() {
        boolean progress=false;
        for(int n=0;n<32 && indexed.hasNext() && capacity.tryAcquire();n++) {
            BlockPos pos=indexed.next();accept(new Hint(candidate(pos,new ChunkPos(pos)),6,true));progress=true;
        }
        synchronized(inbox) {
            for(int n=0;n<32 && !inbox.isEmpty();n++) {accept(inbox.removeFirst());progress=true;}
        }
        highWater=Math.max(highWater,CAPACITY-capacity.availablePermits());return progress;
    }
    private void accept(Hint hint) {
        if(seen.add(hint.candidate().position().asLong()))ready.add(hint);
        else capacity.release();
    }
    private void dispatch() {
        if(!async || stopped.get())return;
        for(Lane lane:lanes)if(!lane.exhausted && capacity.availablePermits()>0 && lane.running.compareAndSet(false,true)) {
            try {
                SearchPool.executor().execute(()->{try{lane.batch(4_000_000L);}finally{lane.running.set(false);}});
                batches.incrementAndGet();
            }catch(RejectedExecutionException full){lane.running.set(false);}
        }
    }
    @Override public void prime(){collect();dispatch();}
    @Override public boolean step() throws Exception {
        if(finished || stopped.get())return false;
        long begin=System.nanoTime();
        try {
            if(failure!=null)throw new IllegalStateException("城堡候选任务异常",failure);
            boolean progress=collect();dispatch();
            if(!async)for(Lane lane:lanes)if(!lane.exhausted){lane.batch(250_000L);progress|=collect();break;}
            // 有高分/已发现候选即启动；否则最多等待250ms收集首批评分，不空等填满窗口。
            boolean initialReady=ready.preferred() || capacity.availablePermits()==0 || producersDone()
                    || System.nanoTime()-startedNanos>=250_000_000L;
            for(Work check:checks) {
                if(check.hint==null && initialReady && ready.size()>0) {check.assign(ready.poll());progress=true;}
                if(check.hint==null)continue;
                var state=check.poll();
                if(state==ScanCoordinator.Confirmation.WAIT)continue;
                progress=true;
                if(state==ScanCoordinator.Confirmation.ACCEPT) {result=check.hint.candidate();finished=true;terminate();return true;}
                // 同一核对槽原地换候选；引用计数先增加新票据，再移除不再使用的旧票据。
                if(ready.size()>0)check.assign(ready.poll());else check.close();
            }
            if(producersDone() && !indexed.hasNext() && ready.size()==0 && inboxEmpty()
                    && Arrays.stream(checks).noneMatch(check->check.hint!=null)) {finished=true;terminate();return true;}
            return progress;
        } finally {mainNanos+=System.nanoTime()-begin;}
    }
    private boolean producersDone(){return lanes.stream().allMatch(lane->lane.exhausted && !lane.running.get());}
    private boolean inboxEmpty(){synchronized(inbox){return inbox.isEmpty();}}
    private final class Work {
        Hint hint;boolean held;long started;
        Set<Long> required=Set.of();
        void assign(Hint next) {
            if(hint!=null){seen.remove(hint.candidate().position().asLong());capacity.release();}
            hint=next;started=0;required=new LinkedHashSet<>(List.of(next.candidate().chunk().toLong(),new ChunkPos(next.candidate().position()).toLong()));
            if(held){tickets.replace(this,required);started=System.nanoTime();confirmations++;}
        }
        ScanCoordinator.Confirmation poll() {
            long begin=System.nanoTime();
            try {
                if(!held) {
                    if(!VerificationSlots.acquire(this))return ScanCoordinator.Confirmation.WAIT;
                    held=true;started=System.nanoTime();tickets.replace(this,required);confirmations++;
                }
                ChunkAccess target=null;
                for(long chunk:required) {
                    var holder=((ChunkMapAccessor)level.getChunkSource().chunkMap).unified$getUpdatingChunkIfPresent(chunk);
                    if(holder==null || holder.getTicketLevel()>TICKET_LEVEL)return waiting();
                    var future=holder.getOrScheduleFuture(ChunkStatus.FEATURES,level.getChunkSource().chunkMap).getNow(null);
                    if(future==null)return waiting();
                    if(future.left().isEmpty())throw new IllegalStateException("城堡区块准备失败 "+new ChunkPos(chunk));
                    if(chunk==new ChunkPos(hint.candidate().position()).toLong())target=future.left().get();
                }
                boolean exists=target!=null && !level.isOutsideBuildHeight(hint.candidate().position())
                        && target.getBlockState(hint.candidate().position()).is(GoldenCrateAdapter.block());
                if(exists)GoldenCrateIndex.get(level).remember(hint.candidate().position());
                else GoldenCrateIndex.get(level).forget(hint.candidate().position());
                if(started!=0){waitNanos+=System.nanoTime()-started;started=0;}
                return exists?ScanCoordinator.Confirmation.ACCEPT:ScanCoordinator.Confirmation.REJECT;
            }finally{verificationNanos+=System.nanoTime()-begin;}
        }
        private ScanCoordinator.Confirmation waiting() {
            if(started!=0 && System.nanoTime()-started>60_000_000_000L)throw new IllegalStateException("城堡候选区块等待超过60秒");
            return ScanCoordinator.Confirmation.WAIT;
        }
        void close() {
            tickets.remove(this);VerificationSlots.release(this);held=false;
            if(started!=0){waitNanos+=System.nanoTime()-started;started=0;}
            if(hint!=null){seen.remove(hint.candidate().position().asLong());capacity.release();hint=null;}
        }
    }
    private void terminate() {
        if(!stopped.compareAndSet(false,true))return;
        for(Work check:checks)check.close();tickets.clear();
        while(ready.size()>0){ready.poll();capacity.release();}
        synchronized(inbox){while(!inbox.isEmpty()){inbox.removeFirst();capacity.release();}}
        if(terrain!=null)terrain.clear();
    }
    @Override public void cancel(){finished=true;terminate();}
    @Override public boolean done(){return finished;}
    @Override public SearchCandidate result(){return result;}
    @Override public boolean budgetLimited(){return quotaDisabled || limited.get();}
    @Override public String mode(){return !rules.predictive() || fallback?"fallback":async?"async":"sync";}
    @Override public SearchJob asFallback(){fallback=true;return this;}
    @Override public String stage(){
        if(finished)return result!=null?"found":!rules.predictive()?"feature_index_only":budgetLimited()?"sample_limit":"range_exhausted";
        for(Work check:checks)if(check.hint!=null)return "crate_waiting";
        return "crate_prioritizing";
    }
    @Override public long confirmationNanos(){return verificationNanos;}
    @Override public long waitingNanos(){long total=waitNanos;for(Work check:checks)if(check.started!=0)total+=System.nanoTime()-check.started;return total;}
    @Override public long workerNanos(){return async?workerNanos.get():0;}
    @Override public long submittedBatches(){return batches.get();}
    @Override public long candidates(){return candidates.get();}
    @Override public int coveredRadius(){return 0;} // 快速模式不把处理最远点冒充已完整覆盖的半径。
    @Override public String diagnostics(){return "golden_crate=fast_verified; prefix_ms="+prefixNanos.get()/1e6
            +"; terrain_ms="+(terrain==null?0:terrain.nanos.get()/1e6)+"; likely="+(terrain==null?0:terrain.likely.get())
            +"; waiting_sum_ms="+waitingNanos()/1e6+"; verification_ms="+verificationNanos/1e6+"; explicit_chunk_requests="+chunkLoads
            +"; pending_peak="+highWater+"; live_tickets="+tickets.size()+"; terrain_fallback="+(terrain==null?rules.reason():terrain.reason());}
    @Override public SearchJob synchronousFallback(){return new GoldenCrateSearch(level,start,excluded,radius,maximum,false);}
    @Override public boolean biome(){return false;}
    @Override public boolean asynchronous(){return async;}
}
