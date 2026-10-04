package com.chaosthedude.explorerscompass.unified.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BiomeFilterTest {
    private final Map<String, Set<String>> biomes = Map.of(
            "village", Set.of("plains"), "mineshaft", Set.of("desert", "plains"),
            "temple", Set.of("desert"));

    @Test void defaultPreservesEverySortingOrder() {
        for (List<String> order : List.of(List.of("village", "temple", "mineshaft"),
                List.of("temple", "mineshaft", "village"), List.of("mineshaft", "village", "temple"))) {
            assertEquals(order, BiomeFilter.apply(order, null, biomes));
        }
    }

    @Test void biomeIntersectsExistingSearchResultsWithoutMutatingThem() {
        List<String> searchResults = new ArrayList<>(List.of("temple", "mineshaft", "village"));
        assertEquals(List.of("mineshaft", "village"), BiomeFilter.apply(searchResults, "plains", biomes));
        assertEquals(List.of("temple", "mineshaft", "village"), searchResults);
        assertEquals(searchResults, BiomeFilter.apply(searchResults, null, biomes));
        assertEquals(List.of("mineshaft"), BiomeFilter.apply(List.of("temple", "mineshaft"), "plains", biomes));
    }

    @Test void unknownBiomesAndMissingStructureMetadataDoNotMatch() {
        assertTrue(BiomeFilter.apply(List.of("village"), "example:missing", biomes).isEmpty());
        assertTrue(BiomeFilter.apply(List.of("unknown"), "plains", biomes).isEmpty());
    }

    @Test void groupCandidatesKeepTheirOrderAndMatchOnlyTheSelectedBiome() {
        assertEquals(List.of("temple", "mineshaft"),
                BiomeFilter.apply(List.of("temple", "village", "mineshaft"), "desert", biomes));
    }
}
