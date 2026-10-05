package com.chaosthedude.explorerscompass.unified.search;

import com.chaosthedude.explorerscompass.unified.CompassUnified;
import com.chaosthedude.explorerscompass.unified.mixin.ChunkMapAccessor;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 索引是定位线索而不是存在证明，所有使用位置仍需重新核对。 */
@Mod.EventBusSubscriber(modid=CompassUnified.MOD_ID)
public final class EndDungeonIndex extends SavedData {
    private record Observation(ServerLevel level, BlockPos anchor) {}
    private record Loaded(ServerLevel level, ChunkPos chunk) {}
    private static final ArrayBlockingQueue<Observation> OBSERVATIONS = new ArrayBlockingQueue<>(2048);
    private static final ArrayDeque<Loaded> LOADED = new ArrayDeque<>();
    private static Loaded scanning; private static int section, cell;
    private final Map<Long, Boolean> entries = new LinkedHashMap<>();
    public static EndDungeonIndex get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(EndDungeonIndex::load, EndDungeonIndex::new,
                "explorerscompass_unified_end_dungeons");
    }
    private static EndDungeonIndex load(CompoundTag tag) {
        var index = new EndDungeonIndex();
        for (long anchor : tag.getLongArray("anchors")) index.entries.put(anchor, false);
        return index;
    }
    @Override public CompoundTag save(CompoundTag tag) {
        tag.putLongArray("anchors", entries.keySet().stream().mapToLong(Long::longValue).toArray()); return tag;
    }
    public List<BlockPos> positions() { return entries.keySet().stream().map(BlockPos::of).toList(); }
    boolean contains(BlockPos anchor) { return entries.containsKey(anchor.asLong()); }
    void remember(BlockPos anchor, boolean expanded) {
        if (!Objects.equals(entries.put(anchor.asLong(), expanded), expanded)) setDirty();
    }
    void forget(BlockPos anchor) { if (entries.remove(anchor.asLong()) != null) setDirty(); }
    /** 生成线程仅提交坐标，不读区块、不写 SavedData，也不改变原模板的返回值。 */
    public static void observe(ServerLevel level, BlockPos anchor) {
        if (level.dimension().equals(Level.END)) OBSERVATIONS.offer(new Observation(level, anchor.immutable()));
    }
    @SubscribeEvent public static void loaded(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.dimension().equals(Level.END)
                || !EndDungeonAdapter.special(level,EndDungeonAdapter.KEY) || !level.getServer().isSameThread()) return;
        if (LOADED.size()<256) LOADED.addLast(new Loaded(level,event.getChunk().getPos()));
    }
    static void maintain(long deadline) {
        while (System.nanoTime()<deadline) {
            Observation observation=OBSERVATIONS.poll();
            if (observation!=null) { get(observation.level()).remember(observation.anchor(),false); continue; }
            if (scanning==null) { scanning=LOADED.pollFirst(); section=0;cell=0; }
            if (scanning==null) return;
            var level=scanning.level();
            var holder=((ChunkMapAccessor)level.getChunkSource().chunkMap).unified$getUpdatingChunkIfPresent(scanning.chunk().toLong());
            var ready=holder==null?null:holder.getFutureIfPresentUnchecked(ChunkStatus.FULL).getNow(null);
            if(ready==null || ready.left().isEmpty()) { scanning=null; continue; }
            var chunk=ready.left().get();var sections=chunk.getSections();
            if(section>=sections.length) {scanning=null;continue;}
            var part=sections[section];
            if(!part.getStates().maybeHas(state -> state.is(EndDungeonAdapter.block(EndDungeonAdapter.MARKER))
                    || state.is(EndDungeonAdapter.block(EndDungeonAdapter.ALTAR)))) { section++;cell=0;continue; }
            // 每轮只读取少量方块，避免有大量特殊方块的区块一次扫描占满 tick。
            for(int count=0;count<64 && cell<4096;count++,cell++) {
                int x=cell&15,y=cell>>>8,z=(cell>>>4)&15;
                var state=part.getBlockState(x,y,z);
                if(!state.is(EndDungeonAdapter.block(EndDungeonAdapter.MARKER)) && !state.is(EndDungeonAdapter.block(EndDungeonAdapter.ALTAR)))continue;
                BlockPos position=new BlockPos(scanning.chunk().getMinBlockX()+x,chunk.getSectionYFromSectionIndex(section)*16+y,scanning.chunk().getMinBlockZ()+z);
                if(state.is(EndDungeonAdapter.block(EndDungeonAdapter.ALTAR)))position=position.offset(-1,-9,1);
                // 候选线索可以暂存，搜索仍检查整个组合，不能把独立祭坛当成完整地牢。
                get(level).remember(position,false);
            }
            if(cell==4096) {section++;cell=0;}
        }
    }
    public static void clearRuntime() {OBSERVATIONS.clear();LOADED.clear();scanning=null;}
}
