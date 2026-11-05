package com.david.compass.runtime.game.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;

/** 冻结群系采样域；额度计算与实际游标共用同一份高度和横向间距。 */
public record BiomeSamplingProfile(List<Integer> heights, int primaryHeights, int spacing,
                                   int depthInterval, boolean aether, String evidence) {
    record LinearDepth(int fromY,int toY,double from,double to) {
        OptionalInt preferred(double low,double high,int minY,int maxY) {
            if(fromY>=toY || from==to || !Double.isFinite(from+to+low+high))return OptionalInt.empty();
            double y=fromY+((low+high)*.5-from)*(toY-fromY)/(to-from);
            if(!Double.isFinite(y))return OptionalInt.empty();
            y=Math.max(minY,Math.min(maxY-1,y));
            return OptionalInt.of(clamp((int)Math.round(y/4)*4,minY,maxY-1));
        }
    }
    public BiomeSamplingProfile {
        heights=List.copyOf(heights);
        if(heights.isEmpty() || primaryHeights<1 || primaryHeights>heights.size() || spacing<1 || depthInterval<1)
            throw new IllegalArgumentException("Invalid biome sampling profile");
    }

    public static BiomeSamplingProfile legacy(int minY,int maxY,int originY,int spacing,int vertical,int interval) {
        int initial=clamp(originY,minY,maxY-1);
        var values=new ArrayList<Integer>();values.add(initial);
        if(vertical>0)for(int y=minY;y<maxY;y+=vertical)if(Math.floorDiv(y,4)!=Math.floorDiv(initial,4))values.add(y);
        values.sort(Comparator.comparingInt(y->Math.abs(y-initial)));
        return new BiomeSamplingProfile(values,1,spacing,interval,false,"configured_grid");
    }

    public static BiomeSamplingProfile aether(int minY,int maxY,int originY,int spacing,int interval,
                                              OptionalInt preferred,String evidence) {
        int initial=clamp(originY,minY,maxY-1);
        var values=new ArrayList<Integer>();var used=new LinkedHashSet<Integer>();
        if(preferred.isPresent()) {
            int hint=clamp(preferred.getAsInt(),minY,maxY-1);
            if(Math.floorDiv(hint,4)!=Math.floorDiv(initial,4))add(values,used,hint);
        }
        add(values,used,initial);int primary=values.size();
        var backups=new ArrayList<Integer>();
        // 逐quart保留整个有效高度；参数带只决定优先高度，绝不用于删除带外位置。
        for(int q=Math.floorDiv(minY,4);q<=Math.floorDiv(maxY-1,4);q++)
            if(!used.contains(q))backups.add(clamp(q*4,minY,maxY-1));
        backups.sort(Comparator.comparingInt(y->Math.abs(y-initial)));values.addAll(backups);
        return new BiomeSamplingProfile(values,primary,spacing,interval,true,evidence);
    }

    private static void add(List<Integer> values,java.util.Set<Integer> used,int y) {
        if(used.add(Math.floorDiv(y,4)))values.add(y);
    }
    private static int clamp(int value,int min,int max){return Math.max(min,Math.min(max,value));}

    /** 圆形新增环带中的唯一XYZ数量；按整数平方比较，边界和负坐标完全对称。 */
    public long samples(int radius,int innerRadius) {
        if(radius<0 || innerRadius>=radius)return 0;
        long main=Math.subtractExact(columns(radius,spacing),columns(innerRadius,spacing));
        int backupSpacing=Math.multiplyExact(spacing,depthInterval);
        long backup=Math.subtractExact(columns(radius,backupSpacing),columns(innerRadius,backupSpacing));
        return Math.addExact(Math.multiplyExact(main,primaryHeights),
                Math.multiplyExact(backup,heights.size()-primaryHeights));
    }

    public long quota(long configured,int radius,int innerRadius) {
        // 0是用户主动禁用采样，不能通过自动补足额度重新开启。
        // 额外一枚令牌仅供圆外边界游标归空；它们不会调用采样，也不计入实际数量。
        return configured<=0 || !aether?configured:Math.max(configured,Math.addExact(samples(radius,innerRadius),1));
    }

    static long columns(int radius,int step) {
        if(radius<0)return 0;
        long squared=(long)radius*radius,total=0;int maximum=radius/step;
        for(int x=-maximum;x<=maximum;x++) {
            long dx=(long)x*step,remaining=squared-dx*dx;
            long z=(long)Math.sqrt(remaining)/step;
            // sqrt只给初值；最终判定用整数，避免大半径整圆边界的浮点误差。
            while((z+1)*step*(z+1)*step<=remaining)z++;
            while(z*step*z*step>remaining)z--;
            total=Math.addExact(total,2*z+1);
        }
        return total;
    }

    public String description(){return "primary_heights="+heights.subList(0,primaryHeights)+";quart_heights="+heights.size()
            +";spacing="+spacing+";backup_spacing="+(long)spacing*depthInterval+";evidence="+evidence;}
}
