package com.chaosthedude.explorerscompass.unified.search;

import java.util.Set;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraftforge.fml.ModList;

/** 只放行已审核版本的入口，不能按命名空间或继承关系信任第三方生成代码。 */
final class AetherStructures {
    private static final String BASE="com.aetherteam.aether.world.structure.";
    private static final String ANCIENT="net.builderdog.ancient_aether.world.structure.";
    private AetherStructures() {}
    static boolean base(Structure structure){
        return version("aether","1.4.2") && Set.of(BASE+"BronzeDungeonStructure",BASE+"SilverDungeonStructure",BASE+"GoldDungeonStructure").contains(structure.getClass().getName());
    }
    static boolean ancient(Structure structure){return version("ancient_aether","0.9.10") && Set.of(ANCIENT+"SkylandsJigsawStructure",ANCIENT+"BuriedJigsawStructure").contains(structure.getClass().getName());}
    static boolean platinum(Structure structure){return version("lost_aether_content","1.2.3") && structure.getClass().getName().equals("com.legacy.lost_aether.world.structure.PlatinumDungeonStructure");}
    static boolean supported(Structure structure){return base(structure)||ancient(structure)||platinum(structure);}
    static boolean moved(Structure structure){return base(structure) && structure.getClass().getSimpleName().equals("BronzeDungeonStructure") || ancient(structure) && structure.getClass().getSimpleName().equals("BuriedJigsawStructure");}
    static boolean version(String id,String expected){return ModList.get().getModContainerById(id).map(m->{String actual=m.getModInfo().getVersion().toString();return actual.equals(expected) || id.equals("aether") && expected.equals("1.4.2") && actual.equals("1.20.1-1.4.2-neoforge");}).orElse(false);}
}
