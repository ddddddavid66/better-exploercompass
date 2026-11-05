package com.chaosthedude.explorerscompass.unified.search;

import com.david.compass.runtime.WorkerPool;
import java.util.concurrent.ThreadPoolExecutor;

/** 过渡装配：所有搜索通过同一个中性核心线程池。 */
public final class SearchPool {
    private static volatile java.util.function.IntSupplier threads=()->Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors()/2));
    private static final WorkerPool WORKERS=new WorkerPool(SearchPool::parallelism);
    public static int parallelism(){return threads.getAsInt();}
    public static void configure(java.util.function.IntSupplier supplier){threads=java.util.Objects.requireNonNull(supplier);}
    private SearchPool() {}
    public static ThreadPoolExecutor executor() { return WORKERS.executor(); }
    public static void shutdown() { WORKERS.shutdown(); }
}
