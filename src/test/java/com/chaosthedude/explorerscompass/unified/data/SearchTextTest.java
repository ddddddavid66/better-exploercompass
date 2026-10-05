package com.chaosthedude.explorerscompass.unified.data;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SearchTextTest {
    @Test void plainsSupportsChineseFullPinyinAndInitials() {
        SearchText plains = SearchText.of("平原", "minecraft:plains");
        assertTrue(plains.matches("平原"));
        assertTrue(plains.matches("pingyuan"));
        assertTrue(plains.matches("PING YUAN"));
        assertTrue(plains.matches("py"));
        assertTrue(plains.matches("ＰＹ"));
        assertTrue(plains.matches("minecraft:plains"));
        assertFalse(plains.matches("沙漠"));
    }

    @Test void moddedChineseNamesUseTheSameIndex() {
        SearchText cave = SearchText.of("磁力洞穴", "alexscaves:magnetic_caves");
        assertTrue(cave.matches("磁力"));
        assertTrue(cave.matches("cilidongxue"));
        assertTrue(cave.matches("cldx"));
        assertTrue(cave.matches("alexscaves:"));
    }

    @Test void untranslatedIdsAndEmptyQueriesWork() {
        SearchText missing = SearchText.of("example:untranslated_biome", "example:untranslated_biome");
        assertTrue(missing.matches(""));
        assertTrue(missing.matches("UNTRANSLATED"));
        assertTrue(missing.matches("example:"));
        assertFalse(missing.matches("平原"));
    }
}
