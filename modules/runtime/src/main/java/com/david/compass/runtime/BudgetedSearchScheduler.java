package com.david.compass.runtime;

import java.util.List;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;

/** 公共协作调度：不识别物品、玩家或任何产品实现。仅由服务器线程调用。 */
public final class BudgetedSearchScheduler {
    public record Lane(int pending, LongPredicate step) {
        public Lane {
            if (pending < 0) throw new IllegalArgumentException("Negative pending count");
            java.util.Objects.requireNonNull(step);
        }
    }
    private final LongSupplier clock;
    private int cursor;
    public BudgetedSearchScheduler(LongSupplier clock) { this.clock=clock; }
    public long deadline(long budget,long preparation) {
        return clock.getAsLong()+Math.max(0,budget-Math.max(0,preparation));
    }
    public void run(long deadline,List<Lane> foreground,LongPredicate background) {
        // 无进展的轮转有界；跨 tick 记住游标，避免首路每次用完预算。
        long attempts=foreground.stream().mapToLong(Lane::pending).sum()*foreground.size();
        long idle=0;
        while (idle<attempts && clock.getAsLong()-deadline<0) {
            var lane=foreground.get(cursor%foreground.size());
            cursor=(cursor+1)%foreground.size();
            if (lane.pending()>0 && lane.step().test(deadline)) idle=0;
            else idle++;
        }
        while (clock.getAsLong()-deadline<0 && background.test(deadline)) { /* 只用剩余预算。 */ }
    }
}
