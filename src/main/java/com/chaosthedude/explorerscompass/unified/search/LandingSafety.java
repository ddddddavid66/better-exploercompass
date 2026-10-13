package com.chaosthedude.explorerscompass.unified.search;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** 所有模式共用的硬限制。只读已就绪区块，屋顶缓存每 tick 失效。 */
final class LandingSafety {
    private static final Map<ServerLevel,Map<Long,Integer>> roofs=new WeakHashMap<>();
    private static int lastTick=-1;
    private LandingSafety() {}
    static void invalidate(ServerLevel level){roofs.remove(level);}
    static boolean allowed(ServerLevel level,Vec3 point,boolean water) {
        if(!level.dimension().equals(Level.OVERWORLD)) {
            int tick=level.getServer().getTickCount();
            if(lastTick!=tick){roofs.clear();lastTick=tick;}
            BlockPos feet=BlockPos.containing(point);
            long column=BlockPos.asLong(feet.getX(),0,feet.getZ());
            var cache=roofs.computeIfAbsent(level,ignored->new HashMap<>());
            int roof=cache.computeIfAbsent(column,ignored->roof(level,feet));
            if(point.y>=roof || level.getBlockState(BlockPos.containing(point.x,point.y-.01,point.z)).is(Blocks.BEDROCK))return false;
        }
        if(!level.dimension().location().toString().equals("aether:the_aether"))return true;
        int x=(int)Math.floor(point.x),z=(int)Math.floor(point.z),y=(int)Math.floor(point.y);
        // 邻列地面可以略低，但不能把水柱或岛屿边缘当作可落脚的平台。
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
            BlockPos p=new BlockPos(x+dx,y,z+dz);
            if(level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)==null)return false;
            if(water) {
                var fluid=level.getFluidState(p);
                if(!fluid.is(FluidTags.WATER) || !fluid.isSource() || fluid.getValue(net.minecraft.world.level.material.FlowingFluid.FALLING))return false;
                boolean bottom=false;
                for(int depth=1;depth<=16;depth++) {
                    var below=p.below(depth);var state=level.getBlockState(below);
                    if(!state.getFluidState().isEmpty()) {if(!state.getFluidState().is(FluidTags.WATER))return false;continue;}
                    if(top(level,below)>=0)bottom=true;
                    break;
                }
                if(!bottom)return false;
            } else {
                boolean supported=false;
                for(int dy=0;dy<=4;dy++) {
                    BlockPos below=p.below(dy);double top=top(level,below);
                    double gap=point.y-(below.getY()+top);
                    if(top>=0 && gap>=-.005 && gap<=3.005){supported=true;break;}
                }
                if(!supported)return false;
            }
        }
        return true;
    }
    private static int roof(ServerLevel level,BlockPos point) {
        var chunk=level.getChunkSource().getChunkNow(point.getX()>>4,point.getZ()>>4);
        if(chunk==null)return level.getMinBuildHeight();
        // 排除底部基岩；生成高度、维度高度不一致时仍以实际方块为准。
        for(int y=level.getMaxBuildHeight()-1;y>=level.getMinBuildHeight()+8;y--)
            if(chunk.getBlockState(new BlockPos(point.getX(),y,point.getZ())).is(Blocks.BEDROCK))return y;
        return Integer.MAX_VALUE;
    }
    private static double top(ServerLevel level,BlockPos point) {
        var state=level.getBlockState(point);
        if(!state.getFluidState().isEmpty())return -1;
        double top=-1;
        for(var box:state.getCollisionShape(level,point).toAabbs())
            if(box.minX<=.5 && box.maxX>=.5 && box.minZ<=.5 && box.maxZ>=.5)top=Math.max(top,box.maxY);
        return top;
    }
}
