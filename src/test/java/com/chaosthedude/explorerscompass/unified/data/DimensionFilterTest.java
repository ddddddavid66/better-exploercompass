package com.chaosthedude.explorerscompass.unified.data;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DimensionFilterTest {
    private final List<String> ordered = List.of("b", "a", "c");
    private final Map<String,Set<String>> structures = Map.of("a",Set.of("plains"),"b",Set.of("plains","cave"),"c",Set.of("forest"));
    private final Map<String,Set<String>> placed = Map.of("world",Set.of("a","c"),"custom",Set.of("b"));
    @Test void defaultKeepsOriginalOrderAndBiomeFilterStillComposes() {
        assertEquals(ordered, DimensionFilter.apply(ordered,null,null,structures,placed));
        assertEquals(List.of("b","a"), DimensionFilter.apply(ordered,"plains",null,structures,placed));
    }
    @Test void dimensionAndBiomeIntersectWithoutReordering() {
        assertEquals(List.of("a","c"), DimensionFilter.apply(ordered,null,"world",structures,placed));
        assertEquals(List.of("a"), DimensionFilter.apply(ordered,"plains","world",structures,placed));
        assertTrue(DimensionFilter.apply(ordered,"forest","custom",structures,placed).isEmpty());
        assertTrue(DimensionFilter.apply(ordered,null,"missing",structures,placed).isEmpty());
    }
    @Test void oneBiomeCanAppearInMultipleDimensionsAndUnusedBiomesRemainInDefault() {
        List<String> all = List.of("unused","plains","cave");
        var biomes = Map.of("world",Set.of("plains"),"custom",Set.of("plains","cave"));
        assertEquals(all, DimensionFilter.biomes(all,null,biomes));
        assertEquals(List.of("plains"), DimensionFilter.biomes(all,"world",biomes));
        assertEquals(List.of("plains","cave"), DimensionFilter.biomes(all,"custom",biomes));
    }
    @Test void chineseDimensionsSupportFullPinyinInitialsAndId() {
        var name = SearchText.of("主世界","minecraft:overworld");
        assertTrue(name.matches("主世")); assertTrue(name.matches("zhushijie")); assertTrue(name.matches("zsj"));
        assertTrue(name.matches("overworld"));
    }
    @Test void inheritedEnglishIsNotMistakenForChineseDimensionTranslation() {
        assertFalse(LocalizedNames.usable("Twilight Forest", true));
        assertTrue(LocalizedNames.usable("暮色森林", true));
        assertTrue(LocalizedNames.usable("Twilight Forest", false));
        assertFalse(LocalizedNames.usable("",false));
    }
}
