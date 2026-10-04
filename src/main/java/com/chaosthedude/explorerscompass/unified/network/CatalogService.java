package com.chaosthedude.explorerscompass.unified.network;

import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.util.PlayerUtils;
import java.util.*;
import java.util.regex.Pattern;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** 一份不可变生成目录同时用于界面、联合筛选、分组和服务端验证。 */
public final class CatalogService {
    private record ConfigKey(List<String> blacklist, List<String> overrides, int defaultCost) {
        @Override public boolean equals(Object other) {
            return this==other || other instanceof ConfigKey key && defaultCost==key.defaultCost
                    && blacklist.equals(key.blacklist) && overrides.equals(key.overrides);
        }
        @Override public int hashCode() { return 31*(31*blacklist.hashCode()+overrides.hashCode())+defaultCost; }
    }
    private record Snapshot(ConfigKey config, BiomeCatalogPacket packet) {}
    private record Cost(Pattern pattern, int amount) {}
    private static final Map<MinecraftServer, Snapshot> CACHE = new IdentityHashMap<>();
    private CatalogService() {}
    public static void clear() { CACHE.clear(); }
    private static Pattern pattern(String glob) {
        return Pattern.compile(Pattern.quote(glob).replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q"));
    }
    private static BiomeCatalogPacket directory(ServerLevel level) {
        var config = ConfigHandler.GENERAL;
        ConfigKey key = new ConfigKey(List.copyOf(config.structureBlacklist.get()), List.copyOf(config.perStructureXpLevels.get()), config.defaultXpLevels.get());
        Snapshot previous = CACHE.get(level.getServer());
        if (previous != null && previous.config.equals(key)) return previous.packet;
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        var structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        var blacklists = key.blacklist.stream().map(CatalogService::pattern).toList();
        List<Cost> rules = new ArrayList<>();
        for (String override : key.overrides) {
            String[] parts = override.split(",", 2);
            if (parts.length != 2) continue;
            try { rules.add(new Cost(pattern(parts[0]), Math.max(0, Math.min(3, Integer.parseInt(parts[1]))))); }
            catch (NumberFormatException ignored) { }
        }
        Map<ResourceLocation, Set<ResourceLocation>> mapping = new LinkedHashMap<>();
        Map<ResourceLocation, Integer> costs = new LinkedHashMap<>();
        for (ResourceLocation id : structures.keySet().stream().sorted().toList()) {
            costs.put(id, cost(id, rules, key.defaultCost));
            if (blacklists.stream().anyMatch(rule -> rule.matcher(id.toString()).matches())) continue;
            var generating = new LinkedHashSet<ResourceLocation>();
            structures.get(id).biomes().forEach(holder -> { var biome = biomes.getKey(holder.value()); if (biome != null) generating.add(biome); });
            mapping.put(id, Set.copyOf(generating));
        }
        
        var end = level.getServer().getLevel(net.minecraft.world.level.Level.END);
        var featureKey = com.chaosthedude.explorerscompass.unified.search.EndDungeonAdapter.KEY;
        boolean endFeature = end != null && com.chaosthedude.explorerscompass.unified.search.EndDungeonAdapter.special(end, featureKey);
        if (endFeature && blacklists.stream().noneMatch(rule -> rule.matcher(featureKey.toString()).matches())) {
            mapping.put(featureKey, com.chaosthedude.explorerscompass.unified.search.EndDungeonAdapter.biomes(end));
            costs.put(featureKey, cost(featureKey, rules, key.defaultCost));
        }
        Map<ResourceLocation, ResourceLocation> groups = new LinkedHashMap<>();
        var sets = level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET);
        // 扫描一次建立反向索引，避免每个结构重新遍历全部结构组。
        for (var set : sets) {
            var group = sets.getKey(set); costs.put(group, cost(group, rules, key.defaultCost));
            for (var entry : set.structures()) {
                var id = structures.getKey(entry.structure().value());
                if (mapping.containsKey(id)) groups.putIfAbsent(id, group);
            }
        }
        if (endFeature && mapping.containsKey(featureKey)) groups.put(featureKey, featureKey);
        mapping.keySet().forEach(id -> groups.putIfAbsent(id, new ResourceLocation("explorerscompass:none")));
        Map<ResourceLocation, Set<ResourceLocation>> dimensionBiomes = new LinkedHashMap<>();
        Map<ResourceLocation, Set<ResourceLocation>> dimensionStructures = new LinkedHashMap<>();
        for (ServerLevel dimension : level.getServer().getAllLevels()) {
            Set<ResourceLocation> available = new LinkedHashSet<>();
            dimension.getChunkSource().getGenerator().getBiomeSource().possibleBiomes().forEach(holder -> {
                var id = biomes.getKey(holder.value()); if (id != null) available.add(id);
            });
            var dimensionId = dimension.dimension().location(); dimensionBiomes.put(dimensionId, Set.copyOf(available));
            var state = dimension.getChunkSource().getGeneratorState(); state.ensureStructuresGenerated();
            Set<ResourceLocation> placed = new LinkedHashSet<>();
            for (var entry : mapping.entrySet()) {
                
                if (endFeature && entry.getKey().equals(featureKey)) {
                    if (dimension.dimension().equals(net.minecraft.world.level.Level.END) && entry.getValue().stream().anyMatch(available::contains)) placed.add(featureKey);
                    continue;
                }
                var holder = structures.getHolderOrThrow(net.minecraft.resources.ResourceKey.create(Registries.STRUCTURE, entry.getKey()));
                if (!state.getPlacementsForStructure(holder).isEmpty() && entry.getValue().stream().anyMatch(available::contains)) placed.add(entry.getKey());
            }
            dimensionStructures.put(dimensionId, Set.copyOf(placed));
        }
        var packet = new BiomeCatalogPacket(new UUID(0,0), biomes.keySet().stream().sorted().toList(), Map.copyOf(mapping),
                level.dimension().location(), Map.copyOf(dimensionBiomes), Map.copyOf(dimensionStructures), Map.copyOf(groups), Map.copyOf(costs), false, 0, false);
        CACHE.put(level.getServer(), new Snapshot(key, packet)); return packet;
    }
    private static int cost(ResourceLocation key, List<Cost> rules, int fallback) {
        for (Cost rule : rules) if (rule.pattern.matcher(key.toString()).matches()) return rule.amount;
        return fallback;
    }
    public static List<ResourceLocation> allowed(ServerLevel level) { return List.copyOf(directory(level).structures().keySet()); }
    /** 请求验证直接使用不可变目录集合，避免为单个 ID 复制整个结构列表。 */
    public static Set<ResourceLocation> allowedSet(ServerLevel level) { return directory(level).structures().keySet(); }
    public static Map<ResourceLocation, ResourceLocation> groups(ServerLevel level) { return directory(level).groups(); }
    public static int xp(ServerLevel level, ResourceLocation key) {
        return directory(level).xpLevels().getOrDefault(key, ConfigHandler.GENERAL.defaultXpLevels.get());
    }
    public static BiomeCatalogPacket snapshot(ServerLevel level, UUID requestId) { return response(level, requestId, false, false); }
    public static BiomeCatalogPacket snapshot(ServerPlayer player, UUID requestId) {
        return response(player.serverLevel(), requestId, ConfigHandler.GENERAL.allowTeleport.get() && PlayerUtils.canTeleport(player.getServer(), player), player.getAbilities().instabuild);
    }
    private static BiomeCatalogPacket response(ServerLevel level, UUID requestId, boolean teleport, boolean infiniteXp) {
        var data = directory(level);
        return new BiomeCatalogPacket(requestId, data.biomes(), data.structures(), level.dimension().location(), data.dimensionBiomes(),
            data.dimensionStructures(), data.groups(), data.xpLevels(), teleport, ConfigHandler.GENERAL.maxNextSearches.get(), infiniteXp);
    }
}
