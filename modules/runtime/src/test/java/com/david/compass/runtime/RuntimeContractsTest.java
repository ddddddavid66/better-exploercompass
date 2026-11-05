package com.david.compass.runtime;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeContractsTest {
    private ForegroundTasks tasks() { return new ForegroundTasks(error -> fail(error)); }
    @Test void replacingProductInvalidatesOldCallbackBeforeCancellation() {
        var tasks = tasks(); var player = UUID.randomUUID();
        var old = new AtomicReference<ForegroundTasks.Handle>();
        var reasons = new ArrayList<ForegroundTasks.Reason>();
        old.set(tasks.begin("explorerscompass", player, UUID.randomUUID(), reason -> {
            reasons.add(reason); assertFalse(old.get().current()); assertFalse(old.get().complete());
        }));
        var next = tasks.begin("creaturecompass", player, UUID.randomUUID(), reasons::add);
        old.get().cancel();
        assertTrue(next.current()); assertEquals(1, tasks.size());
        assertEquals(java.util.List.of(ForegroundTasks.Reason.REPLACED), reasons);
    }
    @Test void differentPlayersRemainIndependent() {
        var tasks = tasks();
        var first = tasks.begin("explorerscompass", UUID.randomUUID(), UUID.randomUUID(), reason -> {});
        var second = tasks.begin("creaturecompass", UUID.randomUUID(), UUID.randomUUID(), reason -> {});
        assertTrue(first.complete()); assertFalse(first.complete()); assertTrue(second.current());
        assertEquals(1, tasks.size());
    }
    @Test void reloadCancelsOnceAndIncrementsGeneration() {
        var tasks = tasks(); var reasons = new ArrayList<ForegroundTasks.Reason>();
        var first = tasks.begin("creaturecompass", UUID.randomUUID(), UUID.randomUUID(), reasons::add);
        tasks.invalidate(ForegroundTasks.Reason.RELOADED); first.cancel();
        var next = tasks.begin("creaturecompass", first.key().player(), UUID.randomUUID(), reasons::add);
        assertEquals(1, next.key().generation()); assertFalse(first.current());
        assertEquals(java.util.List.of(ForegroundTasks.Reason.RELOADED), reasons);
    }
    @Test void cancellationErrorDoesNotLoseReplacement() {
        var errors = new ArrayList<RuntimeException>(); var tasks = new ForegroundTasks(errors::add);
        var player = UUID.randomUUID();
        tasks.begin("explorerscompass", player, UUID.randomUUID(), reason -> { throw new IllegalStateException("probe"); });
        var next = tasks.begin("creaturecompass", player, UUID.randomUUID(), reason -> {});
        assertEquals(1, errors.size()); assertTrue(next.current());
    }
    @Test void wrongThreadCannotMutateRegistry() throws Exception {
        var tasks = tasks(); var failure = new AtomicReference<Throwable>();
        var thread = new Thread(() -> { try { tasks.invalidate(ForegroundTasks.Reason.SHUTDOWN); }
            catch (Throwable error) { failure.set(error); } });
        thread.start(); thread.join(); assertInstanceOf(IllegalStateException.class, failure.get());
    }
    @Test void backgroundCannotBypassFifo() {
        var gate = new VerificationGate(1); var a = new Object(); var b = new Object(); var c = new Object();
        assertTrue(gate.acquire(a, false)); assertFalse(gate.acquire(b, false)); gate.release(a);
        assertFalse(gate.acquire(c, true)); assertTrue(gate.acquire(b, false)); gate.release(b);
        assertTrue(gate.acquire(c, true)); gate.release(c); gate.release(c);
        assertEquals(0, gate.held()); assertEquals(0, gate.waiting());
    }
    @Test void equalButDifferentOwnersDoNotShareSlots() {
        var gate = new VerificationGate(4); var a = new String("same"); var b = new String("same");
        assertTrue(gate.acquire(a, false)); assertTrue(gate.acquire(b, false));
        assertEquals(2, gate.held()); gate.release(a); assertEquals(1, gate.held());
    }
    @Test void fourSlotsAreBoundedAndCancelledWaiterIsRemoved() {
        var gate = new VerificationGate(4);
        for (int i = 0; i < 4; i++) assertTrue(gate.acquire(new Object(), false));
        var fifth = new Object(); assertFalse(gate.acquire(fifth, false)); assertEquals(1, gate.waiting());
        gate.release(fifth); assertEquals(0, gate.waiting()); assertEquals(4, gate.held());
    }
    @Test void snapshotUsesLongQuotaAndRejectsInvalidParameters() {
        var settings = new SearchParameters(320000, 2160000000L, 32, 4, 4, true);
        assertEquals(2160000000L, settings.sampleLimit());
        assertThrows(IllegalArgumentException.class, () -> new SearchParameters(8, -1, 32, 4, 4, true));
        assertThrows(IllegalArgumentException.class, () -> new SearchParameters(8, 1, 0, 4, 4, true));
    }
}
