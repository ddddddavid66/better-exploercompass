package com.chaosthedude.explorerscompass.unified.client;

import com.chaosthedude.explorerscompass.unified.CompassUnified;
import com.chaosthedude.explorerscompass.unified.data.BiomeFilter;
import com.chaosthedude.explorerscompass.unified.data.EnvironmentKey;
import com.chaosthedude.explorerscompass.unified.data.DimensionFilter;
import com.chaosthedude.explorerscompass.unified.data.StateStore;
import com.chaosthedude.explorerscompass.unified.network.BiomeCatalogPacket;
import com.chaosthedude.explorerscompass.unified.network.CatalogRequestPacket;
import com.chaosthedude.explorerscompass.unified.network.UnifiedNetwork;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.loading.FMLPaths;

public final class ClientSession {
    private static StateStore store;
    private static String environment;
    private static UUID pendingRequest;
    private static BiomeCatalogPacket catalog;
    private static ResourceLocation selected;
    private static ResourceLocation selectedDimension;
    private static ResourceLocation observedDimension;
    private static long requestedAt;
    private static long revision;
    private static long counterRevision;

    private ClientSession() {}

    public static void prepare() {
        if (environment != null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        if (store == null) { com.chaosthedude.explorerscompass.unified.data.ConfigMigration.importState(FMLPaths.CONFIGDIR.get(), CompassUnified.LOGGER::warn); store = new StateStore(FMLPaths.CONFIGDIR.get()
                .resolve(CompassUnified.MOD_ID).resolve("client-state.json"), CompassUnified.LOGGER::warn); }
        String address;
        String kind;
        if (minecraft.getSingleplayerServer() != null) {
            kind = "singleplayer";
            address = minecraft.getSingleplayerServer().getWorldPath(LevelResource.ROOT)
                    .toAbsolutePath().normalize().toString();
        } else if (minecraft.getCurrentServer() != null) {
            kind = "multiplayer";
            address = minecraft.getCurrentServer().ip;
        } else {
            return;
        }
        // 使用登录账号，而不是离线服务器可能重新分配的玩家 UUID。
        UUID account = minecraft.getUser().getProfileId();
        environment = EnvironmentKey.of(account == null ? minecraft.player.getUUID().toString() : account.toString(), kind, address);
        selected = com.chaosthedude.explorerscompass.unified.data.TargetDimension.parse(store.biome(environment));
        selectedDimension = com.chaosthedude.explorerscompass.unified.data.TargetDimension.parse(store.dimension(environment));
    }

    public static void requestCatalog() {
        prepare();
        if (environment == null || Minecraft.getInstance().getConnection() == null) return;
        UnifiedNetwork.CHANNEL.sendToServer(new com.chaosthedude.explorerscompass.unified.network.LandingModePacket(com.chaosthedude.explorerscompass.config.ConfigHandler.CLIENT.landingMode.get()));
        pendingRequest = UUID.randomUUID();
        requestedAt = System.currentTimeMillis();
        UnifiedNetwork.CHANNEL.sendToServer(new CatalogRequestPacket(pendingRequest));
    }

    public static void acceptCatalog(BiomeCatalogPacket response) {
        if (environment == null || !response.requestId().equals(pendingRequest)) return;
        if (!response.currentDimension().equals(currentDimension())) { requestCatalog(); return; }
        catalog = response;
        var groups = com.google.common.collect.ArrayListMultimap.<ResourceLocation, ResourceLocation>create();
        response.dimensionStructures().forEach((dimension, keys) -> keys.forEach(key -> groups.put(key, dimension)));
        com.chaosthedude.explorerscompass.ExplorersCompass.allowedStructureKeys = List.copyOf(response.structures().keySet());
        com.chaosthedude.explorerscompass.ExplorersCompass.structureKeysToTypeKeys = response.groups();
        com.chaosthedude.explorerscompass.ExplorersCompass.dimensionKeysForAllowedStructureKeys = groups;
        com.chaosthedude.explorerscompass.ExplorersCompass.xpLevelsForAllowedStructureKeys = response.xpLevels();
        com.chaosthedude.explorerscompass.ExplorersCompass.canTeleport = response.canTeleport();
        com.chaosthedude.explorerscompass.ExplorersCompass.maxNextSearches = response.maxNextSearches();
        com.chaosthedude.explorerscompass.ExplorersCompass.infiniteXp = response.infiniteXp();
        if (selectedDimension != null && !catalog.dimensionBiomes().containsKey(selectedDimension)) selectDimension(null);
        if (selected != null && !biomes().contains(selected)) select(null);
        revision++;
    }

    public static boolean prepareCounters() { prepare(); return environment != null; }
    public static void recordTeleport(com.chaosthedude.explorerscompass.unified.network.TeleportSucceededPacket event) {
        if (event.kind() == com.chaosthedude.explorerscompass.unified.network.TeleportSucceededPacket.Kind.BIOME)
            store.recordBiome(environment, event.target().toString());
        else store.record(environment, List.of(event.target().toString()));
        // 计数只影响绘制，不会清除选择或重置浮层滚动。
        counterRevision++;
    }
    public static long counterRevision() { return counterRevision; }

    public static void select(ResourceLocation biome) {
        selected = biome;
        if (environment != null) store.setBiome(environment, biome == null ? "" : biome.toString());
        revision++;
    }

    public static void selectDimension(ResourceLocation dimension) {
        selectedDimension = dimension;
        if (environment != null) store.setDimension(environment, dimension == null ? "" : dimension.toString());
        if (selected != null && !biomes().contains(selected)) select(null);
        revision++;
    }



    public static long biomeCount(ResourceLocation biome) {
        return environment == null ? 0 : store.biomeCount(environment, biome.toString());
    }

    public static ResourceLocation selectedDimension() { return selectedDimension; }
    public static ResourceLocation currentDimension() {
        var level = Minecraft.getInstance().level;
        return level == null ? null : level.dimension().location();
    }
    public static boolean canSearch() { return ready() && (selectedDimension == null || selectedDimension.equals(currentDimension())); }
    public static List<ResourceLocation> dimensions() { return catalog == null ? List.of() : List.copyOf(catalog.dimensionBiomes().keySet()); }
    public static List<ResourceLocation> biomeDimensions(ResourceLocation biome) {
        return catalog == null ? List.of() : catalog.dimensionBiomes().entrySet().stream()
                .filter(entry -> entry.getValue().contains(biome)).map(java.util.Map.Entry::getKey).toList();
    }
    public static void invalidate() {
        catalog = null; pendingRequest = null; revision++;
        var minecraft = Minecraft.getInstance();
        if (minecraft.player != null && !com.chaosthedude.explorerscompass.util.ItemUtils.getHeldItem(
                minecraft.player, com.chaosthedude.explorerscompass.ExplorersCompass.explorersCompass).isEmpty()) requestCatalog();
    }
    public static void tick() {
        ResourceLocation actual = currentDimension();
        if (!java.util.Objects.equals(actual, observedDimension)) { observedDimension = actual; invalidate(); }
        // 换维度时玩家和物品可能比 ClientLevel 晚恢复；待持有物恢复后补发，不依赖重新打开界面。
        var minecraft = Minecraft.getInstance();
        if (catalog == null && minecraft.player != null && minecraft.getConnection() != null
                && (pendingRequest == null || System.currentTimeMillis() - requestedAt > 20_000)
                && !com.chaosthedude.explorerscompass.util.ItemUtils.getHeldItem(minecraft.player,
                    com.chaosthedude.explorerscompass.ExplorersCompass.explorersCompass).isEmpty()) requestCatalog();
    }

    public static List<ResourceLocation> filter(Collection<ResourceLocation> keys) {
        return DimensionFilter.apply(keys, catalog == null ? null : selected, catalog == null ? null : selectedDimension,
                catalog == null ? java.util.Map.of() : catalog.structures(), catalog == null ? java.util.Map.of() : catalog.dimensionStructures());
    }

    public static long count(ResourceLocation id) {
        return environment == null ? 0 : store.count(environment, id.toString());
    }

    public static boolean ready() { return catalog != null; }
    public static long revision() { return revision; }
    public static ResourceLocation selected() { return selected; }
    public static List<ResourceLocation> biomes() { return catalog == null ? List.of()
            : DimensionFilter.biomes(catalog.biomes(), selectedDimension, catalog.dimensionBiomes()); }

    public static void clear() {
        environment = null;
        pendingRequest = null;
        catalog = null;
        selected = null;
        selectedDimension = null; observedDimension = null;
        TeleportClientState.clear();
        // 新连接必须等待新目录；避免上一服务器的权限和结构出现在首次打开的界面。
        com.chaosthedude.explorerscompass.ExplorersCompass.allowedStructureKeys = List.of();
        com.chaosthedude.explorerscompass.ExplorersCompass.structureKeysToTypeKeys = java.util.Map.of();
        com.chaosthedude.explorerscompass.ExplorersCompass.xpLevelsForAllowedStructureKeys = java.util.Map.of();
        com.chaosthedude.explorerscompass.ExplorersCompass.dimensionKeysForAllowedStructureKeys = com.google.common.collect.ArrayListMultimap.create();
        com.chaosthedude.explorerscompass.ExplorersCompass.canTeleport = false;
        com.chaosthedude.explorerscompass.ExplorersCompass.maxNextSearches = 0;
        com.chaosthedude.explorerscompass.ExplorersCompass.infiniteXp = false;
        revision++;
    }
}
