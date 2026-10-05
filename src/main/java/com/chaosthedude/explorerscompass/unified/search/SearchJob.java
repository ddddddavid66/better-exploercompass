package com.chaosthedude.explorerscompass.unified.search;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public abstract class SearchJob {
    protected final ServerLevel level;
    protected final BlockPos start;
    protected final int radius;
    protected ScanCoordinator<SearchCandidate> scan;
    protected final List<SamplingBudget> budgets = new java.util.ArrayList<>();
    public long mainNanos;
    public int confirmations, chunkLoads;
    public final long startedNanos = System.nanoTime();
    private boolean fallback;
    protected boolean quotaDisabled;
    protected SearchJob(ServerLevel level, BlockPos start, int radius) {
        this.level = level; this.start = start.immutable(); this.radius = radius;
    }
    public boolean step() throws Exception {
        long before = System.nanoTime();
        try { return scan.step(SearchPool.executor()); }
        finally { mainNanos += System.nanoTime() - before; }
    }
    public void cancel() { scan.cancel(); }
    public void prime() { scan.prime(SearchPool.executor()); }
    public boolean done() { return scan.done(); }
    public SearchCandidate result() { return scan.result(); }
    // 状态和 HUD 会反复读取计数；小列表直接求和，避免每次建立 Stream 管线。
    public long samples() {long total=0;for(int i=0;i<budgets.size();i++)total+=budgets.get(i).used();return total;}
    public long sampleLimit() {long total=0;for(int i=0;i<budgets.size();i++)total+=budgets.get(i).maximum();return total;}
    public int legacySamples() { return (int)Math.min(Integer.MAX_VALUE, samples()); }
    public boolean budgetLimited() { return quotaDisabled || scan.budgetLimited(); }
    public String mode() {
        if (fallback) return "fallback";
        if (!scan.hasBackground()) return "sync";
        return scan.hasCompatibility() ? "mixed" : "async";
    }
    public String stage() {
        if (done()) return result() != null ? "found" : budgetLimited() ? "sample_limit" : "range_exhausted";
        return scan.confirming() ? "confirming" : biome() ? "sampling" : "predicting";
    }
    public SearchJob asFallback() { fallback = true; return this; }
    public long preparationNanos() { return 0; }
    public long confirmationNanos() { return 0; }
    public long waitingNanos() { return 0; }
    public long workerNanos() { return scan.workerNanos(); }
    public long submittedBatches() { return scan.submittedBatches(); }
    public long candidates() { return scan.candidateCount(); }
    public int coveredRadius() { return scan.coveredRadius(); }
    public abstract SearchJob synchronousFallback();
    public abstract boolean biome();
    public boolean asynchronous() { return false; }
    static long distance(BlockPos origin, BlockPos position) {
        long dx = (long)position.getX() - origin.getX(), dz = (long)position.getZ() - origin.getZ();
        return dx * dx + dz * dz;
    }
}
