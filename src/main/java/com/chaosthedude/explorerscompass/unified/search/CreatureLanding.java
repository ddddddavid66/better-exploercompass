package com.chaosthedude.explorerscompass.unified.search;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** 用户选择的生物降级：允许窒息、空中和水下，只保留维度硬保护。 */
public final class CreatureLanding {
    private CreatureLanding() {}
    public static List<Vec3> candidates(ServerPlayer player,Entity entity){
        var box=entity.getBoundingBox();double margin=player.getBbWidth()/2.0+.2;
        double west=box.minX-margin,east=box.maxX+margin,north=box.minZ-margin,south=box.maxZ+margin;
        double x=entity.getX(),y=entity.getY(),z=entity.getZ();
        return List.of(new Vec3(east,y,z),new Vec3(west,y,z),new Vec3(x,y,south),new Vec3(x,y,north),
                new Vec3(east,y,south),new Vec3(west,y,north),new Vec3(east,y,north),new Vec3(west,y,south));
    }
    static boolean allowed(ServerPlayer player,ServerLevel level,Vec3 point){
        var box=player.getDimensions(Pose.STANDING).makeBoundingBox(point);
        int ceiling=level.dimensionType().hasCeiling()?Math.min(level.getMaxBuildHeight(),level.getMinBuildHeight()+level.dimensionType().logicalHeight()):level.getMaxBuildHeight();
        if(box.minY<level.getMinBuildHeight()+1 || box.maxY>ceiling || !level.getWorldBorder().isWithinBounds(box))return false;
        for(var pos:BlockPos.betweenClosed(BlockPos.containing(box.minX,box.minY,box.minZ),BlockPos.containing(box.maxX-.0001,box.maxY-.0001,box.maxZ-.0001))){
            if(level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4)==null)return false;
            var state=level.getBlockState(pos);
            if(state.getFluidState().is(FluidTags.LAVA) || state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL) || state.is(Blocks.END_GATEWAY))return false;
        }
        return LandingSafety.allowed(level,point,level.getFluidState(BlockPos.containing(point)).is(FluidTags.WATER));
    }
}
