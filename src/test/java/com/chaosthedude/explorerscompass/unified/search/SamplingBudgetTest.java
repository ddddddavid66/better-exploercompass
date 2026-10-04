package com.chaosthedude.explorerscompass.unified.search;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class SamplingBudgetTest {
    @Test void sharedQuotaIsAtomicAcrossFourWorkers() throws Exception {
        var budget = new SamplingBudget(10000); var pool = Executors.newFixedThreadPool(4);
        try {
            var jobs = new java.util.ArrayList<Future<Integer>>();
            for (int i=0;i<4;i++) jobs.add(pool.submit(() -> { int samples=0;while(budget.take())samples++;return samples; }));
            int samples=0; for (var job:jobs)samples+=job.get();
            assertEquals(10000,samples);assertEquals(10000,budget.used());assertFalse(budget.available());
        } finally {pool.shutdownNow();}
    }
    @Test void limitsAboveIntegerRangeKeepLongValue() {
        var budget=new SamplingBudget(3_000_000_000L);
        assertTrue(budget.take());assertEquals(3_000_000_000L,budget.maximum());assertEquals(1,budget.used());
    }
    @Test void zeroQuotaDoesNotPretendToVisitAnyPoint() {
        var budget=new SamplingBudget(0);assertFalse(budget.take());assertEquals(0,budget.used());
    }
}
