package com.chaosthedude.explorerscompass.unified.search;

import java.util.concurrent.atomic.AtomicLong;

/** 分片共用额度，不能把配置上限乘以线程数。 */
public final class SamplingBudget {
    private final long maximum;
    private final AtomicLong used = new AtomicLong();
    public SamplingBudget(long maximum) { this.maximum = Math.max(0, maximum); }
    public boolean take() {
        long value;
        do { value = used.get(); if (value >= maximum) return false; }
        while (!used.compareAndSet(value, value + 1));
        return true;
    }
    public boolean available() { return used.get() < maximum; }
    public long used() { return used.get(); }
    public long maximum() { return maximum; }
}
