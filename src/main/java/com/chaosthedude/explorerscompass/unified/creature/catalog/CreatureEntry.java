package com.chaosthedude.explorerscompass.unified.creature.catalog;

import java.util.List;
import net.minecraft.resources.ResourceLocation;

/** 空群系表示尚未补充，绝不表示任意群系。 */
public record CreatureEntry(ResourceLocation entity,String name,String english,List<String> aliases,
                            List<ResourceLocation> dimensions,List<ResourceLocation> biomes,String note) {
    public boolean matches(ResourceLocation dimension,ResourceLocation biome) {
        return (dimension==null || dimensions.contains(dimension)) && (biome==null || biomes.contains(biome));
    }
}
