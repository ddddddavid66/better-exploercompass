package com.chaosthedude.explorerscompass.unified.search;

import com.chaosthedude.explorerscompass.unified.data.LandingColumns;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** 天境的实际群系按高度叠置。探测穿过方块读取，玩家仅移动到核实的落脚点。 */
final class AetherHeightProbe {
    private final ServerPlayer player;
    private final ServerLevel level;
    private final ItemStack stack;
    private final ResourceLocation target;
    private final BlockPos origin;
    private final int minimum,maximum;
    private int column,offset;
    private ResourceLocation previous;
    private int changes,checked;
    private boolean relaxed;
    private Vec3 relaxedHint;
    AetherHeightProbe(ServerPlayer player,ItemStack stack,ResourceLocation target,BlockPos origin,boolean relaxed) {
        this.player=player;level=player.serverLevel();this.stack=stack;this.target=target;this.origin=origin;this.relaxed=relaxed;
        minimum=level.getMinBuildHeight()+4;
        maximum=level.getMaxBuildHeight()-(int)Math.ceil(player.getBbHeight());
    }
    boolean exhausted(){return column>=LandingColumns.STRICT.size();}
    boolean pending(boolean mode){return mode!=relaxed || !exhausted();}
    BlockPos waiting;
    Vec3 step(long deadline,boolean nowRelaxed) {
        waiting=null;
        if(nowRelaxed!=relaxed){relaxed=nowRelaxed;column=0;offset=0;previous=null;}
        if(relaxed && relaxedHint!=null) {
            Vec3 hint=relaxedHint;relaxedHint=null;
            if(matches(hint) && (LandingChecks.standing(player,level,stack,hint,true) || LandingChecks.waterStanding(player,level,stack,hint)))return hint;
        }
        int span=Math.max(Math.abs(origin.getY()-minimum),Math.abs(maximum-origin.getY()));
        while(!exhausted() && System.nanoTime()<deadline) {
            var c=LandingColumns.STRICT.get(column);
            int x=origin.getX()+c.x(),z=origin.getZ()+c.z();
            var p=new BlockPos(x,origin.getY(),z);
            if(!LandingChecks.ready(player,level,p,true)){waiting=p;return null;}
            if(offset>span*2){column++;offset=0;previous=null;continue;}
            int y=origin.getY()+LandingColumns.vertical(offset++);
            if(y<minimum || y>maximum)continue;
            p=p.atY(y);checked++;
            var biome=level.getBiome(p).unwrapKey().map(k->k.location()).orElse(null);
            if(biome!=null && !biome.equals(previous)){changes++;previous=biome;}
            if(!target.equals(biome))continue;
            Vec3 found=LandingChecks.find(player,level,stack,p,relaxed);
            if(found==null && relaxed)found=LandingChecks.waterSurface(player,level,stack,p);
            if(found!=null && matches(found))return found;
            // 严格阶段顺便缓存可用降级落点，不在第二次点击时重新执行整轮检查。
            if(!relaxed && relaxedHint==null){var hint=LandingChecks.find(player,level,stack,p,true);if(hint!=null && matches(hint))relaxedHint=hint;}
        }
        return null;
    }
    private boolean matches(Vec3 point){return level.getBiome(BlockPos.containing(point)).unwrapKey().map(k->target.equals(k.location())).orElse(false);}
    String summary(){return "checked="+checked+", changes="+changes+", columns="+column;}
}
