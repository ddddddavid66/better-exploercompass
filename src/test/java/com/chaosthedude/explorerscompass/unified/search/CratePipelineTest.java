package com.chaosthedude.explorerscompass.unified.search;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CratePipelineTest {
 record Value(int id,int score,boolean known){}
 @Test void interleavesLowScoresAndNeverLosesCandidates(){
  var queue=new CrateCandidateOrder<Value>(Comparator.comparingInt(Value::id),Value::known,Value::score);
  for(int i=0;i<12;i++)queue.add(new Value(i,6,false));for(int i=20;i<24;i++)queue.add(new Value(i,0,false));queue.add(new Value(100,0,true));
  assertEquals(100,queue.poll().id());var found=new HashSet<Integer>();for(int i=0;i<16;i++){int id=queue.poll().id();assertTrue(found.add(id));if(i%4==3)assertTrue(id>=20);else assertTrue(id<12);}assertEquals(0,queue.size());
 }
 @Test void sharedChunkLeasesAndHandoffUseExactReferenceCounts(){
  var calls=new ArrayList<String>();var refs=new CrateChunkRefs(k->calls.add("+"+k),k->calls.add("-"+k));Object a=new Object(),b=new Object();
  refs.replace(a,Set.of(1L,2L));refs.replace(b,Set.of(2L,3L));assertEquals(3,refs.size());assertEquals(1,calls.stream().filter("+2"::equals).count());
  refs.replace(a,Set.of(2L,4L));assertEquals(3,refs.size());assertTrue(calls.indexOf("+4")<calls.indexOf("-1"));assertFalse(calls.contains("-2"));
  refs.remove(a);assertFalse(calls.contains("-2"));refs.clear();assertEquals(0,refs.size());for(int i=1;i<=4;i++){String key=""+i;assertEquals(1,calls.stream().filter(("+"+key)::equals).count());assertEquals(1,calls.stream().filter(("-"+key)::equals).count());}
  refs.clear();assertEquals(8,calls.size());
 }
 @Test void duplicateSourceAndTargetNeedOnlyOneTicket(){
  var calls=new ArrayList<Long>();var refs=new CrateChunkRefs(calls::add,k->calls.remove(k));Object owner=new Object();
  refs.replace(owner,new LinkedHashSet<>(List.of(-1L,-1L)));assertEquals(List.of(-1L),calls);refs.remove(owner);assertTrue(calls.isEmpty());
 }
}
