package com.chaosthedude.explorerscompass.unified.data;

import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 分段外部归并，只读取命令提交时的完整字节快照，不阻塞追加线程。 */
public final class SearchLogSort {
    public record Result(Path file,long records,long skipped) {}
    private record Row(String text,boolean complete,long duration,long started,String id) {}
    private static final Comparator<Row> ORDER=Comparator.<Row,Boolean>comparing(r->!r.complete)
            .thenComparing(Comparator.comparingLong(Row::duration).reversed())
            .thenComparing(Comparator.comparingLong(Row::started).reversed()).thenComparing(Row::id);
    private SearchLogSort() {}
    private static Row parse(String text) {
        var object=JsonParser.parseString(text).getAsJsonObject();
        boolean complete=object.get("completed").getAsBoolean();
        return new Row(text,complete,complete?object.get("durationNanos").getAsLong():-1,
                object.get("startedAt").getAsLong(),object.get("requestId").getAsString());
    }
    public static Result sort(Path source,long snapshot,long maximum,int chunkBytes) throws IOException {
        Path temporary=Files.createTempDirectory(source.getParent(),"search-sort-");
        List<Path> parts=new ArrayList<>();long skipped=0,records=0;
        try {
            List<Row> rows=new ArrayList<>();int bytes=0;
            // 限制底层字节数；后续追加的日志不会进入本次排序。
            try(var reader=new BufferedReader(new InputStreamReader(new LimitedInputStream(Files.newInputStream(source),snapshot),StandardCharsets.UTF_8))) {
                String line;
                while((line=reader.readLine())!=null) {
                    try {rows.add(parse(line));bytes+=line.getBytes(StandardCharsets.UTF_8).length+1;records++;}
                    catch(RuntimeException malformed){skipped++;continue;}
                    if(bytes>=chunkBytes){parts.add(writePart(temporary,parts.size(),rows));rows.clear();bytes=0;}
                }
            }
            if(!rows.isEmpty())parts.add(writePart(temporary,parts.size(),rows));
            Path output=temporary.resolve("merged.jsonl");merge(parts,output,maximum);
            Path destination=source.resolveSibling("search-history.sorted.jsonl");
            try {Files.move(output,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException ignored){Files.move(output,destination,StandardCopyOption.REPLACE_EXISTING);}
            return new Result(destination,records,skipped);
        } finally {
            try(var paths=Files.walk(temporary)) {
                for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);
            }
        }
    }
    private static Path writePart(Path directory,int index,List<Row> rows) throws IOException {
        rows.sort(ORDER);Path part=directory.resolve(index+".jsonl");
        try(var writer=Files.newBufferedWriter(part,StandardCharsets.UTF_8)) {
            for(Row row:rows){writer.write(row.text);writer.write('\n');}
        }
        return part;
    }
    private static final class Cursor implements AutoCloseable {
        final BufferedReader reader;Row row;
        Cursor(Path path) throws IOException {reader=Files.newBufferedReader(path,StandardCharsets.UTF_8);advance();}
        boolean advance() throws IOException {String line=reader.readLine();row=line==null?null:parse(line);return row!=null;}
        public void close() throws IOException {reader.close();}
    }
    private static void merge(List<Path> parts,Path output,long maximum) throws IOException {
        List<Cursor> opened=new ArrayList<>();
        var queue=new PriorityQueue<Cursor>((a,b)->ORDER.compare(a.row,b.row));long bytes=0;
        try(var writer=new BufferedOutputStream(Files.newOutputStream(output))) {
            for(Path part:parts){var cursor=new Cursor(part);opened.add(cursor);if(cursor.row!=null)queue.add(cursor);}
            while(!queue.isEmpty()) {
                Cursor cursor=queue.remove();byte[] line=(cursor.row.text+"\n").getBytes(StandardCharsets.UTF_8);
                if(bytes+line.length>maximum)throw new IOException("排序输出达到 100 MB 上限，原排序文件保留");
                writer.write(line);bytes+=line.length;if(cursor.advance())queue.add(cursor);
            }
        } finally {for(Cursor cursor:opened)cursor.close();}
    }
    private static final class LimitedInputStream extends FilterInputStream {
        long remaining;
        LimitedInputStream(InputStream source,long remaining){super(source);this.remaining=remaining;}
        @Override public int read() throws IOException {if(remaining<=0)return -1;int b=super.read();if(b>=0)remaining--;return b;}
        @Override public int read(byte[] b,int off,int length) throws IOException {
            if(remaining<=0)return -1;int count=super.read(b,off,(int)Math.min(remaining,length));if(count>0)remaining-=count;return count;
        }
    }
}
