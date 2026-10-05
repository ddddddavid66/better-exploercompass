package com.chaosthedude.explorerscompass.unified.search;

import com.chaosthedude.explorerscompass.unified.mixin.ChunkMapAccessor;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;

/** 仅准备候选附近 3×3 FEATURES 区块；主线程轮询，不 join、不强制随机刻。 */
final class EndDungeonVerification {
    private static final TicketType<UUID> TICKET=TicketType.create("unified_end_feature",Comparator.<UUID>naturalOrder());
    private static final int FEATURES_LEVEL=ChunkLevel.byStatus(ChunkStatus.FEATURES);
    private static final int BIOMES_LEVEL=ChunkLevel.byStatus(ChunkStatus.BIOMES);
    private final ServerLevel level; private final EndDungeonAdapter.Snapshot rules;
    private final UUID ticketId=UUID.randomUUID();
    private final Map<Long,ChunkAccess> chunks=new HashMap<>();
    private SearchCandidate attempt,confirmed;private boolean ticket,expanded,features;
    private int y,checks,requests;private long waiting,waitStart,mainNanos;
    EndDungeonVerification(ServerLevel level,EndDungeonAdapter.Snapshot rules) {this.level=level;this.rules=rules;}
    ScanCoordinator.Confirmation verify(SearchCandidate candidate) {
        if(!level.getServer().isSameThread())throw new IllegalStateException("地物核对只能在主线程执行");
        long begin=System.nanoTime();
        try {
            if(!candidate.equals(attempt)) { release();attempt=candidate;y=level.getMinBuildHeight();checks++;waitStart=System.nanoTime(); }
            if(!ticket) {
                if(!VerificationSlots.acquire(this))return ScanCoordinator.Confirmation.WAIT;
                ticket=true;requests++;
                for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)
                    level.getChunkSource().chunkMap.getDistanceManager().addTicket(TICKET,new ChunkPos(candidate.chunk().x+dx,candidate.chunk().z+dz),BIOMES_LEVEL,ticketId);
            }
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
                var position=new ChunkPos(candidate.chunk().x+dx,candidate.chunk().z+dz);
                if(chunks.containsKey(position.toLong()))continue;
                var holder=((ChunkMapAccessor)level.getChunkSource().chunkMap).unified$getUpdatingChunkIfPresent(position.toLong());
                if(holder==null || holder.getTicketLevel()>(features?FEATURES_LEVEL:BIOMES_LEVEL))return ScanCoordinator.Confirmation.WAIT;
                var ready=holder.getOrScheduleFuture(features?ChunkStatus.FEATURES:ChunkStatus.BIOMES,level.getChunkSource().chunkMap).getNow(null);
                if(ready==null)return ScanCoordinator.Confirmation.WAIT;
                if(ready.left().isEmpty())throw new IllegalStateException("地物区块准备失败 "+position);
                chunks.put(position.toLong(),ready.left().get());
            }
            if(!features) {
                boolean[] eligible={false};
                // 与原装饰入口相同，检查邻近九区块所有高度的实际群系容器。
                for(var chunk:chunks.values())for(var section:chunk.getSections())section.getBiomes().getAll(holder -> {
                    if(holder.unwrapKey().map(key -> rules.biomes().contains(key.location())).orElse(false))eligible[0]=true;
                });
                // 历史索引不能因当前群系规则变更而跳过真实方块核对。
                if(!eligible[0] && !EndDungeonIndex.get(level).contains(candidate.position())) {release();return ScanCoordinator.Confirmation.REJECT;}
                for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
                    var position=new ChunkPos(candidate.chunk().x+dx,candidate.chunk().z+dz);
                    level.getChunkSource().chunkMap.getDistanceManager().removeTicket(TICKET,position,BIOMES_LEVEL,ticketId);
                    level.getChunkSource().chunkMap.getDistanceManager().addTicket(TICKET,position,FEATURES_LEVEL,ticketId);
                }
                features=true;chunks.clear();return ScanCoordinator.Confirmation.WAIT;
            }
            if(waitStart!=0) {waiting+=System.nanoTime()-waitStart;waitStart=0;}
            // 实际高度取自方块证据，不用当前噪声地形高度代替旧区块地表。
            for(int count=0;count<32 && y<level.getMaxBuildHeight();count++,y++) {
                BlockPos anchor=candidate.position().atY(y);
                int evidence=EndDungeonAdapter.evidence(rules,anchor,this::read);
                if(evidence==0)continue;
                expanded=evidence==2;
                confirmed=new SearchCandidate(anchor,candidate.key(),null,null,candidate.chunk(),candidate.distanceSquared());
                EndDungeonIndex.get(level).remember(anchor,expanded);release();return ScanCoordinator.Confirmation.ACCEPT;
            }
            if(y<level.getMaxBuildHeight())return ScanCoordinator.Confirmation.WAIT;
            EndDungeonIndex.get(level).positions().stream().filter(pos -> pos.getX()==candidate.position().getX() && pos.getZ()==candidate.position().getZ())
                    .forEach(EndDungeonIndex.get(level)::forget);
            release();return ScanCoordinator.Confirmation.REJECT;
        } catch(RuntimeException|Error failure) {release();throw failure;}
        finally {mainNanos+=System.nanoTime()-begin;}
    }
    private BlockState read(BlockPos pos) {
        if(level.isOutsideBuildHeight(pos))return Blocks.AIR.defaultBlockState();
        ChunkAccess chunk=chunks.get(new ChunkPos(pos).toLong());
        return chunk==null?Blocks.AIR.defaultBlockState():chunk.getBlockState(pos);
    }
    private void release() {
        if(ticket && attempt!=null)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)
            level.getChunkSource().chunkMap.getDistanceManager().removeTicket(TICKET,new ChunkPos(attempt.chunk().x+dx,attempt.chunk().z+dz),features?FEATURES_LEVEL:BIOMES_LEVEL,ticketId);
        VerificationSlots.release(this);ticket=false;features=false;chunks.clear();attempt=null;
        if(waitStart!=0) {waiting+=System.nanoTime()-waitStart;waitStart=0;}
    }
    void cancel() {release();}
    SearchCandidate confirmed() {return confirmed;}
    boolean expanded() {return expanded;}
    int checks() {return checks;}
    int requests() {return requests;}
    long mainNanos() {return mainNanos;}
    long waitingNanos() {return waiting+(waitStart==0?0:System.nanoTime()-waitStart);}
}
