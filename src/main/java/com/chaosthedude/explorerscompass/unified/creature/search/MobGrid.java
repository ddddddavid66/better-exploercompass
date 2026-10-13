package com.chaosthedude.explorerscompass.unified.creature.search;

import java.util.*;

/** 稀疏64格格网＋UUID反向索引；不为8万格的空区域分配数组。 */
public final class MobGrid {
    private final Map<Long,Map<UUID,MobPoint>> cells=new HashMap<>();
    private final Map<UUID,Long> reverse=new HashMap<>();
    private static long cell(double x,double z){return ((long)Math.floor(x/64)<<32)^((long)Math.floor(z/64)&0xffffffffL);}
    public void put(MobPoint p){long key=cell(p.x(),p.z());Long old=reverse.put(p.uuid(),key);if(old!=null && old!=key)removeCell(old,p.uuid());cells.computeIfAbsent(key,k->new HashMap<>()).put(p.uuid(),p);}
    public void remove(UUID id){Long old=reverse.remove(id);if(old!=null)removeCell(old,id);}
    private void removeCell(long key,UUID id){var bucket=cells.get(key);if(bucket!=null){bucket.remove(id);if(bucket.isEmpty())cells.remove(key);}}
    public List<MobPoint> snapshot(){var result=new ArrayList<MobPoint>(reverse.size());for(var cell:cells.values())result.addAll(cell.values());return List.copyOf(result);}
    public static List<MobPoint> nearest(List<MobPoint> points,double x,double z,int radius){double max=(double)radius*radius;return points.stream().filter(p->p.distance(x,z)<=max).sorted(Comparator.<MobPoint>comparingDouble(p->p.distance(x,z)).thenComparing(p->p.uuid().toString())).toList();}
}
