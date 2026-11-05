package com.chaosthedude.explorerscompass.unified.data;

import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

/** 基础目录只读取群系来源，不初始化结构放置、费用或任何产品状态。 */
public final class WorldDirectory {
    public record Snapshot(List<ResourceLocation> biomes,
                           Map<ResourceLocation, Set<ResourceLocation>> dimensions) {
        public Snapshot {
            biomes = List.copyOf(biomes);
            var copy = new LinkedHashMap<ResourceLocation, Set<ResourceLocation>>();
            dimensions.forEach((id, values) -> copy.put(id, Set.copyOf(values)));
            dimensions = Collections.unmodifiableMap(copy);
        }
    }
    private static final Map<MinecraftServer, Snapshot> CACHE = new IdentityHashMap<>();
    private WorldDirectory() {}
    public static Snapshot get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("目录只能由服务端线程准备");
        return CACHE.computeIfAbsent(server, current -> {
            var biomes = current.registryAccess().registryOrThrow(Registries.BIOME);
            var dimensions = new TreeMap<ResourceLocation, Set<ResourceLocation>>();
            for (var level : current.getAllLevels()) {
                var available = new LinkedHashSet<ResourceLocation>();
                level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes().forEach(holder -> {
                    var id = biomes.getKey(holder.value()); if (id != null) available.add(id);
                });
                dimensions.put(level.dimension().location(), available);
            }
            return new Snapshot(biomes.keySet().stream().sorted().toList(), dimensions);
        });
    }
    public static void clear() { CACHE.clear(); }
}
