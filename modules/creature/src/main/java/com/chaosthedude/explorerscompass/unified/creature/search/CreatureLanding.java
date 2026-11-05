package com.chaosthedude.explorerscompass.unified.creature.search;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
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
}
