package com.chaosthedude.explorerscompass.unified.search;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class BatchMailboxTest {
    @Test void capacityAndWraparoundPreserveOrder() {
        var box = new BatchMailbox<Integer>();
        for (int round=0;round<100;round++) {
            assertNull(box.poll()); assertTrue(box.isEmpty());
            for(int i=0;i<4;i++) assertTrue(box.offer(round*4+i));
            assertFalse(box.offer(-1)); assertEquals(0,box.remainingCapacity());
            for(int i=0;i<4;i++) assertEquals(round*4+i,box.poll());
            assertEquals(4,box.remainingCapacity());
        }
    }
    @Test void producerAndConsumerPublishEveryPacketExactlyOnce() throws Exception {
        var box = new BatchMailbox<Integer>(); var pool=Executors.newFixedThreadPool(2);
        try {
            var producer=pool.submit(()->{for(int i=0;i<200000;i++) while(!box.offer(i)) {if(Thread.currentThread().isInterrupted())return;Thread.onSpinWait();}});
            var consumer=pool.submit(()->{for(int i=0;i<200000;i++) {
                Integer item; while((item=box.poll())==null) {if(Thread.currentThread().isInterrupted())return;Thread.onSpinWait();} assertEquals(i,item);
            }});
            producer.get(10,TimeUnit.SECONDS); consumer.get(10,TimeUnit.SECONDS); assertTrue(box.isEmpty());
        } finally { pool.shutdownNow(); }
    }
}
