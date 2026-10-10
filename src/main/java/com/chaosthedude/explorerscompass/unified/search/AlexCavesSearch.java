package com.chaosthedude.explorerscompass.unified.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** 保留矩形距离证明；仅逐列跳过不属于目标稀有域的采样，不按单元中心剪掉整片区域。 */
final class AlexCavesSearch extends SearchJob {
    private final ResourceLocation key;
    private final List<BlockPos> excluded;
    private final int maximum,spacing;
    private final boolean async;
    private final BiomeVerification verification;
    AlexCavesSearch(ServerLevel level,BlockPos start,ResourceLocation key,List<BlockPos> excluded,
                    int radius,int maximum,int spacing,boolean async,AlexCavesAdapter adapter) {
        super(level,start,radius);this.key=key;this.excluded=List.copyOf(excluded);this.maximum=maximum;this.spacing=spacing;this.async=async;
        verification=new BiomeVerification(level);
        var budget=new SamplingBudget(maximum);budgets.add(budget);quotaDisabled=maximum<=0;
        var ignored=new com.chaosthedude.explorerscompass.unified.data.ExclusionIndex(excluded.stream()
                .map(point->new com.chaosthedude.explorerscompass.unified.data.ExclusionIndex.Point(point.getX(),point.getZ())).toList());
        List<Integer> heights=new ArrayList<>();
        for(int y=level.getMinBuildHeight();y<level.getMaxBuildHeight();y+=4)heights.add(y);
        heights.sort(Comparator.<Integer>comparingInt(y->Math.abs(y-start.getY())).thenComparingInt(y->y));
        var source=level.getChunkSource().getGenerator().getBiomeSource();var sampler=level.getChunkSource().randomState().sampler();
        var geometry=new RectFrontier.Geometry(start.getX(),start.getZ(),spacing,start.getX(),start.getZ(),start.getX(),start.getZ());
        List<ScanCoordinator.Lane<SearchCandidate>> lanes=new ArrayList<>();
        if(maximum>0)for(var partition:RectFrontier.partition(geometry.domain(radius),async && adapter!=null?SearchConfig.threads():1)) {
            var center=new net.minecraft.world.phys.Vec3[1];
            lanes.add(new BiomeColumnLane(geometry,partition,(long)radius*radius,budget,List.copyOf(heights),1,async && adapter!=null,(dx,dz,y)-> {
                int x=start.getX()+dx*spacing,z=start.getZ()+dz*spacing;
                int qx=QuartPos.fromBlock(x),qy=QuartPos.fromBlock(y),qz=QuartPos.fromBlock(z);
                boolean match=adapter!=null?adapter.matches(qx,qy,qz,center[0]):source.getNoiseBiome(qx,qy,qz,sampler)
                        .unwrapKey().map(value->value.location().equals(key)).orElse(false);
                if(!match)return List.of();
                BlockPos position=new BlockPos(x,y,z);
                return List.of(new SearchCandidate(position,key,null,null,null,distance(start,position)));
            }).withColumnGate((dx,dz)-> {
                int x=start.getX()+dx*spacing,z=start.getZ()+dz*spacing;
                if(ignored.excludes(x,z))return false;
                if(adapter==null)return true;
                center[0]=adapter.center(QuartPos.fromBlock(x),QuartPos.fromBlock(z));return center[0]!=null;
            }));
        }
        scan=new ScanCoordinator<>(lanes,SearchCandidate::distanceSquared,ignoredCandidate->false,(long)radius*radius,async)
                .withBatch(256,4_000_000L).withEagerCandidates(false)
                .withTieBreaker(Comparator.<SearchCandidate>comparingInt(candidate->Math.abs(candidate.position().getY()-start.getY()))
                        .thenComparingInt(candidate->candidate.position().getX()).thenComparingInt(candidate->candidate.position().getZ()))
                .withConfirmation(verification::verify);
    }
    @Override public boolean step() throws Exception {
        boolean done=super.step();confirmations=verification.checks;chunkLoads=verification.loads;return done;
    }
    @Override public void cancel(){super.cancel();verification.release();}
    @Override public long waitingNanos(){return verification.waitingNanos();}
    @Override public boolean biome(){return true;}
    @Override public boolean asynchronous(){return async;}
    @Override public SearchJob synchronousFallback(){return new AlexCavesSearch(level,start,key,excluded,radius,maximum,spacing,false,null);}
}
