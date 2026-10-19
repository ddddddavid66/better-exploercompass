package com.chaosthedude.explorerscompass.unified.search;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GoldenCrateRandomTest {
 @Test void concurrentPredictionsAreStable()throws Exception{
  var rules=new GoldenCrateAdapter.Snapshot(Set.of(),List.of(),2,8,128,true,"");var slot=new GoldenCrateAdapter.Slot(3,77);
  var expected=new ArrayList<BlockPos>();for(int x=-1000;x<1000;x++)expected.add(GoldenCrateAdapter.predict(42,x,-x,slot,rules));
  var pool=Executors.newFixedThreadPool(4);try{var jobs=new ArrayList<Future<?>>();for(int i=0;i<4;i++)jobs.add(pool.submit(()->{for(int x=999;x>=-1000;x--)assertEquals(expected.get(x+1000),GoldenCrateAdapter.predict(42,x,-x,slot,rules));}));for(var job:jobs)job.get();}finally{pool.shutdownNow();}
 }
}
