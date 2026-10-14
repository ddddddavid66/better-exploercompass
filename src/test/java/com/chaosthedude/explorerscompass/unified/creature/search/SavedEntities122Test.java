package com.chaosthedude.explorerscompass.unified.creature.search;

import java.io.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SavedEntities122Test {
    private CompoundTag entity(String id,UUID uuid,double x,double z){
        var n=new CompoundTag();n.putString("id",id);n.putUUID("UUID",uuid);n.putFloat("Health",10);
        var pos=new ListTag();pos.add(DoubleTag.valueOf(x));pos.add(DoubleTag.valueOf(70));pos.add(DoubleTag.valueOf(z));n.put("Pos",pos);
        var large=new CompoundTag();large.putByteArray("ignored",new byte[8192]);n.put("ForgeCaps",large);return n;
    }
    @Test void streamReadsOnlyLocationFieldsAndNestedPassengers()throws Exception{
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),dead=UUID.randomUUID();
        var root=new CompoundTag();var entities=new ListTag();var parent=entity("minecraft:turtle",a,-0.25,-65);
        var passengers=new ListTag();passengers.add(entity("alexsmobs:flutter",b,17,33));parent.put("Passengers",passengers);entities.add(parent);
        var deadTag=entity("minecraft:squid",dead,10,20);deadTag.putFloat("Health",0);entities.add(deadTag);root.put("Entities",entities);root.putString("unrelated","skip");
        var source=new ChunkPos(-2,3);var visitor=new SavedEntityVisitor();
        var bytes=new ByteArrayOutputStream();NbtIo.write(root,new DataOutputStream(bytes));NbtIo.parse(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),visitor);
        var points=visitor.points(source);assertEquals(2,points.size());assertEquals(Set.of(a,b),new HashSet<>(points.stream().map(SavedCreatureIndex.Point::uuid).toList()));
        assertTrue(points.stream().allMatch(p->p.source().equals(source)));assertFalse(((CompoundTag)visitor.getResult()).contains("unrelated"));
        assertFalse(((CompoundTag)((CompoundTag)visitor.getResult()).getList("Entities",10).get(0)).contains("ForgeCaps"));
        var memory=new SavedEntityVisitor();root.acceptAsRoot(memory);assertEquals(points,memory.points(source));
    }
    @Test void gridMatchesExhaustiveOrderingIncludingNegativeBoundariesAndUpdates(){
        var grid=new SpatialGrid<SavedCreatureIndex.Point>();var values=new HashMap<UUID,SavedCreatureIndex.Point>();var random=new Random(122);
        var type=new ResourceLocation("minecraft:turtle");
        for(int i=0;i<800;i++){var id=new UUID(0,i);var p=new SavedCreatureIndex.Point(id,type,random.nextInt(1025)-512,70,random.nextInt(1025)-512,new ChunkPos(0,0));values.put(id,p);grid.put(p);}
        for(int i=0;i<100;i++){var id=new UUID(0,i);grid.remove(id);values.remove(id);}
        var moved=new SavedCreatureIndex.Point(new UUID(0,799),type,-0.5,70,-64.5,new ChunkPos(1,1));grid.put(moved);values.put(moved.uuid(),moved);
        Comparator<SavedCreatureIndex.Point> order=Comparator.<SavedCreatureIndex.Point>comparingDouble(p->SpatialGrid.distance(p,-1,-65)).thenComparing(p->p.uuid().toString());
        var expected=values.values().stream().filter(p->SpatialGrid.distance(p,-1,-65)<=350d*350).filter(p->p.uuid().getLeastSignificantBits()%2==0).sorted(order).limit(17).toList();
        assertEquals(expected,SpatialGrid.nearest(grid.snapshot(),-1,-65,350,17,p->p.uuid().getLeastSignificantBits()%2==0));
        assertTrue(SpatialGrid.nearest(grid.snapshot(),0,0,100,0,p->true).isEmpty());
    }
}
