package com.david.compass.runtime;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/** 单一共享线程池，不在线程饱和时退回服务器线程执行。 */
public final class WorkerPool {
    private final IntSupplier threads;
    private final AtomicInteger serial=new AtomicInteger();
    private ThreadPoolExecutor pool;
    public WorkerPool(IntSupplier threads) { this.threads=java.util.Objects.requireNonNull(threads); }
    public synchronized ThreadPoolExecutor executor() {
        if (pool == null) {
            int count=threads.getAsInt();
            pool=new ThreadPoolExecutor(count,count,60,TimeUnit.SECONDS,new ArrayBlockingQueue<>(256), job->{
                // 保留后台世界访问保护所识别的线程名前缀。
                var thread=new Thread(job,"compassfilters-search-"+serial.incrementAndGet());
                thread.setDaemon(true);return thread;
            },new ThreadPoolExecutor.AbortPolicy());
            pool.allowCoreThreadTimeOut(true);
        }
        return pool;
    }
    public synchronized void shutdown() {
        if(pool!=null){pool.shutdownNow();pool=null;}
    }
}
