package com.david.compass.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** 仅限拥有者线程使用；两个产品在运行时必须注册到同一个实例。 */
public final class ForegroundTasks {
    public enum Reason { REPLACED, CANCELLED, RELOADED, DISCONNECTED, SHUTDOWN }
    private final Thread owner = Thread.currentThread();
    private final Map<UUID, Handle> active = new HashMap<>();
    private final Consumer<RuntimeException> errors;
    private long generation;

    public ForegroundTasks(Consumer<RuntimeException> errors) {
        this.errors = Objects.requireNonNull(errors);
    }

    public Handle begin(String product, UUID player, UUID request, Consumer<Reason> cancel) {
        checkThread();
        Handle next = new Handle(new TaskKey(product, player, request, generation),
                Objects.requireNonNull(cancel));
        // 先使旧句柄失效，再回调。旧任务的取消/完成通知不能移除新任务。
        Handle previous = active.put(player, next);
        if (previous != null) previous.retire(Reason.REPLACED);
        return next;
    }

    public void cancel(UUID player, Reason reason) {
        checkThread();
        Objects.requireNonNull(reason);
        Handle handle = active.remove(player);
        if (handle != null) handle.retire(reason);
    }

    public void invalidate(Reason reason) {
        checkThread();
        Objects.requireNonNull(reason);
        // 清空后逐个取消，防止清理某个任务时操作其他任务破坏遍历。
        var old = active.values().toArray(Handle[]::new);
        active.clear();
        generation = Math.incrementExact(generation);
        for (Handle handle : old) handle.retire(reason);
    }

    public int size() { checkThread(); return active.size(); }
    private void checkThread() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Foreground tasks require the owning server thread");
    }

    public final class Handle {
        private final TaskKey key;
        private final Consumer<Reason> cancel;
        private boolean ended;
        private Handle(TaskKey key, Consumer<Reason> cancel) { this.key = key; this.cancel = cancel; }
        public TaskKey key() { return key; }
        public boolean current() {
            checkThread();
            return !ended && key.generation() == generation && active.get(key.player()) == this;
        }
        /** 正常完成不触发取消回调；迟到完成不能结束替代者。 */
        public boolean complete() {
            if (!current()) return false;
            ended = true;
            active.remove(key.player(), this);
            return true;
        }
        public void cancel() {
            checkThread();
            active.remove(key.player(), this);
            retire(Reason.CANCELLED);
        }
        private void retire(Reason reason) {
            if (ended) return;
            ended = true;
            try { cancel.accept(reason); }
            catch (RuntimeException failure) { errors.accept(failure); }
        }
    }
}
