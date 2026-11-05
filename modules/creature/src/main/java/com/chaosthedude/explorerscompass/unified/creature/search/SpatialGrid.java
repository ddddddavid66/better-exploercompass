package com.chaosthedude.explorerscompass.unified.creature.search;

import java.util.*;
import java.util.function.Predicate;

/** 64 格稀疏网格；不可变快照可交给后台按单元距离下界检索。 */
public final class SpatialGrid<T extends SpatialGrid.Position> {
    public interface Position { UUID uuid(); double x(); double z(); }
    public record Cell<T>(int x,int z,List<T> values) {}
    public record Snapshot<T>(List<Cell<T>> cells) { public int size(){return cells.stream().mapToInt(c->c.values().size()).sum();} }
    private final Map<Long,Map<UUID,T>> cells=new HashMap<>();
    private final Map<UUID,Long> reverse=new HashMap<>();
    private static long key(double x,double z){return ((long)Math.floor(x/64)<<32)^((long)Math.floor(z/64)&0xffffffffL);}
    public void put(T point){long cell=key(point.x(),point.z());Long old=reverse.put(point.uuid(),cell);if(old!=null && old!=cell)removeFrom(old,point.uuid());cells.computeIfAbsent(cell,k->new HashMap<>()).put(point.uuid(),point);}
    public void remove(UUID id){Long cell=reverse.remove(id);if(cell!=null)removeFrom(cell,id);}
    private void removeFrom(long key,UUID id){var bucket=cells.get(key);if(bucket!=null){bucket.remove(id);if(bucket.isEmpty())cells.remove(key);}}
    public Snapshot<T> snapshot(){var result=new ArrayList<Cell<T>>(cells.size());cells.forEach((key,bucket)->result.add(new Cell<>((int)(key>>32),(int)(long)key,List.copyOf(bucket.values()))));return new Snapshot<>(List.copyOf(result));}
    public static double distance(Position point,double x,double z){double dx=point.x()-x,dz=point.z()-z;return dx*dx+dz*dz;}
    private static double lower(Cell<?> cell,double x,double z){double minX=(long)cell.x()*64,minZ=(long)cell.z()*64;double dx=Math.max(0,Math.max(minX-x,x-minX-64)),dz=Math.max(0,Math.max(minZ-z,z-minZ-64));return dx*dx+dz*dz;}
    public static <T extends Position> List<T> nearest(Snapshot<T> snapshot,double x,double z,int radius,int limit,Predicate<T> accepts){
        if(limit<1)return List.of();double max=(double)radius*radius;
        Comparator<T> order=Comparator.<T>comparingDouble(p->distance(p,x,z)).thenComparing(p->p.uuid().toString());
        var frontier=new PriorityQueue<Cell<T>>(Comparator.comparingDouble(c->lower(c,x,z)));
        for(var cell:snapshot.cells())if(lower(cell,x,z)<=max)frontier.add(cell);
        var best=new PriorityQueue<T>(order.reversed());
        while(!frontier.isEmpty()){
            if(Thread.currentThread().isInterrupted())return List.of();
            var cell=frontier.remove();double bound=best.size()==limit?Math.min(max,distance(best.peek(),x,z)):max;
            // 等距仍须访问，以 UUID 固定顺序消除线程完成顺序的影响。
            if(lower(cell,x,z)>bound)break;
            for(var point:cell.values())if(distance(point,x,z)<=max && accepts.test(point)){
                if(best.size()<limit)best.add(point);else if(order.compare(point,best.peek())<0){best.remove();best.add(point);}
            }
        }
        var result=new ArrayList<>(best);result.sort(order);return List.copyOf(result);
    }
}
