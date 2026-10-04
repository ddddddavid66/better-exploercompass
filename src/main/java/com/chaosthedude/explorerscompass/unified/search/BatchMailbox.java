package com.chaosthedude.explorerscompass.unified.search;

/** 每个分区恰好一个生产者和一个主线程消费者；volatile 游标发布批次，不使用阻塞锁。 */
final class BatchMailbox<T> {
    private final Object[] slots = new Object[4];
    private volatile int read, write;
    boolean offer(T value) {
        java.util.Objects.requireNonNull(value);
        int next = write;
        if (next - read == slots.length) return false;
        slots[next & 3] = value; write = next + 1; return true;
    }
    @SuppressWarnings("unchecked") T poll() {
        int next = read;
        if (next == write) return null;
        T value = (T)slots[next & 3]; slots[next & 3] = null; read = next + 1; return value;
    }
    boolean isEmpty() { return read == write; }
    int remainingCapacity() { return slots.length - (write - read); }
}
