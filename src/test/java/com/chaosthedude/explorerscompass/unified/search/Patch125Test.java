package com.chaosthedude.explorerscompass.unified.search;

import com.chaosthedude.explorerscompass.unified.data.PatchConnectivity;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Patch125Test {
    @Test void smallIslandsAndWindowEdgesAreDifferent(){
        boolean[] mask=new boolean[81];mask[40]=mask[41]=true;
        var small=PatchConnectivity.connected(mask,9,40);assertTrue(small.small());assertFalse(small.large());assertEquals(2,small.cells().size());
        mask[0]=true;var edge=PatchConnectivity.connected(mask,9,0);assertFalse(edge.small());assertTrue(edge.large());
        for(int z=3;z<=5;z++)for(int x=3;x<=5;x++)mask[z*9+x]=true;
        assertTrue(PatchConnectivity.connected(mask,9,40).large());
    }
    @Test void diagonalSamplesDoNotJoinAnIsland(){
        boolean[] mask=new boolean[81];mask[40]=mask[50]=true;
        assertEquals(List.of(40),PatchConnectivity.connected(mask,9,40).cells());
    }
    @Test void candidateStreamContinuesBeyondFirstHintWithoutRestartOrPruning()throws Exception{
        var pool=Executors.newFixedThreadPool(2);
        try{
            for(boolean async:new boolean[]{false,true}){
                var budget=new SamplingBudget(2000);var geometry=new RectFrontier.Geometry(0,0,1,0,0,0,0);
                var lanes=RectLane.<Long>create(geometry,8,4,2,budget,(x,z)->List.of((long)x*x+(long)z*z),true);
                var scan=new ScanCoordinator<>(lanes,n->n,p->true,64,async).withCandidateStream(2).withBatch(8,4_000_000L);
                int hints=0;long farthest=0,deadline=System.nanoTime()+5_000_000_000L;
                while(!scan.done() && System.nanoTime()<deadline){scan.step(pool);Long hint;while((hint=scan.pollCandidate())!=null){hints++;farthest=Math.max(farthest,hint);}Thread.yield();}
                assertTrue(scan.done());assertTrue(hints>20);assertTrue(farthest>=49);assertFalse(scan.budgetLimited());assertNull(scan.result());
            }
        }finally{pool.shutdownNow();}
    }
}
