package com.chaosthedude.explorerscompass.unified.creature.network;

import java.util.*;
import com.chaosthedude.explorerscompass.unified.creature.catalog.*;
import net.minecraft.nbt.*;

/** 分包限制同时考虑 NBT 解码内存计费，不能只看压缩后的网络字节数。 */
public final class CreatureCatalogPackets {
    private CreatureCatalogPackets() {}
    public static List<CompoundTag> encode(UUID request, Collection<CreatureEntry> entries, boolean teleport) {
        List<ListTag> groups=new ArrayList<>();ListTag rows=new ListTag();int cost=0;
        for(var entry:entries){
            CompoundTag row=CreatureCatalog.encode(entry);int bytes=row.sizeInBytes();
            if(bytes>524288)throw new IllegalArgumentException("生物目录单条过大: "+entry.entity());
            if(!rows.isEmpty() && (rows.size()>=24 || cost+bytes>524288)){groups.add(rows);rows=new ListTag();cost=0;}
            rows.add(row);cost+=bytes;
        }
        if(!rows.isEmpty() || groups.isEmpty())groups.add(rows);
        if(groups.size()>1024)throw new IllegalArgumentException("生物目录分包过多");
        List<CompoundTag> packets=new ArrayList<>();
        for(int i=0;i<groups.size();i++){
            var n=new CompoundTag();n.putString("action","catalog");n.putUUID("request",request);
            n.putInt("part",i);n.putInt("parts",groups.size());n.putBoolean("canTeleport",teleport);n.put("entries",groups.get(i));packets.add(n);
        }
        return List.copyOf(packets);
    }
}
