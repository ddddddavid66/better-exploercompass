package com.chaosthedude.explorerscompass.unified.search;

import com.david.compass.runtime.VerificationGate;

/** 过渡装配：两个产品共用一个核心核对门禁。服务器关闭后重新绑定线程。 */
public final class VerificationSlots {
    private static VerificationGate gate;
    private static VerificationGate current() {
        if (gate==null) gate=new VerificationGate(4);
        return gate;
    }
    public static boolean acquire(Object owner) { return current().acquire(owner,false); }
    public static boolean acquireBackground(Object owner) { return current().acquire(owner,true); }
    public static void release(Object owner) { current().release(owner); }
    public static int count() { return current().held(); }
    public static int queued() { return current().waiting(); }
    static void reset() { gate=null; }
    private VerificationSlots() {}
}
