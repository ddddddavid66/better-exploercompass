package com.chaosthedude.explorerscompass.unified.creature.search;

import com.chaosthedude.explorerscompass.unified.creature.mixin.EntityIoWorkerAccessor;
import com.chaosthedude.explorerscompass.unified.creature.mixin.EntityRegionStorageAccessor;
import com.chaosthedude.explorerscompass.unified.creature.mixin.EntitySectionManagerAccessor;
import com.chaosthedude.explorerscompass.unified.creature.mixin.EntityStorageAccessor;
import com.chaosthedude.explorerscompass.unified.creature.mixin.ServerEntityManagerAccessor;

import com.david.compass.runtime.game.search.SearchPool;
import com.mojang.datafixers.util.Either;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.*;

/** 会话内的保存实体索引。所有缓存更新在服务端线程，I/O 只产生不可变记录。 */
public final class SavedCreatureIndex {
    public record Point(UUID uuid,ResourceLocation type,double x,double y,double z,ChunkPos source) implements SpatialGrid.Position {}
    private record Region(int x,int z) { ChunkPos origin(){return new ChunkPos(x*32,z*32);} }
    private record Dirty(ServerLevel level,ChunkPos chunk) {}
    private record Read(ChunkPos chunk,long version,CompletableFuture<List<Point>> future) {}
    private record Scan(State state,ChunkPos chunk,CompletableFuture<List<Point>> result) {}
    private static final Pattern REGION=Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final Map<ServerLevel,State> states=new WeakHashMap<>();
    private static final Set<Dirty> dirty=ConcurrentHashMap.newKeySet();
    private static final AtomicInteger inFlight=new AtomicInteger();
    private static final int MAX_READS=8,MAX_BUFFERED=64;
    private static final ArrayDeque<Scan> scheduled=new ArrayDeque<>();
    private static int peakReads;
    private static volatile boolean enabled;
    private SavedCreatureIndex() {}

    private static final class State {
        final IOWorker worker;final EntityIoWorkerAccessor io;final EntityRegionStorageAccessor storage;
        final Map<Long,List<Point>> chunks=new HashMap<>();
        final Map<Long,Long> versions=new HashMap<>();
        final Map<ResourceLocation,SpatialGrid<Point>> types=new HashMap<>();
        final Map<UUID,Point> byId=new HashMap<>();
        final Map<Long,Read> reading=new HashMap<>();
        final Set<UUID> destroyed=new HashSet<>();
        long revision;volatile boolean closed;
        State(ServerLevel level){
            var manager=((ServerEntityManagerAccessor)level).unified$entityManager();
            var persistent=((EntitySectionManagerAccessor)manager).unified$permanentStorage();
            if(!(persistent instanceof EntityStorage))throw new IllegalStateException("实体存储类型未经审核："+persistent.getClass().getName());
            worker=((EntityStorageAccessor)persistent).unified$worker();io=(EntityIoWorkerAccessor)worker;storage=(EntityRegionStorageAccessor)(Object)io.unified$storage();
        }
        void removeChunk(long chunk){
            var previous=chunks.remove(chunk);if(previous==null)return;
            for(var p:previous)if(byId.get(p.uuid())==p){byId.remove(p.uuid());types.get(p.type()).remove(p.uuid());}
            revision++;
        }
        void accept(Read read,List<Point> values){
            if(closed || versions.getOrDefault(read.chunk().toLong(),0L)!=read.version())return;
            long key=read.chunk().toLong();removeChunk(key);chunks.put(key,values);
            for(var p:values)if(!destroyed.contains(p.uuid())){
                var old=byId.put(p.uuid(),p);if(old!=null && !old.type().equals(p.type()))types.get(old.type()).remove(p.uuid());
                types.computeIfAbsent(p.type(),k->new SpatialGrid<>()).put(p);
            }
            revision++;
        }
        CompletableFuture<List<Region>> regions(BlockPos origin,int radius){
            // 目录和 pendingWrites 的快照也在同一个邮箱中取，绝不另开正在写入的区域文件。
            return io.unified$submit(()->{
                try{
                    var result=new HashSet<Region>();Path folder=storage.unified$folder();
                    if(Files.isDirectory(folder))try(var files=Files.newDirectoryStream(folder,"r.*.*.mca")){
                        for(var file:files){var match=REGION.matcher(file.getFileName().toString());if(match.matches())result.add(new Region(Integer.parseInt(match.group(1)),Integer.parseInt(match.group(2))));}
                    }
                    for(var chunk:io.unified$pendingWrites().keySet())result.add(new Region(chunk.x>>5,chunk.z>>5));
                    return Either.left(result.stream().filter(r->lower(r,origin)<=(double)radius*radius)
                        .sorted(Comparator.<Region>comparingDouble(r->lower(r,origin)).thenComparingInt(Region::x).thenComparingInt(Region::z)).toList());
                }catch(Exception e){return Either.right(e);}
            });
        }
        CompletableFuture<List<ChunkPos>> occupied(Region region,BlockPos origin,int radius){
            return io.unified$submit(()->{
                try{
                    var result=new HashSet<ChunkPos>();Path file=storage.unified$folder().resolve("r."+region.x()+"."+region.z()+".mca");
                    // getRegionFile 会创建文件，故只允许已经存在的区域进入该分支。
                    if(Files.isRegularFile(file)){
                        RegionFile existing=storage.unified$getRegionFile(region.origin());
                        for(int x=0;x<32;x++)for(int z=0;z<32;z++){
                            var chunk=new ChunkPos(region.x()*32+x,region.z()*32+z);if(existing.hasChunk(chunk))result.add(chunk);
                        }
                    }
                    for(var chunk:io.unified$pendingWrites().keySet())if(chunk.x>>5==region.x() && chunk.z>>5==region.z())result.add(chunk);
                    return Either.left(result.stream().filter(c->lower(c,origin)<=(double)radius*radius)
                        .sorted(Comparator.<ChunkPos>comparingDouble(c->lower(c,origin)).thenComparingInt(c->c.x).thenComparingInt(c->c.z)).toList());
                }catch(Exception e){return Either.right(e);}
            });
        }
        boolean read(ChunkPos chunk){
            long key=chunk.toLong();if(chunks.containsKey(key) || reading.containsKey(key))return true;
            if(reading.size()>=MAX_BUFFERED)return false;
            var future=new CompletableFuture<List<Point>>();
            reading.put(key,new Read(chunk,versions.getOrDefault(key,0L),future));
            enqueue(new Scan(this,chunk,future));return true;
        }
        void consume(long deadline)throws Exception{
            var iterator=reading.values().iterator();
            while(iterator.hasNext() && System.nanoTime()<deadline){var read=iterator.next();if(!read.future().isDone())continue;iterator.remove();accept(read,read.future().get());}
        }
    }

    public static final class Cursor {
        private final State state;private final ResourceLocation type;private final BlockPos origin;private final int radius;
        private final CompletableFuture<List<Region>> listing;
        private List<Region> regions;private int regionAt;private CompletableFuture<List<ChunkPos>> occupancy;
        private final ArrayDeque<ChunkPos> work=new ArrayDeque<>();private final Set<Long> counted=new HashSet<>();
        private final Set<UUID> examined=new HashSet<>();private final ArrayDeque<Point> candidates=new ArrayDeque<>();
        private Future<List<Point>> nearest;private long revision=-1;private boolean queryDirty=true;
        private long scanned,total;private boolean closed;
        Cursor(State state,ResourceLocation type,BlockPos origin,int radius){this.state=state;this.type=type;this.origin=origin;this.radius=radius;listing=state.regions(origin,radius);}
        public void step(long deadline)throws Exception{
            drain();state.consume(deadline);if(closed)return;
            if(regions==null){if(!listing.isDone())return;regions=listing.get();}
            if(occupancy!=null && occupancy.isDone()){var chunks=occupancy.get();occupancy=null;work.addAll(chunks);total+=chunks.size();regionAt++;}
            while(!work.isEmpty() && System.nanoTime()<deadline){
                var chunk=work.peek();long key=chunk.toLong();
                if(state.chunks.containsKey(key)){work.remove();if(counted.add(key))scanned++;continue;}
                if(!state.read(chunk))break;
                // 在途区块保留在本游标中，直到真实扫描完成才计入进度。
                work.remove();work.addLast(chunk);if(state.reading.size()>=MAX_BUFFERED)break;
            }
            if(work.isEmpty() && occupancy==null && regionAt<regions.size())occupancy=state.occupied(regions.get(regionAt),origin,radius);
            if(nearest!=null && nearest.isDone()) {candidates.addAll(nearest.get());nearest=null;}
            if(nearest==null && candidates.isEmpty() && (queryDirty || revision!=state.revision)){
                var grid=state.types.get(type);var snapshot=grid==null?new SpatialGrid.Snapshot<Point>(List.of()):grid.snapshot();
                var excluded=Set.copyOf(examined);
                try{nearest=SearchPool.executor().submit(()->SpatialGrid.nearest(snapshot,origin.getX(),origin.getZ(),radius,16,p->!excluded.contains(p.uuid())));revision=state.revision;queryDirty=false;}catch(RejectedExecutionException busy){/* 后续时间片重试，不转交主线程。 */}
            }
        }
        public Point poll(){while(!candidates.isEmpty()){var p=candidates.removeFirst();if(examined.add(p.uuid()))return p;}return null;}
        public void rejected(){queryDirty=true;}
        public boolean exhausted(){return regions!=null && regionAt>=regions.size() && occupancy==null && work.isEmpty() && nearest==null && candidates.isEmpty() && revision==state.revision && !queryDirty;}
        public long scanned(){return scanned;}public long total(){return total;}
        public int regionsDone(){return regionAt;}public int regionsTotal(){return regions==null?0:regions.size();}
        public void close(){closed=true;if(nearest!=null)nearest.cancel(true);candidates.clear();}
    }
    private static synchronized void enqueue(Scan scan){scheduled.addLast(scan);pump();}
    private static synchronized void pump(){
        while(inFlight.get()<MAX_READS && !scheduled.isEmpty()){
            var scan=scheduled.removeFirst();
            if(scan.state().closed){scan.result().cancel(false);continue;}
            int active=inFlight.incrementAndGet();peakReads=Math.max(peakReads,active);
            // 1.20.1 的 BACKGROUND 优先级为1；让正常加载和已有保存任务先行，防止连续扫描饿死落盘。
            scan.state().io.unified$mailbox().tell(new net.minecraft.util.thread.StrictQueue.IntRunnable(1,()->read(scan)));
        }
    }
    private static void read(Scan scan){
        if(scan.state().closed){scan.result().cancel(false);inFlight.decrementAndGet();pump();return;}
        var visitor=new SavedEntityVisitor();
        try{
            scan.state().worker.scanChunk(scan.chunk(),visitor).whenComplete((v,error)->{
                try{if(error==null)scan.result().complete(visitor.points(scan.chunk()));else scan.result().completeExceptionally(error);}
                catch(Throwable parse){scan.result().completeExceptionally(parse);}
                finally{inFlight.decrementAndGet();pump();}
            });
        }catch(Throwable error){inFlight.decrementAndGet();scan.result().completeExceptionally(error);pump();}
    }
    public static synchronized int peakReads(){return peakReads;}
    public static Cursor open(ServerLevel level,ResourceLocation type,BlockPos origin,int radius){enabled=true;drain();return new Cursor(states.computeIfAbsent(level,State::new),type,origin,radius);}
    /** 已就绪区块的真实快照直接复用，晚到旧I/O不能覆盖。 */
    public static void seed(ServerLevel level,ChunkPos chunk,List<Point> points){enabled=true;drain();State state=states.computeIfAbsent(level,State::new);long version=state.versions.merge(chunk.toLong(),1L,Long::sum);state.accept(new Read(chunk,version,CompletableFuture.completedFuture(points)),List.copyOf(points));}
    public static void stored(ServerLevel level,ChunkPos chunk){if(enabled)dirty.add(new Dirty(level,chunk));}
    private static void drain(){int count=0;var iterator=dirty.iterator();while(iterator.hasNext() && count++<128){var event=iterator.next();iterator.remove();var state=states.get(event.level());if(state==null)continue;long key=event.chunk().toLong();state.versions.merge(key,1L,Long::sum);state.removeChunk(key);}}
    public static void destroyed(ServerLevel level,UUID id){var state=states.get(level);if(state!=null){state.destroyed.add(id);var old=state.byId.remove(id);if(old!=null){state.types.get(old.type()).remove(id);state.revision++;}}}
    public static void clear(){enabled=false;for(var state:states.values())state.closed=true;states.clear();dirty.clear();}
    public static synchronized int inFlight(){return inFlight.get()+scheduled.size();}
    private static double lower(Region region,BlockPos origin){return lower((long)region.x()*512,(long)region.z()*512,512,origin);}
    private static double lower(ChunkPos chunk,BlockPos origin){return lower(chunk.getMinBlockX(),chunk.getMinBlockZ(),16,origin);}
    private static double lower(long x,long z,int size,BlockPos origin){double dx=Math.max(0,Math.max(x-origin.getX(),origin.getX()-x-size)),dz=Math.max(0,Math.max(z-origin.getZ(),origin.getZ()-z-size));return dx*dx+dz*dz;}
}
