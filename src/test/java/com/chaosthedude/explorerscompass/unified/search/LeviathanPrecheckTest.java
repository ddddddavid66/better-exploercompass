package com.chaosthedude.explorerscompass.unified.search;

import com.chaosthedude.explorerscompass.unified.data.BoundedMemoizer;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LeviathanPrecheckTest {
    private record Point(String value) {}
    private static void worker(Runnable test) throws Exception {
        var executor = Executors.newSingleThreadExecutor(action -> new Thread(action, "compassfilters-search-precheck-test"));
        try { executor.submit(test).get(5, TimeUnit.SECONDS); }
        finally { executor.shutdownNow(); }
    }
    @Test void rejectedBiomeSkipsOfficialTerrainAndFalseTerrainDiscardsPoint() throws Exception {
        worker(() -> {
            Object capability = new Object(); var calls = new AtomicInteger();
            assertNull(LeviathanPrecheck.predict(capability, () -> {
                assertTrue(LeviathanPrecheck.defer(capability, () -> { calls.incrementAndGet(); return true; }));
                return null;
            }));
            assertEquals(0, calls.get());
            assertNull(LeviathanPrecheck.predict(capability, () -> {
                assertTrue(LeviathanPrecheck.defer(capability, () -> { calls.incrementAndGet(); return false; }));
                return "valid-biome-at-actual-y";
            }));
            assertEquals(1, calls.get()); assertFalse(LeviathanPrecheck.deferring(capability));
        });
    }
    @Test void acceptedPointIsUnchangedAndReplayCannotCaptureAgain() throws Exception {
        worker(() -> {
            Object capability = new Object(), point = new Object();
            assertSame(point, LeviathanPrecheck.predict(capability, () -> {
                assertFalse(LeviathanPrecheck.defer(new Object(), () -> false));
                assertTrue(LeviathanPrecheck.defer(capability, () -> {
                    assertFalse(LeviathanPrecheck.deferring(capability)); return true;
                }));
                return point;
            }));
        });
    }
    @Test void terrainValidationPrecedesMemoizationAndCacheHitsKeepBothResults() throws Exception {
        worker(() -> {
            Object capability = new Object(); var calls = new AtomicInteger();
            var memo = new BoundedMemoizer<String, Point>(4);
            for (boolean accepted : new boolean[] { false, true }) {
                String key = Boolean.toString(accepted);
                for (int repeat = 0; repeat < 2; repeat++) {
                    String result = LeviathanPrecheck.predict(capability, () -> memo.get(key, ignored -> {
                        LeviathanPrecheck.defer(capability, () -> { calls.incrementAndGet(); return accepted; });
                        return new Point(LeviathanPrecheck.validate("actual-generation-point"));
                    }).value());
                    assertEquals(accepted ? "actual-generation-point" : null, result);
                }
            }
            assertEquals(2, calls.get());
            assertFalse(LeviathanPrecheck.deferring(capability));
        });
    }
    @Test void nestedScopesRestoreOuterAndBothExceptionPathsClearCapture() throws Exception {
        worker(() -> {
            Object outer = new Object(), inner = new Object();
            assertEquals("outer", LeviathanPrecheck.predict(outer, () -> {
                assertEquals("inner", LeviathanPrecheck.predict(inner, () -> {
                    assertTrue(LeviathanPrecheck.defer(inner, () -> {
                        assertFalse(LeviathanPrecheck.deferring(outer)); return true;
                    })); return "inner";
                }));
                assertTrue(LeviathanPrecheck.deferring(outer));
                assertTrue(LeviathanPrecheck.defer(outer, () -> true)); return "outer";
            }));
            assertThrows(IllegalStateException.class, () -> LeviathanPrecheck.predict(outer, () -> { throw new IllegalStateException("generator"); }));
            assertFalse(LeviathanPrecheck.deferring(outer));
            assertThrows(IllegalStateException.class, () -> LeviathanPrecheck.predict(outer, () -> {
                LeviathanPrecheck.defer(outer, () -> { throw new IllegalStateException("terrain"); }); return "point";
            }));
            assertFalse(LeviathanPrecheck.deferring(outer));
        });
    }
    @Test void ordinaryThreadAndOtherThreadsNeverSeePredictionScope() throws Exception {
        Object capability = new Object();
        assertEquals("original", LeviathanPrecheck.predict(capability, () -> {
            assertFalse(LeviathanPrecheck.deferring(capability)); return "original";
        }));
        worker(() -> LeviathanPrecheck.predict(capability, () -> {
            var visible = new AtomicInteger();
            var observer = new Thread(() -> { if (LeviathanPrecheck.deferring(capability)) visible.incrementAndGet(); });
            observer.start();
            try { observer.join(1000); } catch (InterruptedException error) { throw new AssertionError(error); }
            assertFalse(observer.isAlive()); assertEquals(0, visible.get());
            return "point";
        }));
        assertFalse(LeviathanPrecheck.deferring(capability));
    }
}
