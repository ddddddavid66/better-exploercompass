package com.chaosthedude.explorerscompass.unified.creature.catalog;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Catalog121Test {
    private static ResourceLocation id(String text){return new ResourceLocation(text);}
    @Test void pairedFiltersNeverInventCartesianProduct() {
        var overworld=id("minecraft:overworld");var aether=id("aether:the_aether");var forest=id("minecraft:forest");var skyroot=id("aether:skyroot_forest");
        var appearances=List.of(new CreatureAppearance("natural_spawn",overworld,List.of(forest),"自然生成","verified"),new CreatureAppearance("summoned",aether,List.of(skyroot),"由测试召唤条件出现","partial"));
        var entry=new CreatureEntry(id("test:creature"),"生物","Creature",List.of(),List.of(overworld,aether),List.of(forest,skyroot),"",appearances,true);
        assertTrue(entry.matches(overworld,forest));assertTrue(entry.matches(aether,skyroot));assertFalse(entry.matches(aether,forest));assertFalse(entry.matches(overworld,skyroot));
        var decoded=CreatureCatalog.decode(CreatureCatalog.encode(entry));assertEquals(entry,decoded);assertFalse(decoded.matches(aether,forest));
    }
    @Test void unknownBiomesOnlyAppearUnderDefaultAndOldFormatStillReads() {
        var dim=id("minecraft:overworld");var biome=id("minecraft:plains");
        var unknown=new CreatureEntry(id("test:unknown"),"待补","Unknown",List.of(),List.of(dim),List.of(),"",List.of(new CreatureAppearance("structure",dim,List.of(),"结构规则已核实，群系待补","partial")),true);
        assertTrue(unknown.matches(dim,null));assertFalse(unknown.matches(dim,biome));
        assertFalse(new CreatureAppearance("unknown",dim,List.of(biome),"猜测","unverified").matches(dim,biome));
        assertFalse(new CreatureAppearance("unknown",dim,List.of(biome),"无状态","").matches(dim,biome));
        var old=new CreatureEntry(id("test:legacy"),"旧条目","Legacy",List.of(),List.of(dim),List.of(biome),"");
        assertTrue(CreatureCatalog.decode(CreatureCatalog.encode(old)).matches(dim,biome));assertFalse(old.paired());
    }
}
