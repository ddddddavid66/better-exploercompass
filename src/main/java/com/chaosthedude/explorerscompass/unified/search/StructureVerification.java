package com.chaosthedude.explorerscompass.unified.search;

import com.chaosthedude.explorerscompass.unified.mixin.ChunkMapAccessor;
import com.chaosthedude.explorerscompass.unified.mixin.StructureCheckAccessor;
import com.chaosthedude.explorerscompass.unified.mixin.StructureManagerAccessor;
import com.mojang.datafixers.util.Either;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;

/** 真实结构核对只轮询已调度的起始阶段；从不阻塞服务端线程等待区块。 */
public final class StructureVerification {
    private static final TicketType<UUID> TICKET = TicketType.create("unified_structure_check", Comparator.<UUID>naturalOrder());
    private static final int TICKET_LEVEL = ChunkLevel.byStatus(ChunkStatus.STRUCTURE_STARTS);
    private static final java.util.Map<ServerLevel,java.util.Map<Long,Flight>> FLIGHTS=new java.util.IdentityHashMap<>();
    private static final class Flight {
        final ServerLevel level;final net.minecraft.world.level.ChunkPos chunk;final GenerationStamp stamp;
        final UUID id=UUID.randomUUID();int refs;boolean ticket;
        CompletableFuture<Either<ChunkAccess,ChunkHolder.ChunkLoadingFailure>> future;
        Flight(ServerLevel level,net.minecraft.world.level.ChunkPos chunk){this.level=level;this.chunk=chunk;stamp=GenerationStamp.capture(level);}
    }
    private Flight flight;
    private boolean background;
    private static int tickets;
    private final ServerLevel level;
    private final BlockPos origin;
    private SearchCandidate attempt, confirmed;
    private int confirmations, chunkRequests, sharedAcquires;
    private long mainNanos, waitStarted, waitedNanos;

    public StructureVerification(ServerLevel level, BlockPos origin) { this.level = level; this.origin = origin.immutable(); }

    public ScanCoordinator.Confirmation verify(SearchCandidate candidate) {return verify(candidate,false);}
    public ScanCoordinator.Confirmation verify(SearchCandidate candidate,boolean background) {
        this.background=background;
        requireMainThread();
        long begin = System.nanoTime();
        try { return advance(candidate); }
        catch (RuntimeException | Error error) { release(); throw error; }
        finally { mainNanos += System.nanoTime() - begin; }
    }

    private ScanCoordinator.Confirmation advance(SearchCandidate candidate) {
        if (!candidate.equals(attempt)) {
            // 此对象是稳定候选句柄；旧单候选调用方切换目标时仍先释放旧引用。
            release(); attempt = candidate; confirmations++;
            if (candidate.placement().getClass().getName().equals(GelGridPlacement.TYPE)
                    && !candidate.placement().isStructureChunk(level.getChunkSource().getGeneratorState(),
                            candidate.chunk().x, candidate.chunk().z)) return reject();
            ChunkHolder holder = holder(candidate);
            if (holder != null) {
                var loaded = holder.getFutureIfPresentUnchecked(ChunkStatus.STRUCTURE_STARTS).getNow(null);
                if (loaded != null && loaded.left().isPresent()) return inspect(candidate, loaded.left().get());
            }
            // 已就绪的真实记录优先，避免热搜索再绕过持久化存在性缓存。
            if (knownAbsent(candidate) && TwilightPlacement.of(candidate.placement()) == null) return reject();
            waitStarted = System.nanoTime();
        }
        if(flight==null) {
            var map=FLIGHTS.computeIfAbsent(level,k->new java.util.HashMap<>());
            flight=map.get(candidate.chunk().toLong());
            if(flight==null || !flight.stamp.current(level)) {
                flight=new Flight(level,candidate.chunk());map.put(candidate.chunk().toLong(),flight);
            }
            if(flight.refs>0)sharedAcquires++;
            flight.refs++;
        }
        if (!flight.ticket && !acquire()) return ScanCoordinator.Confirmation.WAIT;
        if (flight.future == null) {
            ChunkHolder holder = holder(candidate);
            if (holder == null || holder.getTicketLevel() > TICKET_LEVEL) return ScanCoordinator.Confirmation.WAIT;
            // 此 holder 入口只调度 future，不像 ServerChunkCache.getChunkFuture 那样 managedBlock。
            flight.future = holder.getOrScheduleFuture(ChunkStatus.STRUCTURE_STARTS, level.getChunkSource().chunkMap);
        }
        var ready = flight.future.getNow(null);
        if (ready == null) return ScanCoordinator.Confirmation.WAIT;
        if (ready.left().isEmpty()) throw new IllegalStateException("结构区块准备失败: " + candidate.chunk() + " " + ready.right().orElse(null));
        return inspect(candidate, ready.left().get());
    }

    private boolean knownAbsent(SearchCandidate candidate) {
        var manager = level.structureManager();
        var checks = ((StructureManagerAccessor)manager).unified$getStructureCheck();
        if (((StructureCheckAccessor)checks).unified$getLoadedChunks().get(candidate.chunk().toLong()) == null) return false;
        // 仅缓存命中才调用原方法；未知位置的 checkStructurePresence 会 scanChunk().join()。
        return manager.checkStructurePresence(candidate.chunk(), candidate.structure(), false) == StructureCheckResult.START_NOT_PRESENT;
    }

    private ChunkHolder holder(SearchCandidate candidate) {
        return ((ChunkMapAccessor)level.getChunkSource().chunkMap).unified$getUpdatingChunkIfPresent(candidate.chunk().toLong());
    }

    private boolean acquire() {
        boolean granted=background?VerificationSlots.acquireBackground(flight):VerificationSlots.acquire(flight);
        if(!granted)return false;
        level.getChunkSource().chunkMap.getDistanceManager().addTicket(TICKET, flight.chunk, TICKET_LEVEL, flight.id);
        flight.ticket=true;tickets++;chunkRequests++;
        return true;
    }

    private ScanCoordinator.Confirmation inspect(SearchCandidate candidate, ChunkAccess chunk) {
        var start = level.structureManager().getStartForStructure(SectionPos.bottomOf(chunk), candidate.structure(), chunk);
        if (start == null || !start.isValid() || start.getPieces().isEmpty()) return reject();
        var box = start.getPieces().get(0).getBoundingBox();
        int y = box.getCenter().getY();
        BlockPos position = candidate.key().toString().equals("minecraft:buried_treasure")
                ? new BlockPos(box.minX(), y, box.minZ()) : candidate.position().atY(y);
        if(AetherStructures.moved(candidate.structure())) {
            // 从真实片段还原锚点，不能依赖异步预测或同步locate坐标，否则两条路径会返回不同位置。
            var piece=start.getPieces().get(0);
            BlockPos anchor=piece instanceof net.minecraft.world.level.levelgen.structure.TemplateStructurePiece template?template.templatePosition()
                :piece instanceof net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece pool?pool.getPosition():box.getCenter();
            position=new BlockPos(Math.max(box.minX(),Math.min(box.maxX(),anchor.getX())),y,
                Math.max(box.minZ(),Math.min(box.maxZ(),anchor.getZ())));
        }
        confirmed = new SearchCandidate(position, candidate.key(), candidate.structure(), candidate.placement(),
                candidate.chunk(), SearchJob.distance(origin, position));
        release();
        return ScanCoordinator.Confirmation.ACCEPT;
    }

    private ScanCoordinator.Confirmation reject() { release(); return ScanCoordinator.Confirmation.REJECT; }

    private void release() {
        if(flight!=null && --flight.refs==0) {
            if(flight.ticket) {
                level.getChunkSource().chunkMap.getDistanceManager().removeTicket(TICKET,flight.chunk,TICKET_LEVEL,flight.id);
                tickets--;
            }
            VerificationSlots.release(flight);
            var map=FLIGHTS.get(level);
            if(map!=null){map.remove(flight.chunk.toLong(),flight);if(map.isEmpty())FLIGHTS.remove(level);}
        }
        flight=null;
        if (waitStarted != 0) { waitedNanos += System.nanoTime() - waitStarted; waitStarted = 0; }
        // future 属于 Minecraft；放弃引用即可，不能取消服务器共同使用的生成任务。
        attempt = null;
    }

    private void requireMainThread() {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("真实结构核对只能在服务端线程执行");
    }

    public void cancel() { requireMainThread(); release(); }
    public boolean waiting() { return attempt != null; }
    boolean slotWaiting(){return flight!=null && !flight.ticket;}
    boolean chunkWaiting(){return flight!=null && flight.ticket && (flight.future==null || !flight.future.isDone());}
    public SearchCandidate confirmed() { return confirmed; }
    public int confirmations() { return confirmations; }
    public int chunkRequests() { return chunkRequests; }
    int sharedAcquires(){return sharedAcquires;}
    public long mainNanos() { return mainNanos; }
    public long waitingNanos() { return waitedNanos + (waitStarted == 0 ? 0 : System.nanoTime() - waitStarted); }
    public static int ticketCount() { return tickets; }
    public static int queuedCount() { return VerificationSlots.queued(); }
}
