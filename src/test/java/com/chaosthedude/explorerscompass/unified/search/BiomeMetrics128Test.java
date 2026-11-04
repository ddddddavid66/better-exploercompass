package com.chaosthedude.explorerscompass.unified.search;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.chaosthedude.explorerscompass.unified.data.BoundedMemoizer;
class BiomeMetrics128Test {
 @Test void onlyBatchPublicationChangesObservedCounters(){
  var m=new BiomeSamplingMetrics();for(int i=0;i<4096;i++)m.sourceCall.run();m.originHits=7;m.columns=3;
  assertEquals(0,m.snapshot().sourceCalls());m.publish();assertEquals(4096,m.snapshot().sourceCalls());
  assertEquals(7,m.snapshot().originHits());assertEquals(3,m.snapshot().columns());
  m.sourceCall.run();assertEquals(4096,m.snapshot().sourceCalls());m.publish();assertEquals(4097,m.snapshot().sourceCalls());
 }
 @Test void cacheReuseDoesNotPretendToCallSource(){
  var cache=new BoundedMemoizer<Integer,String>(4);var first=new BiomeSamplingMetrics();var second=new BiomeSamplingMetrics();
  assertEquals("biome",cache.get(1,k->{first.sourceCall.run();return "biome";}));
  assertEquals("biome",cache.get(1,k->{second.sourceCall.run();return "wrong";}));
  first.publish();second.publish();assertEquals(1,first.snapshot().sourceCalls());assertEquals(0,second.snapshot().sourceCalls());
 }
 @Test void cacheLoaderFailureCountsActualAttemptAndDoesNotCacheFailure(){
  var cache=new BoundedMemoizer<Integer,String>(4);var m=new BiomeSamplingMetrics();
  assertThrows(IllegalStateException.class,()->cache.get(1,k->{m.sourceCall.run();throw new IllegalStateException("failure");}));
  assertEquals("ok",cache.get(1,k->{m.sourceCall.run();return "ok";}));m.publish();assertEquals(2,m.snapshot().sourceCalls());
 }
}
