package com.chaosthedude.explorerscompass.unified.data;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import com.google.gson.JsonParser;

class SearchLogTest {
    @TempDir Path root;
    private SearchLogEntry entry(String id,long duration,long started,boolean complete) {
        return new SearchLogEntry(Map.of("requestId",id,"durationNanos",duration,"startedAt",started,"completed",complete,"dimensionName","阿比斯之渊（第1层）"));
    }
    @Test void boundsUseUtf8BytesAndStopBeforeOverflow() throws Exception {
        var record=entry("a",123,1,true);long size=(record.json()+"\n").getBytes(StandardCharsets.UTF_8).length;
        var notices=new CopyOnWriteArrayList<String>();Path file=root.resolve("cap/log.jsonl");
        try(var log=new AsyncSearchLog(file,size*2,1024,notices::add)) {
            log.append(record);log.append(record);log.append(record);log.flushAsync().get(5,TimeUnit.SECONDS);
            assertEquals(size*2,Files.size(file));assertTrue(log.paused());assertEquals(1,notices.size());
            Files.move(file,file.resolveSibling("archive.jsonl"));log.open();log.flushAsync().get(5,TimeUnit.SECONDS);
            assertFalse(log.paused());assertTrue(log.append(record));log.flushAsync().get(5,TimeUnit.SECONDS);
            assertEquals(size,Files.size(file));
        }
    }
    @Test void sortMergesChunksKeepsSnapshotAndLeavesIncompleteLast() throws Exception {
        Path file=root.resolve("search-history.jsonl");
        var records=List.of(entry("short",1,1,true),entry("long-old",900,1,true),entry("incomplete",99999,9,false),entry("long-new",900,5,true));
        Files.writeString(file,String.join("\n",records.stream().map(SearchLogEntry::json).toList())+"\n");
        long snapshot=Files.size(file);Files.writeString(file,entry("later",9999,10,true).json()+"\n",StandardOpenOption.APPEND);
        var sorted=SearchLogSort.sort(file,snapshot,100000,1);
        assertEquals(4,sorted.records());
        var ids=Files.readAllLines(sorted.file()).stream().map(s->JsonParser.parseString(s).getAsJsonObject().get("requestId").getAsString()).toList();
        assertEquals(List.of("long-new","long-old","short","incomplete"),ids);assertEquals(5,Files.readAllLines(file).size());
        assertEquals(2,Files.list(root).count());
    }
    @Test void sortSkipsDamageAndPreservesPreviousOutputOnLimitFailure() throws Exception {
        Path file=root.resolve("search-history.jsonl");Files.writeString(file,"broken\n"+entry("a",1,1,true).json()+"\n");
        var sorted=SearchLogSort.sort(file,Files.size(file),10000,16);assertEquals(1,sorted.skipped());
        String original=Files.readString(sorted.file());
        assertThrows(java.io.IOException.class,()->SearchLogSort.sort(file,Files.size(file),1,16));
        assertEquals(original,Files.readString(sorted.file()));
    }
    @Test void saturatedQueuePausesWithoutRunningIoOnCaller() throws Exception {
        var notices=new CopyOnWriteArrayList<String>();
        try(var log=new AsyncSearchLog(root.resolve("log.jsonl"),10000,2,notices::add)) {
            log.flushAsync().get(5,TimeUnit.SECONDS);
            var field=AsyncSearchLog.class.getDeclaredField("queue");field.setAccessible(true);
            @SuppressWarnings("unchecked") var queue=(ArrayBlockingQueue<Runnable>)field.get(log);
            var started=new CountDownLatch(1);var release=new CountDownLatch(1);
            queue.put(()->{started.countDown();try{release.await();}catch(InterruptedException ignored){}});
            assertTrue(started.await(5,TimeUnit.SECONDS));
            assertTrue(log.append(entry("a",1,1,true)));
            assertTrue(log.append(entry("b",2,2,true)));assertFalse(log.append(entry("c",3,3,true)));assertTrue(log.paused());assertEquals(1,notices.size());
            release.countDown();
        }
    }
    @Test void ioFailurePausesInsteadOfPropagatingToSearchCaller() throws Exception {
        Path parent=root.resolve("not-directory");Files.writeString(parent,"x");
        try(var log=new AsyncSearchLog(parent.resolve("log.jsonl"),1000,1024,message->{})) {
            assertTrue(log.append(entry("a",1,1,true)));log.flushAsync().get(5,TimeUnit.SECONDS);assertTrue(log.paused());
        }
    }
    @Test void existingHundredMegabyteFilePausesWithoutOverwrite() throws Exception {
        Path file=root.resolve("full.jsonl");try(var random=new java.io.RandomAccessFile(file.toFile(),"rw")){random.setLength(100_000_000);}
        var notices=new CopyOnWriteArrayList<String>();
        try(var log=new AsyncSearchLog(file,notices::add)) {
            log.open();log.flushAsync().get(5,TimeUnit.SECONDS);
            assertTrue(log.paused());assertFalse(log.append(entry("a",1,1,true)));assertEquals(100_000_000,Files.size(file));assertEquals(1,notices.size());
        }
    }
    @Test void modeCycleAndInvalidDimensionDoNotInventMinecraftNamespace() {
        assertEquals(LandingMode.SAFE_ONLY,LandingMode.DEFAULT.next());
        assertEquals(LandingMode.RELAXED_ONLY,LandingMode.SAFE_ONLY.next());assertEquals(LandingMode.DEFAULT,LandingMode.RELAXED_ONLY.next());
        assertNull(TargetDimension.parse(""));assertNull(TargetDimension.parse("minecraft:"));assertNull(TargetDimension.parse("bad id"));
        assertEquals("minecraft:overworld",TargetDimension.parse("minecraft:overworld").toString());
    }
}
