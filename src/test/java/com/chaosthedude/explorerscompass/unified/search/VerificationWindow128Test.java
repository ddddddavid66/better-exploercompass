package com.chaosthedude.explorerscompass.unified.search;

import com.david.compass.runtime.game.search.ScanCoordinator;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class VerificationWindow128Test {
    static class Handle implements ScanCoordinator.Verification<Integer> {
        boolean ready,closed,reject;int value,closeCalls,polls;boolean primary;
        Handle(int value){this.value=value;}
        public ScanCoordinator.Confirmation poll(boolean primary){this.primary=primary;polls++;return !ready?ScanCoordinator.Confirmation.WAIT:reject?ScanCoordinator.Confirmation.REJECT:ScanCoordinator.Confirmation.ACCEPT;}
        public Integer actual(){return value;}
        public void close(){if(!closed){closed=true;closeCalls++;}}
    }
    @Test void farReadyCannotBypassNearWaitingAndHandlesStayStable()throws Exception {
        var pool=Executors.newSingleThreadExecutor();var handles=new HashMap<Integer,Handle>();
        try {
            var scan=new ScanCoordinator<Integer>(List.of(),Integer::longValue,p->true,100,false)
                .withVerificationWindow(p->{var h=new Handle(p);handles.put(p,h);return h;})
                .withCandidateHint(4).withCandidateHint(9);
            scan.step(pool);scan.step(pool);assertEquals(2,handles.size());
            handles.get(9).ready=true;scan.step(pool);assertFalse(scan.done());assertFalse(handles.get(4).closed);
            for(int i=0;i<8;i++)scan.step(pool);
            assertEquals(2,handles.size());assertFalse(scan.done());
            handles.get(4).ready=true;scan.step(pool);assertTrue(scan.done());assertEquals(4,scan.result());
        }finally{pool.shutdownNow();}
    }
    @Test void rejectedPrimaryPromotesSecondaryWithoutRestart()throws Exception {
        var pool=Executors.newSingleThreadExecutor();var handles=new HashMap<Integer,Handle>();
        try {
            var scan=new ScanCoordinator<Integer>(List.of(),Integer::longValue,p->true,100,false)
                .withVerificationWindow(p->{var h=new Handle(p);handles.put(p,h);return h;}).withCandidateHint(1).withCandidateHint(2);
            scan.step(pool);scan.step(pool);handles.get(1).ready=true;handles.get(1).reject=true;scan.step(pool);
            assertTrue(handles.get(2).primary);assertFalse(handles.get(2).closed);
            handles.get(2).ready=true;scan.step(pool);assertEquals(2,scan.result());
        }finally{pool.shutdownNow();}
    }
    @Test void actualAnchorCanMoveAndTieStillNeedsVerification()throws Exception {
        var pool=Executors.newSingleThreadExecutor();var handles=new HashMap<Integer,Handle>();
        try {
            var scan=new ScanCoordinator<Integer>(List.of(),Integer::longValue,p->true,100,false)
                .withVerificationWindow(p->{var h=new Handle(p);handles.put(p,h);return h;})
                .withCandidateLowerBound(p->0).withCandidateHint(1).withCandidateHint(2);
            scan.step(pool);scan.step(pool);handles.get(1).value=50;handles.get(1).ready=true;scan.step(pool);
            assertFalse(scan.done());handles.get(2).value=5;handles.get(2).ready=true;scan.step(pool);assertEquals(5,scan.result());
        }finally{pool.shutdownNow();}
    }
    @Test void cancellationClosesAllStableHandlesOnce()throws Exception {
        var pool=Executors.newSingleThreadExecutor();var handles=new ArrayList<Handle>();
        try {
            var scan=new ScanCoordinator<Integer>(List.of(),Integer::longValue,p->true,100,false)
                .withVerificationWindow(p->{var h=new Handle(p);handles.add(h);return h;}).withCandidateHint(1).withCandidateHint(2);
            scan.step(pool);scan.step(pool);scan.cancel();scan.cancel();
            for(var h:handles){h.ready=true;assertEquals(1,h.closeCalls);}
            assertFalse(scan.step(pool));assertNull(scan.result());
        }finally{pool.shutdownNow();}
    }
    @Test void emptyLookaheadCannotRearmDuringSamePrimaryWait()throws Exception {
        var pool=Executors.newSingleThreadExecutor();var calls=new java.util.concurrent.atomic.AtomicInteger();
        try {
            ScanCoordinator.Lane<Integer> lane=new ScanCoordinator.Lane<>() {
                public boolean available(long limit){return pendingLowerBound()<=limit;}
                public long pendingLowerBound(){return 2+calls.get();}
                public List<Integer> sample(){calls.incrementAndGet();return List.of();}
            };
            var scan=new ScanCoordinator<Integer>(List.of(lane),Integer::longValue,p->true,100,true)
                .withVerificationWindow(Handle::new).withCandidateHint(1);
            for(int i=0;i<100;i++){scan.step(pool);Thread.sleep(1);}
            assertEquals(1,calls.get());assertFalse(scan.done());scan.cancel();
        }finally{pool.shutdownNow();}
    }
}
