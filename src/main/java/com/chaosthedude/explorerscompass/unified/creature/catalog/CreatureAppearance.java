package com.chaosthedude.explorerscompass.unified.creature.catalog;

import java.util.List;
import net.minecraft.resources.ResourceLocation;

/** 一条出现规则只对应一个维度；未知群系不代表任意群系。证据全文不进入运行时。 */
public record CreatureAppearance(String kind, ResourceLocation dimension, List<ResourceLocation> biomes,
                                 String description, String verification) {
    public CreatureAppearance {
        biomes=List.copyOf(biomes);
    }
    public boolean supported(){return verification.equals("verified") || verification.equals("partial");}
    public boolean matches(ResourceLocation dim,ResourceLocation biome){
        return supported() && (dim==null || dim.equals(dimension)) && (biome==null || biomes.contains(biome));
    }
}
