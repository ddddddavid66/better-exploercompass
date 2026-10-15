package com.chaosthedude.explorerscompass.unified.search;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** 结构与地物共享四个核对名额；仅由服务端线程访问。 */
public final class VerificationSlots {
    private static final ArrayDeque<Object> queued = new ArrayDeque<>();
    private static final Set<Object> held = new HashSet<>();
    public static boolean acquire(Object owner) {
        if (held.contains(owner)) return true;
        if (!queued.contains(owner)) queued.addLast(owner);
        if (held.size() >= 4 || queued.peekFirst() != owner) return false;
        queued.removeFirst(); held.add(owner); return true;
    }
    public static void release(Object owner) { queued.remove(owner); held.remove(owner); }
    public static int count() { return held.size(); }
    public static int queued() { return queued.size(); }
    /** 预探索不进入前台等待队列，不能挡住搜索或传送。 */
    public static boolean acquireBackground(Object owner){if(held.contains(owner))return true;if(!queued.isEmpty() || held.size()>=4)return false;held.add(owner);return true;}
    private VerificationSlots() {}
}
