package com.chaosthedude.explorerscompass.unified.data;

import com.chaosthedude.explorerscompass.unified.creature.search.*;
import com.chaosthedude.explorerscompass.unified.creature.catalog.CreatureEntry;
import java.util.*;
import java.nio.file.*;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class Creature120Test {
    private static ResourceLocation id(String s){return new ResourceLocation(s);}
    @Test void legacyExtensionIsUpgradedAndChunkRoundsAreBounded(@TempDir Path dir)throws Exception {
        Path old=dir.resolve("old.toml"),common=dir.resolve("new.toml");Files.writeString(old,"searchRadius = 40000\nmaxStructureSamples = 25000000\nmaxBiomeSamples = 8000000\nasyncBiomeSearch = false\n");assertTrue(ConfigMigration.merge(common,old,s->{}));String text=Files.readString(common);assertTrue(text.contains("searchRadius = 80000"));assertTrue(text.contains("maxBiomeSamples = 32000000"));assertTrue(text.contains("asyncBiomeSearch = false"));
        for(int rounds:new int[]{0,4,32}){var plan=CreatureSearch.chunkPlan(new net.minecraft.core.BlockPos(-17,64,31),rounds*64);assertEquals(rounds*64,plan.size());assertEquals(plan.size(),new HashSet<>(plan).size());}
        Files.writeString(common,"[Search]\nconfigVersion = 1\nsearchRadius = 160000\nmaxStructureSamples = 200000000\nmaxBiomeSamples = 64000000\n");assertTrue(ConfigMigration.merge(common,old,s->{}));text=Files.readString(common);assertTrue(text.contains("searchRadius = 160000"));assertTrue(text.contains("maxStructureSamples = 200000000"));assertTrue(text.contains("maxBiomeSamples = 64000000"));
    }
    @Test void emptyBiomeIsNotWildcardAndBilingualPinyinWorks(){var e=new CreatureEntry(id("alexsmobs:grizzly_bear"),"灰熊","Grizzly Bear",List.of(),List.of(id("minecraft:overworld")),List.of(),"");assertTrue(e.matches(id("minecraft:overworld"),null));assertFalse(e.matches(null,id("minecraft:forest")));var text=SearchText.of("灰熊 Grizzly Bear",e.entity().toString());for(String s:List.of("灰熊","grizzly","huixiong","hx","alexsmobs:grizzly_bear"))assertTrue(text.matches(s));}
    @Test void gridUpdatesByUuidAndRanksNegativePositions(){var grid=new MobGrid();var uuid=UUID.randomUUID();var dim=id("minecraft:overworld");var type=id("minecraft:cow");var biome=id("minecraft:plains");grid.put(new MobPoint(uuid,type,dim,biome,-80,60,0,true));grid.put(new MobPoint(uuid,type,dim,biome,70,60,0,true));assertEquals(1,grid.snapshot().size());grid.put(new MobPoint(UUID.randomUUID(),type,dim,biome,-2,60,-3,true));assertEquals(-2,MobGrid.nearest(grid.snapshot(),0,0,80000).get(0).x());grid.remove(uuid);assertEquals(1,grid.snapshot().size());}
    @Test void migrationBacksUpAndPreservesHigherValues(@TempDir Path dir)throws Exception {
        Path common=dir.resolve("common.toml");Files.writeString(common,"[General]\nallowTeleport = false\n[Search]\nconfigVersion = 1\nsearchRadius = 40000\nmaxStructureSamples = 25000000\nmaxBiomeSamples = 8000000\nasyncBiomeSearch = false\n");assertTrue(ConfigMigration.merge(common,dir.resolve("none"),s->{}));String s=Files.readString(common);assertTrue(s.contains("searchRadius = 80000"));assertTrue(s.contains("maxStructureSamples = 100000000"));assertTrue(s.contains("maxBiomeSamples = 32000000"));assertTrue(s.contains("asyncBiomeSearch = false"));assertTrue(s.contains("allowTeleport = false"));assertFalse(ConfigMigration.merge(common,dir.resolve("none"),m->{}));assertTrue(Files.list(dir).count()>1);
    }
}
