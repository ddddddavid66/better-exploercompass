package com.chaosthedude.explorerscompass.unified.creature.catalog;

import java.util.List;
import net.minecraft.resources.ResourceLocation;

/** 空群系表示尚未补充，绝不表示任意群系。 */
public record CreatureEntry(ResourceLocation entity,String name,String english,List<String> aliases,
                            List<ResourceLocation> dimensions,List<ResourceLocation> biomes,String note,
                            List<CreatureAppearance> appearances,boolean paired) {
    public CreatureEntry(ResourceLocation entity,String name,String english,List<String> aliases,
                         List<ResourceLocation> dimensions,List<ResourceLocation> biomes,String note) {
        this(entity,name,english,aliases,dimensions,biomes,note,List.of(),false);
    }
    public boolean matches(ResourceLocation dimension,ResourceLocation biome) {
        if(paired)return appearances.stream().anyMatch(a->a.matches(dimension,biome));
        return (dimension==null || dimensions.contains(dimension)) && (biome==null || biomes.contains(biome));
    }
}
