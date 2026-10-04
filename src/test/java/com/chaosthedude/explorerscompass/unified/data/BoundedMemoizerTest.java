package com.chaosthedude.explorerscompass.unified.data;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoundedMemoizerTest {
    @Test void repeatedAndConcurrentKeysReuseOneGenerationResult() throws Exception {
        var cache=new BoundedMemoizer<Integer,Integer>(32);
        var calls=new AtomicInteger();var pool=Executors.newFixedThreadPool(4);
        try {
            var tasks=new java.util.ArrayList<Future<Integer>>();
            for(int i=0;i<20;i++)tasks.add(pool.submit(()->cache.get(42,k->calls.incrementAndGet())));
            for(var task:tasks)assertEquals(1,task.get());
            assertEquals(1,calls.get());assertEquals(1,cache.size());
        } finally { pool.shutdownNow(); }
    }
    @Test void capacityIsGlobalAndFailuresReleaseTheirSlot() throws Exception {
        var cache=new BoundedMemoizer<Integer,Integer>(3);
        assertThrows(IllegalStateException.class,()->cache.get(100,k->{throw new IllegalStateException();}));
        var pool=Executors.newFixedThreadPool(8);
        try {
            var tasks=new java.util.ArrayList<Future<Integer>>();
            for(int i=0;i<100;i++){int key=i;tasks.add(pool.submit(()->cache.get(key,k->k)));}
            for(int i=0;i<100;i++)assertEquals(i,tasks.get(i).get());
            assertEquals(3,cache.size());
        } finally { pool.shutdownNow(); }
    }
    @Test void newSnapshotDoesNotReuseStaleOrFailedResults() {
        var old=new BoundedMemoizer<Integer,String>(1);assertEquals("old",old.get(1,k->"old"));
        var fresh=new BoundedMemoizer<Integer,String>(1);assertEquals("new",fresh.get(1,k->"new"));
        assertThrows(NullPointerException.class,()->new BoundedMemoizer<Integer,String>(1).get(1,k->null));
    }
    @Test void capacityEvictsEarlyKeysInsteadOfPinningThemForever() {
        var cache = new BoundedMemoizer<Integer,Integer>(2);
        cache.remember(1, 1); cache.remember(2, 2); cache.remember(3, 3);
        assertEquals(2, cache.size()); assertNull(cache.peek(1)); assertEquals(3, cache.peek(3));
    }
}
