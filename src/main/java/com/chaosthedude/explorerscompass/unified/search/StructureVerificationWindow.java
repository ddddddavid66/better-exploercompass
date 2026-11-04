package com.chaosthedude.explorerscompass.unified.search;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** 每个逻辑候选独占句柄，同区块的底层Future和票据由StructureVerification共享。 */
final class StructureVerificationWindow {
    private final ServerLevel level;private final BlockPos origin;
    private final List<Handle> active=new ArrayList<>();
    private int checks,chunks,peak,shared;private long cpu;
    private final Interval waiting=new Interval(),slots=new Interval(),futures=new Interval();
    /** 用墙钟区间并集计时，不把并行窗口的等待相加。 */
    private static final class Interval {
        long since,total;
        void update(boolean running,long now){if(running && since==0)since=now;else if(!running && since!=0){total+=now-since;since=0;}}
        long nanos(){return total+(since==0?0:System.nanoTime()-since);}
    }
    private void updateWait(){
        boolean any=false,slot=false,chunk=false;
        for(Handle h:active){any|=h.worker.waiting();slot|=h.worker.slotWaiting();chunk|=h.worker.chunkWaiting();}
        long now=System.nanoTime();waiting.update(any,now);slots.update(slot,now);futures.update(chunk,now);
    }
    StructureVerificationWindow(ServerLevel level,BlockPos origin){this.level=level;this.origin=origin;}
    ScanCoordinator.Verification<SearchCandidate> begin(SearchCandidate candidate,java.util.function.Consumer<SearchCandidate> accepted) {
        Handle handle=new Handle(candidate,accepted);active.add(handle);peak=Math.max(peak,active.size());return handle;
    }
    private final class Handle implements ScanCoordinator.Verification<SearchCandidate> {
        final StructureVerification worker=new StructureVerification(level,origin);
        final SearchCandidate candidate;final java.util.function.Consumer<SearchCandidate> accepted;boolean closed;
        Handle(SearchCandidate candidate,java.util.function.Consumer<SearchCandidate> accepted){this.candidate=candidate;this.accepted=accepted;}
        public ScanCoordinator.Confirmation poll(boolean primary) {
            var result=worker.verify(candidate,!primary);
            updateWait();
            if(result==ScanCoordinator.Confirmation.ACCEPT)accepted.accept(worker.confirmed());
            return result;
        }
        public SearchCandidate actual(){return worker.confirmed();}
        public void close(){
            if(closed)return;closed=true;worker.cancel();
            shared+=worker.sharedAcquires();checks+=worker.confirmations();chunks+=worker.chunkRequests();cpu+=worker.mainNanos();active.remove(this);
            updateWait();
        }
    }
    void cancel(){for(Handle handle:List.copyOf(active))handle.close();}
    int confirmations(){int total=checks;for(Handle h:active)total+=h.worker.confirmations();return total;}
    int chunkRequests(){int total=chunks;for(Handle h:active)total+=h.worker.chunkRequests();return total;}
    long mainNanos(){long total=cpu;for(Handle h:active)total+=h.worker.mainNanos();return total;}
    long waitingNanos(){return waiting.nanos();}
    String diagnostics(){int reused=shared;for(Handle h:active)reused+=h.worker.sharedAcquires();return "shared_preparations="+reused+", verification_peak="+peak+", slot_wait_ms="+slots.nanos()/1e6+", chunk_wait_ms="+futures.nanos()/1e6;}
    int peak(){return peak;}
}
