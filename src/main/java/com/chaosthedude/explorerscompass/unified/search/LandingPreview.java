package com.chaosthedude.explorerscompass.unified.search;

import com.david.compass.runtime.game.search.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;

/** 探险者专属有界预备：每请求最多两个候选，全局一个推测任务。 */
final class LandingPreview implements AutoCloseable {
    private static LandingPreview owner;
    private final ServerLevel level;
    private final GenerationStamp stamp;
    private ChunkPos chunk;
    private int attempts;
    private long started,readyAt;
    private boolean slot;
    LandingPreview(ServerLevel level){this.level=level;stamp=GenerationStamp.capture(level);}
    void offer(SearchCandidate candidate){
        if(!stamp.current(level) || attempts>=2 || owner!=null && owner!=this)return;
        // 门房可能远离结构源区块，仅有真实门房提示后才能精确准备。
        if(candidate.key().toString().contains("stronghold") && candidate.portal()==null)return;
        ChunkPos next=new ChunkPos(candidate.portal()==null?candidate.position():candidate.portal().room().getCenter());
        if(next.equals(chunk))return;
        if(!slot && !VerificationSlots.acquireBackground(this))return;
        slot=true;owner=this;if(started==0)started=System.nanoTime();
        ChunkPreparationBroker.retain(level,next,ChunkStatus.FULL);
        if(chunk!=null)ChunkPreparationBroker.release(level,chunk,ChunkStatus.FULL);
        chunk=next;attempts++;
    }
    String diagnostics(){
        if(chunk!=null && readyAt==0 && level.getChunkSource().getChunkNow(chunk.x,chunk.z)!=null)readyAt=System.nanoTime();
        return "preview_candidates="+attempts+", preview_ready_ms="+(readyAt==0?-1:(readyAt-started)/1e6);
    }
    void expire(){diagnostics();if(started!=0 && System.nanoTime()-started>=30_000_000_000L){close();attempts=2;}}
    void yieldSlot(){if(slot){VerificationSlots.release(this);slot=false;}}
    public void close(){
        yieldSlot();if(chunk!=null){ChunkPreparationBroker.release(level,chunk,ChunkStatus.FULL);chunk=null;}
        if(owner==this)owner=null;
    }
}
