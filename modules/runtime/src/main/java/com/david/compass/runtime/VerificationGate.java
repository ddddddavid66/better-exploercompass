package com.david.compass.runtime;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** 中性 FIFO 核对名额；后台预取不能越过已有前台等待者。 */
public final class VerificationGate {
    private final Thread owner = Thread.currentThread();
    private final ArrayDeque<Object> queued = new ArrayDeque<>();
    private final Set<Object> held = Collections.newSetFromMap(new IdentityHashMap<>());
    private final int capacity;
    public VerificationGate(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Invalid capacity");
        this.capacity = capacity;
    }
    public boolean acquire(Object task, boolean background) {
        check(task);
        if (held.contains(task)) return true;
        if (background) {
            if (!queued.isEmpty() || held.size() >= capacity) return false;
        } else {
            if (queued.stream().noneMatch(waiter -> waiter == task)) queued.addLast(task);
            if (held.size() >= capacity || queued.peekFirst() != task) return false;
            queued.removeFirst();
        }
        held.add(task);
        return true;
    }
    public void release(Object task) {
        check(task);
        queued.removeIf(waiter -> waiter == task);
        held.remove(task);
    }
    public int held() { check(this); return held.size(); }
    public int waiting() { check(this); return queued.size(); }
    private void check(Object task) {
        if (task == null) throw new NullPointerException("task");
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Verification gate requires the owning server thread");
    }
}
