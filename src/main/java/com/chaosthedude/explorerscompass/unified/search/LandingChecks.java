package com.chaosthedude.explorerscompass.unified.search;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;

/** 检查实际站立碰撞面；不读取尚未就绪的区块。 */
final class LandingChecks {
    private static final Set<Block> STRICT = Set.of(Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.CACTUS,
            Blocks.MAGMA_BLOCK, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.SWEET_BERRY_BUSH,
            Blocks.POWDER_SNOW, Blocks.WITHER_ROSE, Blocks.COBWEB, Blocks.NETHER_PORTAL, Blocks.END_PORTAL, Blocks.END_GATEWAY);
    private static final Set<Block> FORBIDDEN = Set.of(Blocks.NETHER_PORTAL, Blocks.END_PORTAL, Blocks.END_GATEWAY, Blocks.POWDER_SNOW);
    private LandingChecks() {}
    static boolean ready(ServerPlayer player, ServerLevel level, BlockPos point, boolean biome) {
        var box = player.getDimensions(Pose.STANDING).makeBoundingBox(point.getX()+.5, point.getY(), point.getZ()+.5);
        int margin = biome ? 4 : 1;
        for (int x = ((int)Math.floor(box.minX-margin))>>4; x <= ((int)Math.floor(box.maxX+margin))>>4; x++)
            for (int z = ((int)Math.floor(box.minZ-margin))>>4; z <= ((int)Math.floor(box.maxZ+margin))>>4; z++)
                if (level.getChunkSource().getChunkNow(x,z)==null) return false;
        return true;
    }
    static Vec3 find(ServerPlayer player, ServerLevel level, ItemStack stack, BlockPos point, boolean relaxed) {
        if (!ready(player,level,point,!relaxed && SearchService.isBiome(stack))) return null;
        for (int drop=0;drop<=(relaxed?3:0);drop++) {
            BlockPos support=point.below(drop+1); var floor=level.getBlockState(support);
            if (bad(floor,relaxed) || !floor.getFluidState().isEmpty()) continue;
            // 半砖、台阶和栅栏使用中心脚下的真实碰撞面高度，不假定每块都是一格高。
            double top=-1;
            for(var shape:floor.getCollisionShape(level,support).toAabbs())
                if(shape.minX<=.5 && shape.maxX>=.5 && shape.minZ<=.5 && shape.maxZ>=.5) top=Math.max(top,shape.maxY);
            if(top<0) continue;
            Vec3 landing=new Vec3(point.getX()+.5,support.getY()+top,point.getZ()+.5);
            if(valid(player,level,stack,landing,relaxed)) return landing;
        }
        return null;
    }
    static boolean valid(ServerPlayer player, ServerLevel level, ItemStack stack, Vec3 landing, boolean relaxed) {
        BlockPos point=BlockPos.containing(landing);
        if(!ready(player,level,point,!relaxed && SearchService.isBiome(stack))) return false;
        var box=player.getDimensions(Pose.STANDING).makeBoundingBox(landing.x,landing.y,landing.z);
        int ceiling=level.dimensionType().hasCeiling()?Math.min(level.getMaxBuildHeight(),level.getMinBuildHeight()+level.dimensionType().logicalHeight()):level.getMaxBuildHeight();
        if(box.minY<level.getMinBuildHeight()+1 || box.maxY>ceiling || !level.getWorldBorder().isWithinBounds(box)) return false;
        for(BlockPos pos:BlockPos.betweenClosed(BlockPos.containing(box.minX,box.minY+.0001,box.minZ),BlockPos.containing(box.maxX-.0001,box.maxY-.0001,box.maxZ-.0001))) {
            var state=level.getBlockState(pos);
            if(bad(state,relaxed)) return false;
            if(!state.getFluidState().isEmpty() && (!relaxed || !state.getFluidState().is(FluidTags.WATER))) return false;
        }
        if(!level.getFluidState(BlockPos.containing(landing.x,box.maxY-.05,landing.z)).isEmpty()) return false;
        if(!relaxed && SearchService.isBiome(stack) && !level.getBiome(point).unwrapKey().map(key->key.location().toString().equals(stack.getTag().getString(SearchService.BIOME_KEY))).orElse(false)) return false;
        var vehicle=player.getRootVehicle();
        return (vehicle==player || !vehicle.getBoundingBox().intersects(box)) && level.noCollision(player,box);
    }
    static boolean standing(ServerPlayer player,ServerLevel level,ItemStack stack,Vec3 landing,boolean relaxed) {
        if(!valid(player,level,stack,landing,relaxed))return false;
        BlockPos feet=BlockPos.containing(landing);
        // 落地和缓存复核使用真实小数 Y；半砖和超过一格的碰撞面不能按整数格截断。
        for(int dy=0;dy<=2;dy++) {
            BlockPos pos=feet.below(dy);var state=level.getBlockState(pos);
            if(bad(state,relaxed) || !state.getFluidState().isEmpty())continue;
            for(var box:state.getCollisionShape(level,pos).toAabbs())
                if(box.minX<=.5 && box.maxX>=.5 && box.minZ<=.5 && box.maxZ>=.5
                    && Math.abs(pos.getY()+box.maxY-landing.y)<.005)return true;
        }
        return false;
    }
    private static boolean bad(net.minecraft.world.level.block.state.BlockState state, boolean relaxed) {
        return state.getFluidState().is(FluidTags.LAVA) || (relaxed?FORBIDDEN:STRICT).contains(state.getBlock());
    }
}
