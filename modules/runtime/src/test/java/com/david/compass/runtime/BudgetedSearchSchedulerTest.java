package com.david.compass.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BudgetedSearchSchedulerTest {
    @Test void preparationConsumesBudgetBeforeAnyLaneRuns() {
        var now=new AtomicLong(100);
        var scheduler=new BudgetedSearchScheduler(now::get);
        scheduler.run(scheduler.deadline(20,25), List.of(new BudgetedSearchScheduler.Lane(1,d->{fail();return false;})),
                d->{fail();return false;});
        assertEquals(115,scheduler.deadline(20,5));
    }
    @Test void waitingLanesYieldAndBackgroundUsesRemainder() {
        var now=new AtomicLong(); var scheduler=new BudgetedSearchScheduler(now::get);
        var order=new ArrayList<String>();
        scheduler.run(20,List.of(new BudgetedSearchScheduler.Lane(1,d->{order.add("waiting");return false;}),
                new BudgetedSearchScheduler.Lane(1,d->{order.add("second");return false;})),
                d->{order.add("background");return false;});
        assertEquals(List.of("waiting","second","waiting","second","background"),order);
    }
    @Test void tightBudgetsDoNotStarveLaterProducts() {
        var now=new AtomicLong(); var scheduler=new BudgetedSearchScheduler(now::get);
        var order=new ArrayList<Integer>(); var lanes=new ArrayList<BudgetedSearchScheduler.Lane>();
        for(int i=0;i<3;i++) { int lane=i; lanes.add(new BudgetedSearchScheduler.Lane(1,d->{order.add(lane);now.incrementAndGet();return true;})); }
        for(int i=0;i<3;i++) scheduler.run(scheduler.deadline(1,0),lanes,d->{fail();return false;});
        assertEquals(List.of(0,1,2),order);
    }
    @Test void noForegroundWorkAllowsBackground() {
        var now=new AtomicLong(); var scheduler=new BudgetedSearchScheduler(now::get);
        scheduler.run(3,List.of(),d->now.incrementAndGet()<3);
        assertEquals(3,now.get());
    }
}
