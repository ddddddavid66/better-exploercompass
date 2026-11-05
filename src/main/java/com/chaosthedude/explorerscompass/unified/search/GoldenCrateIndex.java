package com.chaosthedude.explorerscompass.unified.search;

import com.chaosthedude.explorerscompass.unified.CompassUnified;
import com.david.compass.runtime.game.mixin.ChunkMapAccessor;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 记录已加载旧区块里的真实箱子；索引仅作线索，返回前仍核实方块。 */
@Mod.EventBusSubscriber(modid=CompassUnified.MOD_ID)
public final class GoldenCrateIndex extends SavedData {
    private record Loaded(ServerLevel level,ChunkPos chunk) {}
    private static final ArrayDeque<Loaded> QUEUE=new ArrayDeque<>();
    private static Loaded scanning;
    private static int section,cell;
    private final Set<Long> positions=new LinkedHashSet<>();
    public static GoldenCrateIndex get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(GoldenCrateIndex::load,GoldenCrateIndex::new,
                "explorerscompass_unified_golden_crates");
    }
    private static GoldenCrateIndex load(CompoundTag tag) {
        var index=new GoldenCrateIndex();for (long value:tag.getLongArray("anchors")) index.positions.add(value);return index;
    }
    @Override public CompoundTag save(CompoundTag tag) {
        tag.putLongArray("anchors",positions.stream().mapToLong(Long::longValue).toArray());return tag;
    }
    List<BlockPos> positions() { return positions.stream().map(BlockPos::of).toList(); }
    void remember(BlockPos pos) { if (positions.add(pos.asLong())) setDirty(); }
    void forget(BlockPos pos) { if (positions.remove(pos.asLong())) setDirty(); }
    @SubscribeEvent public static void loaded(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && level.getServer().isSameThread()
                && GoldenCrateAdapter.DIMENSION.equals(level.dimension().location()) && GoldenCrateAdapter.supported()
                && QUEUE.size()<256) QUEUE.addLast(new Loaded(level,event.getChunk().getPos()));
    }
    static void maintain(long deadline) {
        while (System.nanoTime()<deadline) {
            if (scanning==null) {scanning=QUEUE.pollFirst();section=0;cell=0;}
            if (scanning==null) return;
            var level=scanning.level();
            var holder=((ChunkMapAccessor)level.getChunkSource().chunkMap).unified$getUpdatingChunkIfPresent(scanning.chunk().toLong());
            var ready=holder==null?null:holder.getFutureIfPresentUnchecked(ChunkStatus.FULL).getNow(null);
            if (ready==null || ready.left().isEmpty()) {scanning=null;continue;}
            var chunk=ready.left().get();
            if (section>=chunk.getSections().length) {scanning=null;continue;}
            var part=chunk.getSections()[section];
            if (!part.getStates().maybeHas(state -> state.is(GoldenCrateAdapter.block()))) {section++;cell=0;continue;}
            for (int count=0;count<64 && cell<4096;count++,cell++) {
                int x=cell&15,y=cell>>>8,z=(cell>>>4)&15;
                if (part.getBlockState(x,y,z).is(GoldenCrateAdapter.block()))
                    get(level).remember(new BlockPos(scanning.chunk().getMinBlockX()+x,
                            chunk.getSectionYFromSectionIndex(section)*16+y,scanning.chunk().getMinBlockZ()+z));
            }
            if (cell==4096) {section++;cell=0;}
        }
    }
    public static void clearRuntime() { QUEUE.clear();scanning=null; }
}
