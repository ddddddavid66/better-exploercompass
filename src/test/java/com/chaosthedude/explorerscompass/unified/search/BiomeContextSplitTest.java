package com.david.compass.runtime.game.search;

import com.david.compass.runtime.game.search.BiomeGenerationContext;
import com.david.compass.runtime.game.search.BiomeSearch;
import com.chaosthedude.explorerscompass.unified.creature.search.CreatureChunkPlanner;
import com.david.compass.runtime.game.search.HabitatSearch;
import com.david.compass.runtime.game.search.SearchCandidate;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BiomeContextSplitTest {
    @Test void memoFreezesPositionAndRetainsLongCost() {
        var position = new BlockPos.MutableBlockPos(1, 17, -3);
        var memo = new BiomeGenerationContext.Memo(position, 100L, 5_120_000_000L);
        position.set(9, 9, 9);
        assertEquals(new BlockPos(1, 17, -3), memo.position);
        assertEquals(5_120_000_000L, memo.cost);
        assertEquals(100L, memo.upper);
    }
    @Test void commonBiomeContextHasNoStructureOrProductImports() throws Exception {
        var text = source("BiomeGenerationContext");
        for (var forbidden : new String[]{"StructureSearch", "StructurePredictor", "SearchCandidate",
                "levelgen.structure", "CreatureSearch", "ExplorersCompass.explorersCompass"})
            assertFalse(text.contains(forbidden), forbidden);
    }
    @Test void biomeAndHabitatCallersDoNotLoadStructureCache() throws Exception {
        for (var name : new String[]{"BiomeSearch", "HabitatSearch", "CreatureChunkPlanner"}) {
            assertFalse(source(name).contains("WorldgenCache"), name);
            assertTrue(source(name).contains("BiomeGenerationContext.get(level)"), name);
        }
    }
    private String source(String name) throws Exception {
        String base=name.equals("CreatureChunkPlanner")
                ? "modules/creature/src/main/java/com/chaosthedude/explorerscompass/unified/creature/search/"
                : "modules/runtime/src/main/java/com/david/compass/runtime/game/search/";
        return Files.readString(Path.of(base+name+".java"));
    }
}
