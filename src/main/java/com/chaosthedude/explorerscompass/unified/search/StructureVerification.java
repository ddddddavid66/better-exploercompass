package com.chaosthedude.explorerscompass.unified.search;

import com.chaosthedude.explorerscompass.unified.mixin.ChunkMapAccessor;
import com.chaosthedude.explorerscompass.unified.mixin.StructureCheckAccessor;
import com.chaosthedude.explorerscompass.unified.mixin.StructureManagerAccessor;
import com.mojang.datafixers.util.Either;
import java.util.ArrayDeque;
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
    private static final int MAX_TICKETS = 4;
    private static final ArrayDeque<StructureVerification> QUEUE = new ArrayDeque<>();
    private static int tickets;
    private final ServerLevel level;
    private final BlockPos origin;
    private final UUID ticketId = UUID.randomUUID();
    private SearchCandidate attempt, confirmed;
    private CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future;
    private boolean ticket, queued;
    private int confirmations, chunkRequests;
    private long mainNanos, waitStarted, waitedNanos;

    public StructureVerification(ServerLevel level, BlockPos origin) { this.level = level; this.origin = origin.immutable(); }

    public ScanCoordinator.Confirmation verify(SearchCandidate candidate) {
        requireMainThread();
        long begin = System.nanoTime();
        try { return advance(candidate); }
        catch (RuntimeException | Error error) { release(); throw error; }
        finally { mainNanos += System.nanoTime() - begin; }
    }

    private ScanCoordinator.Confirmation advance(SearchCandidate candidate) {
        if (!candidate.equals(attempt)) {
            // 更近的候选可抢占尚未就绪的旧位置，不能保留其 ticket 或迟到的 future。
            release(); attempt = candidate; confirmations++;
            if (candidate.placement().getClass().getName().equals(GelGridPlacement.TYPE)
                    && !candidate.placement().isStructureChunk(level.getChunkSource().getGeneratorState(),
                            candidate.chunk().x, candidate.chunk().z)) return reject();
            if (knownAbsent(candidate) && TwilightPlacement.of(candidate.placement()) == null) return reject();
            ChunkHolder holder = holder(candidate);
            if (holder != null) {
                var loaded = holder.getFutureIfPresentUnchecked(ChunkStatus.STRUCTURE_STARTS).getNow(null);
                if (loaded != null && loaded.left().isPresent()) return inspect(candidate, loaded.left().get());
            }
            waitStarted = System.nanoTime();
        }
        if (!ticket && !acquire()) return ScanCoordinator.Confirmation.WAIT;
        if (future == null) {
            ChunkHolder holder = holder(candidate);
            if (holder == null || holder.getTicketLevel() > TICKET_LEVEL) return ScanCoordinator.Confirmation.WAIT;
            // 此 holder 入口只调度 future，不像 ServerChunkCache.getChunkFuture 那样 managedBlock。
            future = holder.getOrScheduleFuture(ChunkStatus.STRUCTURE_STARTS, level.getChunkSource().chunkMap);
        }
        var ready = future.getNow(null);
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
        if (!queued) { QUEUE.addLast(this); queued = true; }
        if (QUEUE.peekFirst() != this || !VerificationSlots.acquire(this)) return false;
        QUEUE.removeFirst(); queued = false;
        level.getChunkSource().chunkMap.getDistanceManager().addTicket(TICKET, attempt.chunk(), TICKET_LEVEL, ticketId);
        ticket = true; tickets++; chunkRequests++;
        return true;
    }

    private ScanCoordinator.Confirmation inspect(SearchCandidate candidate, ChunkAccess chunk) {
        var start = level.structureManager().getStartForStructure(SectionPos.bottomOf(chunk), candidate.structure(), chunk);
        if (start == null || !start.isValid() || start.getPieces().isEmpty()) return reject();
        var box = start.getPieces().get(0).getBoundingBox();
        int y = box.getCenter().getY();
        BlockPos position = candidate.key().toString().equals("minecraft:buried_treasure")
                ? new BlockPos(box.minX(), y, box.minZ()) : candidate.position().atY(y);
        confirmed = new SearchCandidate(position, candidate.key(), candidate.structure(), candidate.placement(),
                candidate.chunk(), SearchJob.distance(origin, position));
        release();
        return ScanCoordinator.Confirmation.ACCEPT;
    }

    private ScanCoordinator.Confirmation reject() { release(); return ScanCoordinator.Confirmation.REJECT; }

    private void release() {
        if (queued) { QUEUE.remove(this); queued = false; }
        if (ticket) {
            level.getChunkSource().chunkMap.getDistanceManager().removeTicket(TICKET, attempt.chunk(), TICKET_LEVEL, ticketId);
            ticket = false; tickets--;
        }
        if (waitStarted != 0) { waitedNanos += System.nanoTime() - waitStarted; waitStarted = 0; }
        // future 属于 Minecraft；放弃引用即可，不能取消服务器共同使用的生成任务。
        VerificationSlots.release(this); future = null; attempt = null;
    }

    private void requireMainThread() {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("真实结构核对只能在服务端线程执行");
    }

    public void cancel() { requireMainThread(); release(); }
    public boolean waiting() { return attempt != null; }
    public SearchCandidate confirmed() { return confirmed; }
    public int confirmations() { return confirmations; }
    public int chunkRequests() { return chunkRequests; }
    public long mainNanos() { return mainNanos; }
    public long waitingNanos() { return waitedNanos + (waitStarted == 0 ? 0 : System.nanoTime() - waitStarted); }
    public static int ticketCount() { return tickets; }
    public static int queuedCount() { return QUEUE.size(); }
}
