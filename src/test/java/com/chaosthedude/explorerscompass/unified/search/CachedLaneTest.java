package com.chaosthedude.explorerscompass.unified.search;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

class CachedLaneTest {
    record Point(int x,int z) {long distance(){return (long)x*x+(long)z*z;}}
    private ScanCoordinator<Point> scan(int quota,BiFunction<Integer,Integer,List<Point>> sample,
            BiFunction<Integer,Integer,List<Point>> cached,java.util.function.Predicate<Point> verify) {
        var geometry=new RectFrontier.Geometry(0,0,1,0,0,0,0);
        var lanes=RectLane.create(geometry,20,8,4,new SamplingBudget(quota),sample,true,cached);
        return new ScanCoordinator<>(lanes,Point::distance,verify,400,true)
                .withTieBreaker(Comparator.comparingInt(Point::x).thenComparingInt(Point::z))
                .withCandidateHint(new Point(10,0));
    }
    private Point run(ScanCoordinator<Point> scan,ExecutorService pool) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!scan.done() && System.nanoTime()<deadline){scan.step(pool);Thread.yield();}
        assertTrue(scan.done());return scan.result();
    }
    private List<Point> exists(int x,int z) {
        Point point=new Point(x,z);return point.distance()>=9?List.of(point):List.of();
    }
    @Test void completeCacheNeedsNoBackgroundGenerationAndStillFindsNearestTie() throws Exception {
        var pool=Executors.newFixedThreadPool(4);var calls=new AtomicInteger();
        try {
            var scan=scan(2000,(x,z)->{calls.incrementAndGet();return exists(x,z);},this::exists,p->true);
            assertEquals(new Point(-3,0),run(scan,pool));assertEquals(0,calls.get());
            assertEquals(0,scan.submittedBatches());
        }finally{pool.shutdownNow();}
    }
    @Test void missKeepsSamePointAndDoesNotConsumeQuotaBeforeBackgroundSample() {
        var budget=new SamplingBudget(1);var geometry=new RectFrontier.Geometry(0,0,1,0,0,0,0);
        var calls=new ArrayList<Point>();
        var lane=RectLane.create(geometry,0,8,1,budget,
                (x,z)->{calls.add(new Point(x,z));return List.of(new Point(x,z));},true,(x,z)->null).get(0);
        assertNull(lane.sampleCached(0));assertEquals(0,budget.used());assertEquals(0,lane.pendingLowerBound());
        assertEquals(List.of(new Point(0,0)),lane.sample(0));assertEquals(1,budget.used());
        assertEquals(List.of(new Point(0,0)),calls);assertEquals(Long.MAX_VALUE,lane.pendingLowerBound());
    }
    @Test void cachedPredictionsAreRecheckedAndCanBeRejected() throws Exception {
        var pool=Executors.newFixedThreadPool(4);var rejected=new AtomicInteger();
        try {
            var scan=scan(2000,this::exists,this::exists,p->{
                if(p.distance()<25){rejected.incrementAndGet();return false;}return true;
            });
            assertEquals(new Point(-5,0),run(scan,pool));assertTrue(rejected.get()>0);
        }finally{pool.shutdownNow();}
    }
    @Test void cachedQuotaExhaustionCannotCertifyHint() throws Exception {
        var pool=Executors.newFixedThreadPool(4);
        try {
            var scan=scan(1,this::exists,this::exists,p->true);
            assertNull(run(scan,pool));assertTrue(scan.budgetLimited());
        }finally{pool.shutdownNow();}
    }
}
