package com.chaosthedude.explorerscompass.unified.search;

import com.chaosthedude.explorerscompass.unified.CompassUnified;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = CompassUnified.MOD_ID)
public final class SearchEvents {
    private SearchEvents() {}
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) SearchService.tick();
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SearchService.cancel(player, true); TeleportEvents.clear(player.getUUID());
            TeleportService.forget(player.getUUID());
        }
    }
    @SubscribeEvent public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) SearchService.cancel(player, true);
    }
    @SubscribeEvent public static void reload(OnDatapackSyncEvent event) {
        if (event.getPlayer() == null) {
            invalidate(event.getPlayerList().getServer());
        }
    }
    public static void invalidate(net.minecraft.server.MinecraftServer server) {
        SearchService.stopAll(); com.chaosthedude.explorerscompass.unified.network.CatalogService.clear();
        for (var player : server.getPlayerList().getPlayers()) com.chaosthedude.explorerscompass.unified.network.UnifiedNetwork.send(player,
                new com.chaosthedude.explorerscompass.unified.network.CatalogInvalidatedPacket());
    }
    @SubscribeEvent public static void stop(ServerStoppingEvent event) { SearchService.shutdown(); com.chaosthedude.explorerscompass.unified.network.CatalogService.clear(); }
}
