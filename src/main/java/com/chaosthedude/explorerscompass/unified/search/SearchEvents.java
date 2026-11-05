package com.chaosthedude.explorerscompass.unified.search;

import com.david.compass.runtime.BudgetedSearchScheduler;

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
    private static final BudgetedSearchScheduler SCHEDULER = new BudgetedSearchScheduler(System::nanoTime);
    private SearchEvents() {}
    static void runSearchTick() {
        long budget=SearchConfig.TICK_MILLIS.get()*1_000_000L;
        long preparation=SearchService.takePreparationTime();
        long deadline=SCHEDULER.deadline(budget,preparation);
        TeleportService.maintain();
        EndDungeonIndex.maintain(Math.min(deadline,System.nanoTime()+250_000L));
        GoldenCrateIndex.maintain(Math.min(deadline,System.nanoTime()+100_000L));
        com.chaosthedude.explorerscompass.unified.creature.search.CreatureIndex.drain(Math.min(deadline,System.nanoTime()+100_000L));
        SCHEDULER.run(deadline,java.util.List.of(
                new BudgetedSearchScheduler.Lane(TeleportService.active()?1:0, ignored -> TeleportService.active() && TeleportService.step()),
                new BudgetedSearchScheduler.Lane(com.chaosthedude.explorerscompass.unified.creature.search.CreatureSearch.taskCount(),
                        com.chaosthedude.explorerscompass.unified.creature.search.CreatureSearch::step),
                SearchService.searchLane(preparation,budget)),
                com.chaosthedude.explorerscompass.unified.creature.search.CreaturePreExplore::step);
        SearchService.publishTickProgress();
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            com.chaosthedude.explorerscompass.unified.creature.search.CreaturePreExplore.maintain(event.getServer());
            runSearchTick();com.chaosthedude.explorerscompass.unified.entertainment.AdventureTravel.tick();
        }
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            com.chaosthedude.explorerscompass.unified.creature.search.CreaturePreExplore.cancel(player.getUUID());
            com.chaosthedude.explorerscompass.unified.entertainment.AdventureTravel.interrupt(player,"entertainment.interrupted");
            com.chaosthedude.explorerscompass.unified.creature.search.CreatureSearch.cancel(player.getUUID());
            SearchService.cancel(player, true); TeleportEvents.clear(player.getUUID());
            TeleportService.forget(player.getUUID());
        }
    }
    @SubscribeEvent public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            com.chaosthedude.explorerscompass.unified.creature.search.CreaturePreExplore.cancel(player.getUUID());
            com.chaosthedude.explorerscompass.unified.entertainment.AdventureTravel.interrupt(player,"entertainment.interrupted");
            com.chaosthedude.explorerscompass.unified.creature.search.CreatureSearch.cancel(player.getUUID());
            SearchService.cancel(player, true);
        }
    }
    @SubscribeEvent public static void reload(OnDatapackSyncEvent event) {
        if (event.getPlayer() == null) {
            invalidate(event.getPlayerList().getServer());
        }
    }
    public static void invalidate(net.minecraft.server.MinecraftServer server) {
        com.chaosthedude.explorerscompass.unified.entertainment.AdventureTravel.clear();
        com.chaosthedude.explorerscompass.unified.creature.search.CreatureSearch.clear();
        SearchService.stopAll(); com.chaosthedude.explorerscompass.unified.network.CatalogService.clear();
        com.chaosthedude.explorerscompass.unified.creature.network.CreatureNetwork.invalidateWorldCatalog(server);
        for (var player : server.getPlayerList().getPlayers()) com.chaosthedude.explorerscompass.unified.network.UnifiedNetwork.send(player,
                new com.chaosthedude.explorerscompass.unified.network.CatalogInvalidatedPacket());
    }
    @SubscribeEvent public static void stop(ServerStoppingEvent event) { com.chaosthedude.explorerscompass.unified.entertainment.AdventureTravel.clear();com.chaosthedude.explorerscompass.unified.creature.search.CreatureSearch.clear();SearchService.shutdown(); com.chaosthedude.explorerscompass.unified.network.CatalogService.clear(); }
}
