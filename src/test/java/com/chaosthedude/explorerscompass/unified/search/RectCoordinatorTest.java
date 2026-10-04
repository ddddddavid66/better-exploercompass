package com.chaosthedude.explorerscompass.unified.search;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Comparator;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RectCoordinatorTest {
    record Point(int x, int z) { long distance() { return (long)x * x + (long)z * z; } }
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
            var search = search(3, false, ignored -> true, ignored -> true);
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
            var search = search(2000, true, point -> point.equals(new Point(3, 0)) || point.equals(new Point(4, 0)), ignored -> true)
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
}
