package com.chaosthedude.explorerscompass.unified.search;

import java.util.*;
import com.chaosthedude.explorerscompass.unified.data.LandingColumns;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** 按已就绪区块消费完整高度，防止逐高度轮转导致准备票据反复往返。 */
final class AetherHeightProbe {
    private final ServerPlayer player;
    private final ServerLevel level;
    private final ResourceLocation target;
    private final BlockPos origin;
    private final int minimum,maximum,span;
    private final Map<Long,ResourceLocation> biomes=new HashMap<>();
    private List<Group> groups;
    private Group active;
    private boolean relaxed;
    private Vec3 relaxedHint;
    private int checked,changes,targetMatches,supports,completedColumns;
    private ResourceLocation previous;
    BlockPos waiting;
    private static final class Group {
        final List<BlockPos> columns=new ArrayList<>();
        int column,priority,offset;
        boolean done(){return column>=columns.size();}
        BlockPos point(){return columns.get(column);}
    }
    AetherHeightProbe(ServerPlayer player,ResourceLocation target,BlockPos origin,boolean relaxed){
        this.player=player;level=player.serverLevel();this.target=target;this.origin=origin;this.relaxed=relaxed;
        minimum=level.getMinBuildHeight()+4;maximum=level.getMaxBuildHeight()-(int)Math.ceil(player.getBbHeight());
        span=Math.max(Math.abs(origin.getY()-minimum),Math.abs(maximum-origin.getY()));resetGroups();
    }
    private void resetGroups(){
        Map<ChunkPos,Group> byChunk=new LinkedHashMap<>();
        for(var c:relaxed?LandingColumns.RELAXED:LandingColumns.STRICT){
            var p=origin.offset(c.x(),0,c.z());byChunk.computeIfAbsent(new ChunkPos(p),ignored->new Group()).columns.add(p);
        }
        groups=List.copyOf(byChunk.values());active=null;
    }
    boolean exhausted(){return groups.stream().allMatch(Group::done);}
    int checked(){return checked;}
    boolean pending(boolean mode){return mode!=relaxed || !exhausted();}
    List<BlockPos> provenUnusableColumn(){
        if(!exhausted())return List.of();
        // 已实际检查整柱全部支撑面；硬高度边界之外也不可能成为合法落点。
        List<BlockPos> result=new ArrayList<>();
        for(int y=level.getMinBuildHeight();y<level.getMaxBuildHeight();y++)result.add(origin.atY(y));
        return List.copyOf(result);
    }
    Vec3 step(long deadline,boolean nowRelaxed){
        waiting=null;
        if(nowRelaxed!=relaxed){relaxed=nowRelaxed;resetGroups();previous=null;}
        if(relaxed && relaxedHint!=null){var hint=relaxedHint;relaxedHint=null;
            if(matches(hint) && (LandingChecks.standing(player,level,target,hint,true) || LandingChecks.waterStanding(player,level,hint)))return hint;
        }
        while(System.nanoTime()<deadline){
            if(active==null || active.done()){
                active=null;
                // 先完整消费仍就绪的区块组；所有可做工作耗尽才申请新中心。
                for(var g:groups)if(!g.done()){
                    if(LandingChecks.ready(player,level,g.point(),true)){active=g;break;}
                    if(waiting==null)waiting=g.point();
                }
                if(active==null)return null;waiting=null;
            }
            BlockPos p=active.point();
            if(!LandingChecks.ready(player,level,p,true)){waiting=p;active=null;return null;}
            int y;
            if(active.priority<2){
                var chunk=level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4);
                y=active.priority++==0?origin.getY():chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,p.getX()&15,p.getZ()&15)+1;
            }else{
                if(active.offset>span*2){active.column++;active.priority=active.offset=0;completedColumns++;previous=null;continue;}
                y=origin.getY()+LandingColumns.vertical(active.offset++);
            }
            if(y<minimum || y>maximum)continue;p=p.atY(y);checked++;
            var below=p.below();var floor=level.getBlockState(below);
            boolean solid=!floor.isAir() && !floor.getCollisionShape(level,below).isEmpty();
            boolean water=!floor.getFluidState().isEmpty() || !level.getFluidState(p).isEmpty();
            // 空气高度只读取每4格的变化；所有实际支撑面仍逐格进行完整真实群系校验。
            if(!solid && !water && Math.floorMod(y,4)!=0)continue;
            var biome=biome(p);
            if(biome!=null && !biome.equals(previous)){changes++;previous=biome;}
            if(!target.equals(biome) && !(solid && target.equals(biome(p.below()))))continue;targetMatches++;
            if(!solid && !water)continue;supports++;
            Vec3 found=LandingChecks.find(player,level,target,p,relaxed);
            if(found==null && relaxed && water)found=LandingChecks.waterSurface(player,level,p);
            if(found!=null && matches(found))return found;
            if(!relaxed && relaxedHint==null){var hint=LandingChecks.find(player,level,target,p,true);
                if(hint==null && water)hint=LandingChecks.waterSurface(player,level,p);
                if(hint!=null && matches(hint))relaxedHint=hint;
            }
        }
        return null;
    }
    private ResourceLocation biome(BlockPos point){
        // getBiome 含逐方块模糊边界，缓存使用精确XYZ而非quart坐标。
        if(biomes.size()>=32768)biomes.clear();
        return biomes.computeIfAbsent(point.asLong(),ignored->level.getBiome(point).unwrapKey().map(k->k.location()).orElse(null));
    }
    private boolean matches(Vec3 p){return target.equals(biome(BlockPos.containing(p)));}
    String summary(){return "checked="+checked+", sampled_changes="+changes+", target_matches="+targetMatches+", support_candidates="+supports+", completed_columns="+completedColumns+", groups="+groups.stream().filter(Group::done).count()+"/"+groups.size()+", relaxed="+relaxed;}
}
