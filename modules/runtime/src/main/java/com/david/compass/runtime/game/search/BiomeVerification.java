package com.david.compass.runtime.game.search;

import com.david.compass.runtime.game.mixin.ChunkMapAccessor;
import com.mojang.datafixers.util.Either;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.*;

/** 主线程完整来源校验后，再核对 BIOMES 阶段的真实区块；共享四个核对名额。 */
public final class BiomeVerification {
    private static final TicketType<UUID> TYPE=TicketType.create("unified_biome_check",Comparator.<UUID>naturalOrder());
    private static final int LEVEL=ChunkLevel.byStatus(ChunkStatus.BIOMES);
    private final ServerLevel level;
    private final UUID id=UUID.randomUUID();
    private SearchCandidate pending;
    private boolean ticket;
    private CompletableFuture<Either<ChunkAccess,ChunkHolder.ChunkLoadingFailure>> future;
    int checks,loads;
    long waiting,started;
    BiomeVerification(ServerLevel level){this.level=level;}
    ScanCoordinator.Confirmation verify(SearchCandidate candidate) {
        if(!level.getServer().isSameThread())throw new IllegalStateException("群系核对必须在主线程");
        try {
            var point=candidate.position();int x=QuartPos.fromBlock(point.getX()),y=QuartPos.fromBlock(point.getY()),z=QuartPos.fromBlock(point.getZ());
            if(!candidate.equals(pending)) {
                release();checks++;
                if(!matches(level.getChunkSource().getGenerator().getBiomeSource().getNoiseBiome(x,y,z,level.getChunkSource().randomState().sampler()),candidate))
                    return ScanCoordinator.Confirmation.REJECT;
                pending=candidate;started=System.nanoTime();
            }
            var chunk=new ChunkPos(point);
            if(!ticket) {
                if(!VerificationSlots.acquire(this))return ScanCoordinator.Confirmation.WAIT;
                level.getChunkSource().chunkMap.getDistanceManager().addTicket(TYPE,chunk,LEVEL,id);ticket=true;loads++;
            }
            if(future==null) {
                var holder=((ChunkMapAccessor)level.getChunkSource().chunkMap).unified$getUpdatingChunkIfPresent(chunk.toLong());
                if(holder==null || holder.getTicketLevel()>LEVEL)return ScanCoordinator.Confirmation.WAIT;
                future=holder.getOrScheduleFuture(ChunkStatus.BIOMES,level.getChunkSource().chunkMap);
            }
            var ready=future.getNow(null);if(ready==null)return ScanCoordinator.Confirmation.WAIT;
            if(ready.left().isEmpty())throw new IllegalStateException("群系区块准备失败: "+chunk);
            boolean valid=matches(ready.left().get().getNoiseBiome(x,y,z),candidate);release();
            return valid?ScanCoordinator.Confirmation.ACCEPT:ScanCoordinator.Confirmation.REJECT;
        }catch(RuntimeException|Error failure){release();throw failure;}
    }
    private static boolean matches(net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome,SearchCandidate candidate) {
        return biome.unwrapKey().map(key->key.location().equals(candidate.key())).orElse(false);
    }
    void release() {
        if(ticket)level.getChunkSource().chunkMap.getDistanceManager().removeTicket(TYPE,new ChunkPos(pending.position()),LEVEL,id);
        if(started!=0){waiting+=System.nanoTime()-started;started=0;}
        ticket=false;pending=null;future=null;VerificationSlots.release(this);
    }
    long waitingNanos(){return waiting+(started==0?0:System.nanoTime()-started);}
}
