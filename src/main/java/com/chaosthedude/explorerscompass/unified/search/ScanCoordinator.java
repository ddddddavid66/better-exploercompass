package com.chaosthedude.explorerscompass.unified.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/** 矩形下界、未消费候选和在途批次共同构成最近性证明。预测只暂停工作，不删除范围。 */
public final class ScanCoordinator<T> {
    public enum Confirmation { ACCEPT, REJECT, WAIT }
    public interface Lane<T> {
        boolean available(long limit);
        List<T> sample();
        default List<T> sample(long verifiedUpper) { return sample(); }
        default boolean backgroundAllowed() { return true; }
        default long pendingLowerBound() { return 0; }
        default boolean budgetLimited(long maximum) { return false; }
    }
    private record Packet<T>(List<T> values, long remainingBound) {}
    private final class Work {
        final Lane<T> lane;
        final BatchMailbox<Packet<T>> output = new BatchMailbox<>();
        final AtomicBoolean running = new AtomicBoolean();
        volatile long remaining;
        volatile Throwable failure;
        int scheduledStep;
        Work(Lane<T> lane) { this.lane = lane; remaining = lane.pendingLowerBound(); }
    }
    private final List<Work> work = new ArrayList<>();
    private final ToLongFunction<T> distance;
    private ToLongFunction<T> candidateBound;
    private Function<T, Confirmation> confirmation;
    private Function<T, T> resolved = Function.identity();
    private Comparator<T> order;
    private PriorityQueue<T> pending;
    private final boolean async;
    private final long maximum;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicLong priority = new AtomicLong();
    private final AtomicLong verifiedUpper = new AtomicLong();
    private final AtomicLong workerNanos = new AtomicLong(), submitted = new AtomicLong(), count = new AtomicLong();
    private volatile boolean done;
    private boolean confirming;
    private T accepted, result;
    private int batchSize = 64;
    private long batchNanos = 4_000_000;
    private boolean eagerCandidates = true;
    private boolean unconditionalConfirmation;
    private boolean customVerification;
    private int schedulingStep;
    public ScanCoordinator(List<Lane<T>> lanes, ToLongFunction<T> distance, Predicate<T> confirm, long maximum, boolean async) {
        this.distance = distance; candidateBound = distance; this.maximum = maximum; this.async = async;
        confirmation = candidate -> confirm.test(candidate) ? Confirmation.ACCEPT : Confirmation.REJECT;
        order = Comparator.comparingLong(distance); rebuild(); priority.set(maximum); verifiedUpper.set(maximum);
        lanes.forEach(lane -> work.add(new Work(lane)));
    }
    public boolean step(ExecutorService executor) throws Exception {
        if (done || cancelled.get()) return false;
        for (Work item : work) if (item.failure != null) throw new ExecutionException(item.failure);
        boolean progress = false;
        // 队列里的 packet 在主线程消费之前，旧下界一直保留，覆盖全部在途结果。
        for (Work item : work) {
            Packet<T> packet = item.output.poll();
            if (packet == null) continue;
            pending.addAll(packet.values); item.remaining = packet.remainingBound; progress = true;
        }
        for (Work item : work) if (!item.running.get() && item.output.isEmpty())
            item.remaining = item.lane.pendingLowerBound();
        long upper = accepted == null ? maximum : distance.applyAsLong(accepted);
        T next = pending.peek();
        if (next != null && candidateBound.applyAsLong(next) <= upper) {
            confirming = true;
            Confirmation outcome = confirmation.apply(next);
            if (outcome != Confirmation.WAIT) {
                pending.remove(); progress = true;
                if (outcome == Confirmation.ACCEPT) {
                    T actual = resolved.apply(next);
                    if (distance.applyAsLong(actual) <= maximum && (accepted == null || order.compare(actual, accepted) < 0))
                        { accepted = actual; verifiedUpper.accumulateAndGet(distance.applyAsLong(actual), Math::min); }
                }
            }
        }
        upper = accepted == null ? maximum : distance.applyAsLong(accepted);
        if (accepted != null && unresolvedBound() > upper) {
            result = accepted; done = true; return true;
        }
        long preferred = pending.isEmpty() ? upper : Math.min(upper, Math.max(candidateBound.applyAsLong(pending.peek()),
                distance.applyAsLong(pending.peek())));
        priority.set(preferred);
        // 按各分区的下界调度；线程池拒绝后仍等待下一轮，不在调用线程执行后台任务。
        int turn = ++schedulingStep;
        for (int pass = 0; pass < work.size(); pass++) {
            Work item = null;
            for (Work possible : work) if (possible.scheduledStep != turn && !possible.running.get() && possible.output.isEmpty()
                    && possible.lane.available(preferred) && (item == null || possible.remaining < item.remaining)) item = possible;
            if (item == null) break;
            item.scheduledStep = turn;
            if (async && item.lane.backgroundAllowed()) progress |= submit(item, executor);
            else {
                Packet<T> packet = sample(item, 1, false);
                pending.addAll(packet.values); item.remaining = packet.remainingBound;
                return true;
            }
        }
        if (work.stream().anyMatch(item -> item.running.get() || !item.output.isEmpty())) return progress;
        if (!pending.isEmpty() && candidateBound.applyAsLong(pending.peek()) <= upper) return progress;
        for (Work item : work) if (item.lane.available(upper)) { priority.set(upper); return progress; }
        // 空批次可能在上面的证明检查后完成；终止前再读取一次完整前沿。
        if (accepted != null && unresolvedBound() > upper) result = accepted;
        // 额度耗尽时仍有未处理下界，不能提交暂存结果；完整覆盖才算找到最近目标。
        done = true; return true;
    }
    /** 接受请求时只提交已准备好的后台工作；核对与兼容查询仍由 tick 的共享预算执行。 */
    public void prime(ExecutorService executor) {
        if (!async || done || cancelled.get()) return;
        for (Work item : work) if (item.lane.backgroundAllowed() && item.lane.available(maximum)) submit(item, executor);
    }
    private long unresolvedBound() {
        long bound = pending.isEmpty() ? Long.MAX_VALUE : candidateBound.applyAsLong(pending.peek());
        for (Work item : work) bound = Math.min(bound, item.remaining);
        return bound;
    }
    private boolean submit(Work item, ExecutorService executor) {
        if (!item.running.compareAndSet(false, true)) return false;
        Runnable task = new Runnable() {
            public void run() {
                boolean chained = false;
                try {
                    if (done || cancelled.get() || item.output.remainingCapacity() == 0) return;
                    Packet<T> packet = sample(item, batchSize, true);
                    if (done || cancelled.get()) return;
                    if (!packet.values.isEmpty()) {
                        if (!item.output.offer(packet)) throw new IllegalStateException("Concurrent lane producer");
                    } else if (item.output.isEmpty()) {
                        // 无候选批次不等待一个 tick；只有已发布候选需要保持消费屏障。
                        item.remaining = packet.remainingBound;
                    }
                    // 有限队列保留消费屏障，后台可继续覆盖较近范围，避免核对后再多等一 tick。
                    if (item.output.remainingCapacity() > 0 && item.lane.available(priority.get())) {
                        try { executor.execute(this); submitted.incrementAndGet(); chained = true; return; }
                        catch (RejectedExecutionException full) { /* 有限队列拥堵，下个 tick 再提交。 */ }
                    }
                } catch (Throwable error) { item.failure = error; }
                finally { if (!chained) item.running.set(false); }
            }
        };
        try { executor.execute(task); submitted.incrementAndGet(); return true; }
        catch (RejectedExecutionException full) { item.running.set(false); return false; }
    }
    private Packet<T> sample(Work item, int size, boolean worker) {
        long begin = System.nanoTime(); List<T> values = new ArrayList<>();
        try {
            for (int i = 0; i < size && !done && !cancelled.get() && !Thread.currentThread().isInterrupted()
                    && item.lane.available(priority.get()); i++) {
                for (T candidate : item.lane.sample(verifiedUpper.get())) if (candidateBound.applyAsLong(candidate) <= maximum) {
                    if (unconditionalConfirmation) {
                        // 群系的完整官方查询已经给出真实有效点；只保留批内最优点，不丢失可能被拒绝的结构。
                        verifiedUpper.accumulateAndGet(distance.applyAsLong(candidate), Math::min);
                        if (values.isEmpty()) values.add(candidate);
                        else if (order.compare(candidate, values.get(0)) < 0) values.set(0,candidate);
                    } else values.add(candidate);
                    count.incrementAndGet();
                    // 暂停更远工作以便先核对；此优先级不用于永久剪枝，拒绝后恢复。
                    priority.accumulateAndGet(Math.max(candidateBound.applyAsLong(candidate), distance.applyAsLong(candidate)), Math::min);
                }
                if (!values.isEmpty() && (eagerCandidates || distance.applyAsLong(values.get(values.size()-1)) == 0)
                        || System.nanoTime() - begin >= batchNanos) break;
            }
            return new Packet<>(values, item.lane.pendingLowerBound());
        } finally { if (worker) workerNanos.addAndGet(System.nanoTime() - begin); }
    }
    private void rebuild() {
        var queue = new PriorityQueue<T>(Comparator.comparingLong(candidateBound).thenComparing(order));
        if (pending != null) queue.addAll(pending); pending = queue;
    }
    public ScanCoordinator<T> withTieBreaker(Comparator<T> tie) { order = Comparator.comparingLong(distance).thenComparing(tie); rebuild(); return this; }
    private void customVerification() {
        if (unconditionalConfirmation) throw new IllegalStateException("Unconditional samples cannot reject or adjust anchors");
        customVerification=true;
    }
    public ScanCoordinator<T> withCandidateLowerBound(ToLongFunction<T> bound) { customVerification(); candidateBound = bound; rebuild(); return this; }
    public ScanCoordinator<T> withResolvedCandidate(Function<T,T> mapper) { customVerification(); resolved = mapper; return this; }
    public ScanCoordinator<T> withConfirmation(Function<T,Confirmation> confirm) { customVerification(); confirmation = confirm; return this; }
    public ScanCoordinator<T> withBatch(int size, long nanos) {
        if (size < 1 || nanos < 1) throw new IllegalArgumentException(); batchSize = size; batchNanos = nanos; return this;
    }
    public ScanCoordinator<T> withEagerCandidates(boolean eager) { eagerCandidates = eager; return this; }
    /** 仅供不需要区块核对、不会拒绝且不会修正锚点的真实采样结果使用。 */
    public ScanCoordinator<T> withUnconditionalConfirmation() {
        if (customVerification) throw new IllegalStateException("Custom verification requires main-thread confirmation");
        unconditionalConfirmation=true; confirmation=ignored->Confirmation.ACCEPT;
        candidateBound=distance; resolved=Function.identity(); rebuild(); return this;
    }
    public void cancel() { cancelled.set(true); }
    public boolean done() { return done; }
    public boolean cancelled() { return cancelled.get(); }
    public T result() { return result; }
    public boolean confirming() { return confirming; }
    public boolean budgetLimited() { return work.stream().anyMatch(item -> item.lane.budgetLimited(maximum)); }
    public boolean hasBackground() { return async && work.stream().anyMatch(item -> item.lane.backgroundAllowed()); }
    public boolean hasCompatibility() { return !async || work.stream().anyMatch(item -> !item.lane.backgroundAllowed()); }
    public long workerNanos() { return workerNanos.get(); }
    public long submittedBatches() { return submitted.get(); }
    public long candidateCount() { return count.get(); }
    public int coveredRadius() { return (int)Math.sqrt(Math.min(maximum, unresolvedBound())); }
}
