package com.chaosthedude.explorerscompass.unified.search;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Comparator;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RectCoordinatorTest {
    record Point(int x, int z) { long distance() { return (long)x * x + (long)z * z; } }
    record Shifted(int i,int j,int x,int z,int actualX,int actualZ) {
        long distance(int qx,int qz){return (long)(x-qx)*(x-qx)+(long)(z-qz)*(z-qz);}
        Shifted resolved(){return new Shifted(i,j,actualX,actualZ,actualX,actualZ);}
    }
    private ScanCoordinator<Point> search(int quota, boolean async, java.util.function.Predicate<Point> exists,
                                          java.util.function.Predicate<Point> verify) {
        var budget = new SamplingBudget(quota);
        var geometry = new RectFrontier.Geometry(0, 0, 1, 0, 0, 0, 0);
        var lanes = RectLane.create(geometry, 20, 8, 4, budget,
                (x, z) -> { var point = new Point(x, z); return exists.test(point) ? List.of(point) : List.of(); }, true);
        return new ScanCoordinator<>(lanes, Point::distance, verify, 400, async)
                .withTieBreaker(Comparator.comparingInt(Point::x).thenComparingInt(Point::z)).withBatch(16, 4_000_000);
    }
    private <T> T run(ScanCoordinator<T> search, ExecutorService executor) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!search.done() && System.nanoTime() < deadline) {
            search.step(executor); Thread.yield();
        }
        assertTrue(search.done(), "search stalled"); return search.result();
    }
    @Test void nearestAndDeterministicTiesMatchAsyncAndSync() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            for (int repeat = 0; repeat < 12; repeat++) for (boolean async : new boolean[]{false, true}) {
                var search = search(2000, async, point -> point.distance() >= 9, ignored -> true);
                assertEquals(new Point(-3, 0), run(search, pool));
            }
        } finally { pool.shutdownNow(); }
    }
    @Test void rejectedOldChunksDoNotPermanentlyPruneUnsearchedRange() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            var search = search(2000, true, point -> point.distance() >= 4, point -> point.distance() >= 25);
            assertEquals(new Point(-5, 0), run(search, pool));
        } finally { pool.shutdownNow(); }
    }
    @Test void quotaCannotBeMistakenForProofOfNearest() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        try {
            var search = search(3, false, point -> point.distance() > 0, ignored -> true);
            assertNull(run(search, pool)); assertTrue(search.budgetLimited());
        } finally { pool.shutdownNow(); }
    }
    @Test void rangeExhaustionIsDifferentFromQuota() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        try {
            var search = search(2000, true, ignored -> false, ignored -> true);
            assertNull(run(search, pool)); assertFalse(search.budgetLimited());
        } finally { pool.shutdownNow(); }
    }
    @Test void correctedAnchorChangesUpperBoundAndCandidateOrdering() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            // 来源单元的范围必须同时包住预测与核对后的锚点，不能使用不成立的点下界。
            var geometry = new RectFrontier.Geometry(0, 0, 1, -8, -8, 8, 8);
            var lanes = RectLane.create(geometry, 20, 8, 4, new SamplingBudget(10000),
                    (x,z) -> x == 3 && z == 0 || x == 4 && z == 0 ? List.of(new Point(x,z)) : List.<Point>of(), true);
            var search = new ScanCoordinator<>(lanes, Point::distance, ignored -> true, 400, true)
                    .withCandidateLowerBound(ignored -> 0)
                    .withResolvedCandidate(point -> point.x == 3 ? new Point(10, 0) : new Point(2, 0));
            assertEquals(new Point(2, 0), run(search, pool));
        } finally { pool.shutdownNow(); }
    }
    @Test void waitAndQueuedPacketsParticipateInProof() throws Exception {
        var pool = Executors.newFixedThreadPool(4); var checks = new AtomicInteger();
        try {
            var search = search(2000, true, point -> point.distance() == 4, ignored -> false)
                    .withConfirmation(ignored -> checks.incrementAndGet() < 50 ? ScanCoordinator.Confirmation.WAIT : ScanCoordinator.Confirmation.ACCEPT);
            assertEquals(new Point(-2, 0), run(search, pool)); assertTrue(checks.get() >= 50);
        } finally { pool.shutdownNow(); }
    }
    @Test void cancellationNeverSubmitsLateResult() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            var search = search(2000, true, ignored -> true, ignored -> true);
            search.step(pool); search.cancel();
            for (int i = 0; i < 100; i++) assertFalse(search.step(pool));
            assertNull(search.result());
        } finally { pool.shutdownNow(); }
    }
    @Test void primingStartsOnlyBackgroundSamplingAndNeverConfirmsOffThread() throws Exception {
        var pool=Executors.newFixedThreadPool(4); var sampled=new CountDownLatch(1); var confirmations=new AtomicInteger();
        try {
            var search=search(2000,true,point->{sampled.countDown();return true;},point->{confirmations.incrementAndGet();return true;});
            search.prime(pool); assertTrue(sampled.await(1,TimeUnit.SECONDS)); assertEquals(0,confirmations.get());
            assertEquals(new Point(0,0),run(search,pool)); assertTrue(confirmations.get()>0);
        } finally {pool.shutdownNow();}
    }
    @Test void primingSynchronousRequestDoesNotMoveWorkToBackground() {
        var pool=Executors.newSingleThreadExecutor();var samples=new AtomicInteger();
        try {
            var search=search(2000,false,point->{samples.incrementAndGet();return true;},point->true);
            search.prime(pool); assertEquals(0,samples.get()); search.cancel();
        } finally {pool.shutdownNow();}
    }
    @Test void boundedExecutorRejectsWithoutCallingSamplerOnMainThread() throws Exception {
        var pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
        var release = new CountDownLatch(1); var started = new CountDownLatch(1); var calls = new AtomicInteger();
        try {
            pool.execute(() -> { started.countDown(); try { release.await(); } catch (InterruptedException ignored) {} });
            assertTrue(started.await(1, TimeUnit.SECONDS)); pool.execute(() -> {});
            var search = search(2000, true, point -> { calls.incrementAndGet(); return true; }, ignored -> true);
            assertFalse(search.step(pool)); assertEquals(0, calls.get());
            release.countDown(); assertNotNull(run(search, pool));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void fourIndependentRequestsCompleteOnSharedPool() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            var searches = java.util.stream.IntStream.range(0, 4).mapToObj(i -> search(2000, true,
                    point -> point.distance() >= (i + 1) * (i + 1), ignored -> true)).toList();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (searches.stream().anyMatch(search -> !search.done()) && System.nanoTime() < deadline)
                for (var search : searches) search.step(pool);
            for (int i = 0; i < 4; i++) assertEquals(new Point(-i - 1, 0), searches.get(i).result());
        } finally { pool.shutdownNow(); }
    }
    @Test void randomizedVerifiedAnchorsAndRejectionsMatchFiniteExhaustiveSearch() throws Exception {
        var pool=Executors.newFixedThreadPool(4);var random=new java.util.Random(20241002);
        try {
            for(int trial=0;trial<40;trial++) {
                int qx=random.nextInt(40)-20,qz=random.nextInt(40)-20,salt=trial;
                var geometry=new RectFrontier.Geometry(qx,qz,5,-1,-1,6,6);
                java.util.function.BiFunction<Integer,Integer,List<Shifted>> sample=(i,j)->{
                    int hash=Math.floorMod(i*1237+j*461+salt,97);
                    return hash%5==0?List.of(new Shifted(i,j,i*5+5,j*5+5,i*5+hash%8-1,j*5+(hash/8)%8-1)):List.of();
                };
                var order=Comparator.<Shifted>comparingLong(p->p.distance(qx,qz)).thenComparingInt(Shifted::x).thenComparingInt(Shifted::z);
                var domain=geometry.domain(60); Shifted expected=null;
                for(int i=domain.minX();i<=domain.maxX();i++)for(int j=domain.minZ();j<=domain.maxZ();j++)for(var predicted:sample.apply(i,j)) {
                    var actual=predicted.resolved();
                    if(Math.floorMod(i+j,7)!=0&&actual.distance(qx,qz)<=3600&&(expected==null||order.compare(actual,expected)<0))expected=actual;
                }
                var lanes=RectLane.create(geometry,60,8,4,new SamplingBudget(10000),sample,true);
                var search=new ScanCoordinator<>(lanes,p->p.distance(qx,qz),p->Math.floorMod(p.i+p.j,7)!=0,3600,true)
                        .withCandidateLowerBound(p->geometry.lowerBound(p.i,p.j,p.i,p.j)).withResolvedCandidate(Shifted::resolved)
                        .withTieBreaker(Comparator.comparingInt(Shifted::x).thenComparingInt(Shifted::z));
                search.prime(pool);assertEquals(expected,run(search,pool),"trial "+trial);
            }
        }finally{pool.shutdownNow();}
    }
    @Test void backgroundExceptionsAreSurfacedInsteadOfClaimingCoverage() throws Exception {
        var pool=Executors.newFixedThreadPool(4);
        try {
            var search=search(2000,true,point->{throw new IllegalStateException("unreviewed generator");},point->true);
            assertThrows(ExecutionException.class,()->run(search,pool)); assertNull(search.result()); search.cancel();
        }finally{pool.shutdownNow();}
    }
    @Test void unconditionalActualSamplesPreserveNearestTiesInBothModes() throws Exception {
        var pool=Executors.newFixedThreadPool(4);
        try {
            for(int repeat=0;repeat<40;repeat++)for(boolean async:new boolean[]{true,false}) {
                var search=search(2000,async,p->p.distance()>=9,p->true)
                        .withEagerCandidates(false).withUnconditionalConfirmation();
                search.prime(pool);assertEquals(new Point(-3,0),run(search,pool));
            }
        }finally{pool.shutdownNow();}
    }
    @Test void unconditionalPathCannotBeMixedWithRejectableOrAdjustedCandidates() {
        var trusted=search(2000,true,p->true,p->true).withUnconditionalConfirmation();
        assertThrows(IllegalStateException.class,()->trusted.withConfirmation(p->ScanCoordinator.Confirmation.REJECT));
        assertThrows(IllegalStateException.class,()->trusted.withResolvedCandidate(p->new Point(999,999)));
        assertThrows(IllegalStateException.class,()->trusted.withCandidateLowerBound(p->0));
        var adjusted=search(2000,true,p->true,p->true).withResolvedCandidate(p->p);
        assertThrows(IllegalStateException.class,adjusted::withUnconditionalConfirmation);
    }
}
