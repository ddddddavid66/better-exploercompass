package com.chaosthedude.explorerscompass.unified.search;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** 结构与地物共享四个核对名额；仅由服务端线程访问。 */
final class VerificationSlots {
    private static final ArrayDeque<Object> queued = new ArrayDeque<>();
    private static final Set<Object> held = new HashSet<>();
    static boolean acquire(Object owner) {
        if (held.contains(owner)) return true;
        if (!queued.contains(owner)) queued.addLast(owner);
        if (held.size() >= 4 || queued.peekFirst() != owner) return false;
        queued.removeFirst(); held.add(owner); return true;
    }
    static void release(Object owner) { queued.remove(owner); held.remove(owner); }
    static int count() { return held.size(); }
    static int queued() { return queued.size(); }
    private VerificationSlots() {}
}
