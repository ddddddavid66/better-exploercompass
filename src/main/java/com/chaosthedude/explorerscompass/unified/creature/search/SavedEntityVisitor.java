package com.chaosthedude.explorerscompass.unified.creature.search;

import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.nbt.visitors.CollectToTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

/** 流式跳过装备、脑、能力及方块数据；只保留定位字段，绝不创建 Entity。 */
public final class SavedEntityVisitor extends CollectToTag {
    private static final Set<String> FIELDS=Set.of("id","UUID","Pos","Health","Passengers");
    @Override public EntryResult visitEntry(TagType<?> type,String name) {
        if(depth()==1 && !name.equals("Entities"))return EntryResult.SKIP;
        if(depth()>=3 && !FIELDS.contains(name))return EntryResult.SKIP;
        return super.visitEntry(type,name);
    }
    public List<SavedCreatureIndex.Point> points(ChunkPos source) {
        if(!(getResult() instanceof CompoundTag root))return List.of();
        var points=new ArrayList<SavedCreatureIndex.Point>();
        var queue=new ArrayDeque<CompoundTag>();
        for(var tag:root.getList("Entities",10))queue.add((CompoundTag)tag);
        while(!queue.isEmpty()) {
            var tag=queue.removeFirst();
            for(var passenger:tag.getList("Passengers",10))queue.add((CompoundTag)passenger);
            ResourceLocation type=ResourceLocation.tryParse(tag.getString("id"));
            var pos=tag.getList("Pos",6);
            if(type==null || !tag.hasUUID("UUID") || pos.size()!=3 || tag.contains("Health",99) && tag.getFloat("Health")<=0)continue;
            double x=pos.getDouble(0),y=pos.getDouble(1),z=pos.getDouble(2);
            if(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z))
                points.add(new SavedCreatureIndex.Point(tag.getUUID("UUID"),type,x,y,z,source));
        }
        return List.copyOf(points);
    }
}
