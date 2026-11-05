package com.chaosthedude.explorerscompass.unified.search;

import com.david.compass.runtime.WorkerPool;
import java.util.concurrent.ThreadPoolExecutor;

/** 过渡装配：所有搜索通过同一个中性核心线程池。 */
public final class SearchPool {
    private static final WorkerPool WORKERS=new WorkerPool(SearchConfig::threads);
    private SearchPool() {}
    public static ThreadPoolExecutor executor() { return WORKERS.executor(); }
    public static void shutdown() { WORKERS.shutdown(); }
}
