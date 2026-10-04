package com.chaosthedude.explorerscompass.unified.search;

import java.util.concurrent.atomic.AtomicLong;

/** 分片共用额度，不能把配置上限乘以线程数。 */
public final class SamplingBudget {
    private final long maximum;
    private final AtomicLong used = new AtomicLong();
    public SamplingBudget(long maximum) { this.maximum = Math.max(0, maximum); }
    public boolean take() {
        return take(1);
    }
    public boolean take(long amount) {
        if(amount<0)throw new IllegalArgumentException("Negative sample cost");
        long value;
        do { value = used.get(); if (amount>maximum-value) return false; }
        while (!used.compareAndSet(value, value + amount));
        return true;
    }
    public boolean available() { return used.get() < maximum; }
    public long used() { return used.get(); }
    public long maximum() { return maximum; }
}
