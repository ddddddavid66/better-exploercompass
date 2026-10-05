package com.chaosthedude.explorerscompass.benchmark;

import com.chaosthedude.explorerscompass.unified.search.ScanCoordinator;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** 固定数量的同步回调：测协调／轮转开销，不能替代真实世界、后台预测或区块等待的测量。 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(3)
@Warmup(iterations=5,time=1)
@Measurement(iterations=10,time=1)
public class SchedulingBenchmark {
    public interface Driver {long run(int lanes,int calls) throws Exception;}
    @Param({"unified105","unified110","enhance160"}) public String engine;
    @Param({"1","4"}) public int lanes;
    private Driver driver;private ExternalApiBridge bridge;
    @Setup(Level.Trial) public void prepare() throws Exception {
        if(engine.equals("unified110")) {
            driver=(count,calls)->{
                long[] sampled={0};var lanes=new ArrayList<ScanCoordinator.Lane<Long>>();
                for(int i=0;i<count;i++)lanes.add(new ScanCoordinator.Lane<>() {
                    int remaining=calls;
                    public boolean available(long limit){return remaining>0;}
                    public List<Long> sample(){remaining--;sampled[0]++;return List.of();}
                    public long pendingLowerBound(){return remaining>0?0:Long.MAX_VALUE;}
                });
                var search=new ScanCoordinator<>(lanes,Long::longValue,ignored->false,1_000_000,false);
                while(!search.done())search.step(null);return sampled[0];
            };
            if(driver.run(lanes,1024)!=lanes*1024L)throw new IllegalStateException("New scheduler workload mismatch");
            return;
        }
        String api=Driver.class.getCanonicalName(),name,source;
        if(engine.equals("unified105")) {
            name="ExternalCoordinator";
            source="import java.util.*;import com.chaosthedude.explorerscompass.unified.search.ScanCoordinator;"
                    +"public final class ExternalCoordinator implements "+api+" {public long run(int count,int calls)throws Exception {"
                    +"long[] sampled={0};var lanes=new ArrayList<ScanCoordinator.Lane<Long>>();for(int i=0;i<count;i++)lanes.add(new ScanCoordinator.Lane<Long>(){"
                    +"int remaining=calls;public boolean available(long limit){return remaining>0;}public List<Long> sample(){remaining--;sampled[0]++;return List.of();}"
                    +"public long coveredDistanceSquared(){return remaining>0?0:Long.MAX_VALUE;}});"
                    +"var search=new ScanCoordinator<Long>(lanes,Long::longValue,ignored->false,1000000,false);while(!search.done())search.step(null);return sampled[0];}}";
        }else {
            name="com.chaosthedude.explorerscompass.worker.ExternalScheduler";
            source="package com.chaosthedude.explorerscompass.worker;import java.util.*;public final class ExternalScheduler implements "+api
                    +"{public long run(int count,int calls)throws Exception {long[] sampled={0};var scheduler=new SearchScheduler(System::nanoTime,()->0,()->Long.MAX_VALUE);"
                    +"var field=SearchScheduler.class.getDeclaredField(\"slices\");field.setAccessible(true);List slices=(List)field.get(scheduler);"
                    +"for(int i=0;i<count;i++)slices.add(new SearchScheduler.SearchSlice(){int remaining=calls;public boolean hasWork(){return remaining>0;}"
                    +"public boolean doWork(){remaining--;sampled[0]++;return remaining>0;}});"
                    +"while(scheduler.hasWork())scheduler.doWork();return sampled[0];}}";
        }
        bridge=new ExternalApiBridge(engine,name,source,Driver.class);driver=(Driver)bridge.create(name);
        if(driver.run(lanes,1024)!=lanes*1024L)throw new IllegalStateException("External scheduler workload mismatch");
    }
    @Benchmark public long dispatch() throws Exception{return driver.run(lanes,1024);}
    @TearDown(Level.Trial) public void close() throws Exception {if(bridge!=null)bridge.close();}
}
