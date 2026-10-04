package com.chaosthedude.explorerscompass.unified.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class LandingColumns {
    public record Column(int x,int z) {}
    public static final List<Column> STRICT=around(8), RELAXED=around(32);
    private LandingColumns() {}
    public static List<Column> around(int radius) {
        List<Column> points=new ArrayList<>();
        for(int x=-radius;x<=radius;x++) for(int z=-radius;z<=radius;z++) points.add(new Column(x,z));
        points.sort(Comparator.comparingInt((Column p)->p.x*p.x+p.z*p.z).thenComparingInt(Column::x).thenComparingInt(Column::z));
        return List.copyOf(points);
    }
    public static int vertical(int index) { return index==0?0:index%2==1?-(index+1)/2:index/2; }
}
