package com.chaosthedude.explorerscompass.unified.search;

import com.david.compass.runtime.game.search.ServerSearchRuntime;

import com.david.compass.runtime.BudgetedSearchScheduler;
import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.unified.entertainment.AdventureTravel;
import com.chaosthedude.explorerscompass.unified.network.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** 探险者参与者，不注册独立服务器 tick。 */
public final class SearchEvents implements ServerSearchRuntime.Participant {
    private SearchEvents(){}
    public static void register(){ServerSearchRuntime.register(ExplorersCompass.MODID,new SearchEvents());}
    static void runSearchTick(){ServerSearchRuntime.tick(net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer());}
    public static void invalidate(MinecraftServer server){ServerSearchRuntime.invalidate(server);}
    @Override public long takePreparationTime(){return SearchService.takePreparationTime();}
    @Override public void maintain(MinecraftServer server,long deadline){
        EndDungeonIndex.maintain(Math.min(deadline,System.nanoTime()+250_000L));
        GoldenCrateIndex.maintain(Math.min(deadline,System.nanoTime()+100_000L));
    }
    @Override public BudgetedSearchScheduler.Lane lane(long preparation,long budget){return SearchService.searchLane(preparation,budget);}
    @Override public void afterTick(){SearchService.publishTickProgress();AdventureTravel.tick();}
    @Override public void leave(ServerPlayer player,boolean logout){
        AdventureTravel.interrupt(player,"entertainment.interrupted");SearchService.cancel(player,true);
        if(logout)TeleportEvents.clear(player.getUUID());
    }
    @Override public void clear(boolean stopping){
        AdventureTravel.clear();
        if(stopping)SearchService.shutdown();else SearchService.clearProduct();
        CatalogService.clear();
    }
    @Override public void directoryInvalidated(MinecraftServer server){
        for(var player:server.getPlayerList().getPlayers())UnifiedNetwork.send(player,new CatalogInvalidatedPacket());
    }
}
