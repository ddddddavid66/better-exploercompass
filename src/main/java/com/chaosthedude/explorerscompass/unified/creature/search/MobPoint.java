package com.chaosthedude.explorerscompass.unified.creature.search;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;

/** 后台仅持有数值快照，不能持有实体或世界引用。 */
public record MobPoint(UUID uuid,ResourceLocation type,ResourceLocation dimension,ResourceLocation biome,double x,double y,double z,boolean live) {
    public double distance(double ox,double oz){double dx=x-ox,dz=z-oz;return dx*dx+dz*dz;}
    public CompoundTag encode(){var n=new CompoundTag();n.putUUID("uuid",uuid);n.putString("type",type.toString());n.putString("dimension",dimension.toString());n.putString("biome",biome.toString());n.putDouble("x",x);n.putDouble("y",y);n.putDouble("z",z);n.putBoolean("live",live);return n;}
    public static MobPoint decode(CompoundTag n){return new MobPoint(n.getUUID("uuid"),new ResourceLocation(n.getString("type")),new ResourceLocation(n.getString("dimension")),new ResourceLocation(n.getString("biome")),n.getDouble("x"),n.getDouble("y"),n.getDouble("z"),n.getBoolean("live"));}
    public MobPoint unloaded(){return new MobPoint(uuid,type,dimension,biome,x,y,z,false);}
}
