package com.chaosthedude.explorerscompass.unified.creature.search;

import java.util.*;

/** 已加载实体复用真实稀疏网格；保存记录与实时记录不混成同一有效状态。 */
public final class MobGrid {
    private final SpatialGrid<MobPoint> grid=new SpatialGrid<>();
    public void put(MobPoint point){grid.put(point);}
    public void remove(UUID id){grid.remove(id);}
    public SpatialGrid.Snapshot<MobPoint> snapshot(){return grid.snapshot();}
    public static List<MobPoint> nearest(SpatialGrid.Snapshot<MobPoint> snapshot,double x,double z,int radius){
        return SpatialGrid.nearest(snapshot,x,z,radius,Integer.MAX_VALUE,p->true);
    }
}
