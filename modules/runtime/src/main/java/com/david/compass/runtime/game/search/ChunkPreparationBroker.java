package com.david.compass.runtime.game.search;

import java.util.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;

/** 主线程区块需求合并；升级先添加再移除，不取消 Minecraft 的生成 Future。 */
public final class ChunkPreparationBroker {
    private static final TicketType<UUID> TYPE=TicketType.create("compass_preparation",UUID::compareTo);
    private static final Map<ServerLevel,Map<ChunkPos,Entry>> LEVELS=new IdentityHashMap<>();
    private static final class Entry {
        final UUID id=UUID.randomUUID(); final GenerationStamp stamp;
        final TreeMap<Integer,Integer> demands=new TreeMap<>(); int installed=Integer.MAX_VALUE;
        Entry(ServerLevel level){stamp=GenerationStamp.capture(level);}
    }
    private ChunkPreparationBroker(){}
    public static void retain(ServerLevel level,ChunkPos pos,ChunkStatus status){
        requireMain(level);
        var map=LEVELS.computeIfAbsent(level,k->new HashMap<>());
        var entry=map.computeIfAbsent(pos,k->new Entry(level));
        if(!entry.stamp.current(level))throw new IllegalStateException("Stale generation lease");
        entry.demands.merge(ChunkLevel.byStatus(status),1,Integer::sum);update(level,pos,entry);
    }
    public static void release(ServerLevel level,ChunkPos pos,ChunkStatus status){
        requireMain(level);var map=LEVELS.get(level);if(map==null)return;
        var entry=map.get(pos);if(entry==null)return;int key=ChunkLevel.byStatus(status);
        Integer count=entry.demands.get(key);if(count==null)throw new IllegalStateException("Unbalanced chunk lease");
        if(count==1)entry.demands.remove(key);else entry.demands.put(key,count-1);
        update(level,pos,entry);
        if(entry.demands.isEmpty()){map.remove(pos);if(map.isEmpty())LEVELS.remove(level);}
    }
    private static void update(ServerLevel level,ChunkPos pos,Entry entry){
        int next=entry.demands.isEmpty()?Integer.MAX_VALUE:entry.demands.firstKey();
        if(next==entry.installed)return;
        var manager=level.getChunkSource().chunkMap.getDistanceManager();
        if(next!=Integer.MAX_VALUE)manager.addTicket(TYPE,pos,next,entry.id);
        if(entry.installed!=Integer.MAX_VALUE)manager.removeTicket(TYPE,pos,entry.installed,entry.id);
        entry.installed=next;
    }
    private static void requireMain(ServerLevel level){if(!level.getServer().isSameThread())throw new IllegalStateException("Chunk lease requires server thread");}
}
