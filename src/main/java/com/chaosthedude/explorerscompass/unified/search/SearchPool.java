package com.chaosthedude.explorerscompass.unified.search;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class SearchPool {
    private static ThreadPoolExecutor pool;
    private static final AtomicInteger serial = new AtomicInteger();
    private SearchPool() {}
    public static ThreadPoolExecutor executor() {
        if (pool == null) {
            int threads = SearchConfig.threads();
            pool = new ThreadPoolExecutor(threads, threads, 60, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(256), runnable -> {
                        Thread thread = new Thread(runnable, "compassfilters-search-" + serial.incrementAndGet());
                        thread.setDaemon(true); return thread;
                    }, new ThreadPoolExecutor.AbortPolicy());
            pool.allowCoreThreadTimeOut(true);
        }
        return pool;
    }
    public static void shutdown() {
        if (pool != null) { pool.shutdownNow(); pool = null; }
    }
}
