package com.chaosthedude.explorerscompass.unified.data;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BiomeFilter {
    private BiomeFilter() {}

    public static <K> List<K> apply(Collection<K> orderedStructures, K selectedBiome,
                                    Map<K, Set<K>> generatingBiomes) {
        // 返回新列表，避免原排序方式的候选缓存被群系条件永久删减。
        if (selectedBiome == null) return List.copyOf(orderedStructures);
        return orderedStructures.stream()
                .filter(key -> generatingBiomes.getOrDefault(key, Set.of()).contains(selectedBiome))
                .toList();
    }
}
