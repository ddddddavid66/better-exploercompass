package com.chaosthedude.explorerscompass.unified.search;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GenerationPrefixTest {
    record Point(int x,int z) {long distance(){return (long)x*x+(long)z*z;}}
    private final List<Point> prefix=List.of(new Point(-3,0),new Point(3,0),new Point(0,-3),new Point(0,3));
    private ScanCoordinator<Point> scan(int radius,SamplingBudget budget,AtomicInteger sampled,
                                        java.util.function.Predicate<Point> verify) {
        return new ScanCoordinator<>(lanes(radius,budget,sampled),Point::distance,verify,(long)radius*radius,true)
                .withTieBreaker(Comparator.comparingInt(Point::x).thenComparingInt(Point::z));
    }
    private List<ScanCoordinator.Lane<Point>> lanes(int radius,SamplingBudget budget,AtomicInteger sampled) {
        var geometry=new RectFrontier.Geometry(0,0,1,0,0,0,0);
        return RectLane.create(geometry,radius,8,4,budget,(x,z)->{
            sampled.incrementAndGet();Point p=new Point(x,z);return p.distance()>=9?List.of(p):List.of();
        },true);
    }
    private Point run(ScanCoordinator<Point> scan,ExecutorService pool) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!scan.done() && System.nanoTime()<deadline){scan.step(pool);Thread.yield();}
        assertTrue(scan.done());return scan.result();
    }
    @Test void completePredictionPrefixRechecksEveryTieWithoutResampling() throws Exception {
        var pool=Executors.newFixedThreadPool(4);var sampled=new AtomicInteger();var checked=new AtomicInteger();
        try {
            var budget=new SamplingBudget(2000);assertTrue(budget.take(29));
            var scan=new ScanCoordinator<Point>(List.of(),Point::distance,p->{checked.incrementAndGet();return true;},400,true)
                    .withTieBreaker(Comparator.comparingInt(Point::x).thenComparingInt(Point::z)).withCoveredPrefix(9)
                    .withDeferredLanes(()->{fail("warm prefix prepared unnecessary outer cursors");return List.of();});
            prefix.forEach(scan::withCandidateHint);
            assertEquals(new Point(-3,0),run(scan,pool));assertEquals(4,checked.get());
            assertEquals(0,sampled.get());assertEquals(29,budget.used());
        }finally{pool.shutdownNow();}
    }
    @Test void rejectedPrefixResumesOuterSearchInsteadOfTrustingOldRecord() throws Exception {
        var pool=Executors.newFixedThreadPool(4);var sampled=new AtomicInteger();
        try {
            var prepared=new AtomicInteger();var budget=new SamplingBudget(2000);
            var scan=new ScanCoordinator<Point>(List.of(),Point::distance,p->p.distance()>=25,400,true)
                    .withTieBreaker(Comparator.comparingInt(Point::x).thenComparingInt(Point::z)).withCoveredPrefix(9)
                    .withDeferredLanes(()->{prepared.incrementAndGet();return lanes(20,budget,sampled);});
            prefix.forEach(scan::withCandidateHint);
            assertEquals(new Point(-5,0),run(scan,pool));assertTrue(sampled.get()>0);assertEquals(1,prepared.get());
        }finally{pool.shutdownNow();}
    }
    @Test void changedRealAnchorOutsidePrefixRequiresFurtherCoverage() throws Exception {
        var pool=Executors.newFixedThreadPool(4);
        try {
            var geometry=new RectFrontier.Geometry(0,0,1,0,0,0,0);
            var lanes=RectLane.create(geometry,20,8,4,new SamplingBudget(2000),(x,z)->{
                Point p=new Point(x,z);return p.distance()>=4?List.of(p):List.of();
            },true);
            var prepared=new AtomicInteger();
            var scan=new ScanCoordinator<Point>(List.of(),Point::distance,p->true,400,true).withCoveredPrefix(1)
                    .withDeferredLanes(()->{prepared.incrementAndGet();return lanes;})
                    .withResolvedCandidate(p->p.distance()==1?new Point(10,0):p)
                    .withTieBreaker(Comparator.comparingInt(Point::x).thenComparingInt(Point::z))
                    .withCandidateHint(new Point(1,0));
            assertEquals(new Point(-2,0),run(scan,pool));assertEquals(1,prepared.get());
        }finally{pool.shutdownNow();}
    }
    @Test void completeRejectedPrefixIsRangeExhaustionEvenWhenQuotaIsFullyCharged() throws Exception {
        var pool=Executors.newFixedThreadPool(4);var sampled=new AtomicInteger();
        try {
            var budget=new SamplingBudget(29);assertTrue(budget.take(29));
            var scan=scan(3,budget,sampled,p->false).withCoveredPrefix(9);prefix.forEach(scan::withCandidateHint);
            assertNull(run(scan,pool));assertFalse(scan.budgetLimited());assertEquals(0,sampled.get());
        }finally{pool.shutdownNow();}
    }
    @Test void bulkQuotaIsAtomicAndCannotOverflowOrBypassLowerLimits() {
        var budget=new SamplingBudget(10);assertFalse(budget.take(11));assertEquals(0,budget.used());
        assertTrue(budget.take(10));assertFalse(budget.take());assertThrows(IllegalArgumentException.class,()->budget.take(-1));
        var large=new SamplingBudget(Long.MAX_VALUE);assertTrue(large.take(Long.MAX_VALUE));assertFalse(large.take());
        assertEquals(Long.MAX_VALUE,large.used());
    }
    @Test void prefixScopeRejectsShiftedOriginsRelaxedExclusionsAndInsufficientQuota() {
        var prefix=new WorldgenCache.Prefix(-17,31,100,45,Set.of(7L),List.of());
        assertTrue(prefix.covers(new net.minecraft.core.BlockPos(-17,64,31),Set.of(7L,8L),100,45));
        assertFalse(prefix.covers(new net.minecraft.core.BlockPos(-16,64,31),Set.of(7L),100,45));
        assertFalse(prefix.covers(new net.minecraft.core.BlockPos(-17,64,31),Set.of(),100,45));
        assertFalse(prefix.covers(new net.minecraft.core.BlockPos(-17,64,31),Set.of(7L),99,45));
        assertFalse(prefix.covers(new net.minecraft.core.BlockPos(-17,64,31),Set.of(7L),100,44));
    }
}
