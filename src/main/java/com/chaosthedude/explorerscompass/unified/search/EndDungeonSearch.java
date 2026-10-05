package com.chaosthedude.explorerscompass.unified.search;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** 地物搜索与结构共用分片、预算、最近距离确认和任务生命周期。 */
public final class EndDungeonSearch extends SearchJob {
    private final Set<Long> excluded;private final int maximum;private final boolean async;
    private final EndDungeonAdapter.Snapshot rules;
    private final EndDungeonVerification verification;
    private final Map<SearchCandidate,SearchCandidate> verified=new HashMap<>();
    private final Map<SearchCandidate,Boolean> expanded=new HashMap<>();
    public EndDungeonSearch(ServerLevel level,BlockPos start,Set<Long> excluded,int radius,int maximum,boolean async) {
        super(level,start,radius);this.excluded=Set.copyOf(excluded);this.maximum=maximum;this.async=async;
        rules=EndDungeonAdapter.snapshot(level);verification=new EndDungeonVerification(level,rules);
        quotaDisabled=maximum<=0;
        scan=new ScanCoordinator<>(lanes(),SearchCandidate::distanceSquared,ignored -> false,(long)radius*radius,async)
                .withTieBreaker(Comparator.comparingInt((SearchCandidate candidate)->candidate.position().getX())
                        .thenComparingInt(candidate->candidate.position().getZ()))
                .withBatch(256,4_000_000L).withConfirmation(candidate -> {
                    var outcome=verification.verify(candidate);
                    if(outcome==ScanCoordinator.Confirmation.ACCEPT) {
                        SearchCandidate actual=verification.confirmed();
                        if(this.excluded.contains(actual.position().asLong()))return ScanCoordinator.Confirmation.REJECT;
                        verified.put(candidate,actual);expanded.put(actual,verification.expanded());
                    }
                    return outcome;
                }).withResolvedCandidate(candidate -> verified.getOrDefault(candidate, candidate));
    }
    private List<ScanCoordinator.Lane<SearchCandidate>> lanes() {
        List<ScanCoordinator.Lane<SearchCandidate>> lanes=new ArrayList<>();
        // 索引候选和预测候选共同参与距离排序，不能直接返回索引的最近项。
        var known=EndDungeonIndex.get(level).positions().stream().filter(pos -> !excluded.contains(pos.asLong()) && distance(start,pos)<=(long)radius*radius)
                .map(pos -> new SearchCandidate(pos,EndDungeonAdapter.KEY,null,null,new ChunkPos(pos),distance(start,pos))).toList();
        if(!known.isEmpty())lanes.add(new ScanCoordinator.Lane<>() {
            private boolean pending=true;
            public boolean available(long limit) {return pending;}
            public List<SearchCandidate> sample() {pending=false;return known;}
            public long pendingLowerBound() {return pending?0:Long.MAX_VALUE;}
            public boolean backgroundAllowed() {return false;}
        });
        if(!rules.predictive() || maximum<=0 || radius<=0)return lanes;
        SamplingBudget budget=new SamplingBudget(maximum);budgets.add(budget);
        long seed=level.getSeed();
        boolean background = async && BiomeSourceSafety.prepare(level).allowed();
        int workers=background?SearchConfig.threads():1;
        Set<Long> visited=java.util.concurrent.ConcurrentHashMap.newKeySet();
        // 模板偏移为 [-1,1]，源区块随机坐标为 [0,15]，真实锚点保持 X/Z。
        var geometry = new RectFrontier.Geometry(start.getX(), start.getZ(), 16, -1, -1, 16, 16);
        lanes.addAll(RectLane.create(geometry, radius, 32, workers, budget, (x,z) -> {
            ChunkPos chunk=new ChunkPos(x,z); List<SearchCandidate> found=new ArrayList<>();
            for(var slot:rules.slots()) {
                var hit=EndDungeonRandom.predict(seed,x,z,slot.step(),slot.index());
                if(hit==null)continue;
                BlockPos position=new BlockPos(hit.x(),0,hit.z());long squared=distance(start,position);
                if(squared>(long)radius*radius || !visited.add(position.asLong()))continue;
                found.add(new SearchCandidate(position,EndDungeonAdapter.KEY,null,null,chunk,squared));
            }
            return found;
        },background));
        return lanes;
    }
    @Override public boolean step() throws Exception {
        try {return super.step();}
        finally {confirmations=verification.checks();chunkLoads=verification.requests();if(done())verification.cancel();}
    }
    @Override public SearchCandidate result() {var result=super.result();return result==null?null:verified.getOrDefault(result,result);}
    public boolean expanded() {return expanded.getOrDefault(super.result(),false);}
    @Override public void cancel() {verification.cancel();super.cancel();}
    @Override public long confirmationNanos() {return verification.mainNanos();}
    @Override public long waitingNanos() {return verification.waitingNanos();}
    @Override public String mode() {return !rules.predictive()?"fallback":super.mode();}
    @Override public String stage() {return !rules.predictive() && result()==null?"feature_index_only":super.stage();}
    public SearchJob synchronousFallback() {return new EndDungeonSearch(level,start,excluded,radius,maximum,false);}
    public boolean biome() {return false;}
    public boolean asynchronous() {return async;}
}
