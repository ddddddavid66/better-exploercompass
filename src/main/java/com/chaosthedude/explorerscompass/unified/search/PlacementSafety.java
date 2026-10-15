package com.chaosthedude.explorerscompass.unified.search;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.*;
import net.minecraftforge.fml.ModList;

/** 在主线程审核完整排除依赖图；共享或循环依赖不因根节点类型而自动放行。 */
final class PlacementSafety {
    static boolean supported(ServerLevel level,StructurePlacement placement) {
        try {return inspect(level,placement,Collections.newSetFromMap(new IdentityHashMap<>()));}
        catch(RuntimeException unknown) {return false;}
    }
    private static boolean inspect(ServerLevel level,StructurePlacement placement,Set<StructurePlacement> path) {
        if(path.size()>=32 || !path.add(placement))return false;
        try {
            boolean vanilla=placement.getClass()==RandomSpreadStructurePlacement.class
                    || placement.getClass()==ConcentricRingsStructurePlacement.class;
            boolean jungle=placement.getClass().getName().equals(
                    "com.yungnickyoung.minecraft.betterjungletemples.world.placement.BetterJungleTemplePlacement")
                    && version("betterjungletemples","1.20-Forge-2.0.5") && version("yungsapi","1.20-Forge-4.0.5");
            boolean cataclysm=placement.getClass().getName().equals("com.github.L_Ender.cataclysm.world.structures.placements.CataclysmRandomSpread") && version("cataclysm","3.16");
            if(!vanilla && !jungle && !cataclysm)return false;
            var json=StructurePlacement.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE,level.registryAccess()),placement)
                    .result().orElseThrow().getAsJsonObject();
            for(String field:java.util.List.of("exclusion_zone","enhanced_exclusion_zone","super_exclusion_zone"))
                if(json.has(field) && !references(level,json.getAsJsonObject(field).get("other_set"),path))return false;
            return true;
        } finally {path.remove(placement);}
    }
    private static boolean references(ServerLevel level,JsonElement value,Set<StructurePlacement> path) {
        if(value==null)return false;
        if(value.isJsonArray()) {
            for(var entry:value.getAsJsonArray())if(!references(level,entry,path))return false;
            return true;
        }
        if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())return false;
        String id=value.getAsString();var registry=level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET);
        if(id.startsWith("#")) {
            var tag=registry.getTag(TagKey.create(Registries.STRUCTURE_SET,new ResourceLocation(id.substring(1))));
            if(tag.isEmpty())return false;
            for(var set:tag.get())if(!inspect(level,set.value().placement(),path))return false;
            return true;
        }
        StructureSet set=registry.get(new ResourceLocation(id));
        return set!=null && inspect(level,set.placement(),path);
    }
    private static boolean version(String id,String version) {
        return ModList.get().getModContainerById(id).map(mod->version.equals(mod.getModInfo().getVersion().toString())).orElse(false);
    }
    private PlacementSafety() {}
}
