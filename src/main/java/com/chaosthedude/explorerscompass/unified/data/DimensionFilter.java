package com.chaosthedude.explorerscompass.unified.data;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 维度和群系取交集，始终保留原排序结果的相对顺序。 */
public final class DimensionFilter {
    private DimensionFilter() {}
    public static <K> List<K> apply(Collection<K> ordered, K biome, K dimension,
            Map<K, Set<K>> structures, Map<K, Set<K>> dimensionStructures) {
        return BiomeFilter.apply(ordered, biome, structures).stream()
                .filter(key -> dimension == null || dimensionStructures.getOrDefault(dimension, Set.of()).contains(key)).toList();
    }
    public static <K> List<K> biomes(Collection<K> all, K dimension, Map<K, Set<K>> dimensionBiomes) {
        return all.stream().filter(key -> dimension == null || dimensionBiomes.getOrDefault(dimension, Set.of()).contains(key)).toList();
    }
}
