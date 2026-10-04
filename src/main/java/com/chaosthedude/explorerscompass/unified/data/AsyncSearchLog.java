package com.chaosthedude.explorerscompass.unified.data;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 有界、非阻塞事件入口。磁盘写入和排序使用不同线程，排序不占用追加队列。 */
public final class AsyncSearchLog implements AutoCloseable {
    public static final long MAXIMUM=100_000_000L;
    private final Path file;
    private final long maximum;
    private final Consumer<String> report;
    private final ArrayBlockingQueue<Runnable> queue;
    private final Thread writer;
    private final ExecutorService sorter=Executors.newSingleThreadExecutor(r->daemon(r,"Compass-log-sort"));
    private final AtomicBoolean sorting=new AtomicBoolean(),warned=new AtomicBoolean();
    private volatile boolean running=true,paused,blocked;
    private volatile long bytes;
    private OutputStream stream;
    public AsyncSearchLog(Path file,Consumer<String> report){this(file,MAXIMUM,1024,report);}
    public AsyncSearchLog(Path file,long maximum,int capacity,Consumer<String> report) {
        this.file=file.toAbsolutePath();this.maximum=maximum;this.report=report;queue=new ArrayBlockingQueue<>(capacity);
        writer=daemon(this::run,"Compass-log-writer");writer.start();
        submit(()->{try {bytes=Files.exists(this.file)?Files.size(this.file):0;}catch(IOException error){pause(error.getMessage());}});
    }
    private static Thread daemon(Runnable runnable,String name) {Thread thread=new Thread(runnable,name);thread.setDaemon(true);thread.setPriority(Thread.MIN_PRIORITY);return thread;}
    public Path file(){return file;}
    public long bytes(){return bytes;}
    public boolean paused(){return paused;}
    public boolean append(SearchLogEntry entry){return !paused && submit(()->write(entry));}
    public void open() {submit(()->{
        try {closeStream();bytes=Files.exists(file)?Files.size(file):0;warned.set(false);paused=false;blocked=false;
            if(bytes>=maximum)pause("日志达到 100 MB 上限；请移走原文件后重新开启");
        }catch(IOException error){pause(error.getMessage());}
    });}
    private boolean submit(Runnable runnable) {
        if(!running)return false;
        if(queue.offer(runnable))return true;
        paused=true;if(warned.compareAndSet(false,true))report.accept("日志队列已满，已暂停收录；搜索继续正常运行");return false;
    }
    private void pause(String reason){paused=true;blocked=true;if(warned.compareAndSet(false,true))report.accept(reason);}
    private void run() {
        long flushAt=System.nanoTime()+2_000_000_000L;
        try {
            while(running || !queue.isEmpty()) {
                try {Runnable work=queue.poll(100,TimeUnit.MILLISECONDS);if(work!=null)work.run();}
                catch(InterruptedException ignored){}
                if(System.nanoTime()>=flushAt){flush();flushAt=System.nanoTime()+2_000_000_000L;}
            }
        }catch(RuntimeException error){pause("日志线程失败："+error.getMessage());}
        finally {try{closeStream();}catch(IOException error){pause(error.getMessage());}}
    }
    private void write(SearchLogEntry entry) {
        if(blocked)return;
        try {
            byte[] line=(entry.json()+"\n").getBytes(StandardCharsets.UTF_8);
            if(stream==null) {
                Files.createDirectories(file.getParent());bytes=Files.exists(file)?Files.size(file):0;
                // 崩溃留下的不完整末行保留；分隔后新的完整记录仍可排序。
                if(bytes>0 && bytes<maximum)try(var input=new RandomAccessFile(file.toFile(),"r")) {
                    input.seek(bytes-1);if(input.read()!=10){Files.write(file,new byte[]{10},StandardOpenOption.APPEND);bytes++;}
                }
                stream=new BufferedOutputStream(Files.newOutputStream(file,StandardOpenOption.CREATE,StandardOpenOption.APPEND));
            }
            if(bytes+line.length>maximum){flush();pause("日志达到 100 MB 上限，已暂停记录；请移走原文件后重新开启");return;}
            stream.write(line);bytes+=line.length;
            if(bytes==maximum){flush();pause("日志达到 100 MB 上限，已暂停记录");}
        }catch(IOException error){pause("日志写入失败："+error.getMessage());}
    }
    private void flush(){try{if(stream!=null)stream.flush();}catch(IOException error){pause(error.getMessage());}}
    private void closeStream() throws IOException {if(stream!=null){stream.close();stream=null;}}
    public CompletableFuture<Long> flushAsync() {
        var result=new CompletableFuture<Long>();
        if(!submit(()->{flush();result.complete(bytes);})){result.completeExceptionally(new IOException("日志队列不可用"));}
        return result;
    }
    public boolean sort() {
        if(!sorting.compareAndSet(false,true))return false;
        flushAsync().whenComplete((snapshot,error)->{
            if(error!=null){sorting.set(false);report.accept("排序失败："+error.getMessage());return;}
            sorter.execute(()->{
                try {
                    if(!Files.exists(file)){report.accept("暂无日志可排序");return;}
                    var result=SearchLogSort.sort(file,snapshot,maximum,8*1024*1024);
                    report.accept("排序完成："+result.records()+" 条，跳过 "+result.skipped()+" 条损坏记录；"+result.file());
                }catch(IOException|RuntimeException failure){report.accept("排序失败："+failure.getMessage());}
                finally{sorting.set(false);}
            });
        });
        return true;
    }
    public void close() {
        running=false;writer.interrupt();sorter.shutdown();
        try{writer.join(2000);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}
    }
}
