package com.chaosthedude.explorerscompass.unified.creature.search;

import com.david.compass.runtime.BudgetedSearchScheduler;
import com.chaosthedude.explorerscompass.unified.creature.CreatureIdentity;
import com.chaosthedude.explorerscompass.unified.creature.network.CreatureNetwork;
import com.david.compass.runtime.game.search.ServerSearchRuntime;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** 生物生命周期和背景预探索只从生物产品接入。 */
public final class CreatureServerParticipant implements ServerSearchRuntime.Participant {
    private CreatureServerParticipant(){}
    public static void register(){ServerSearchRuntime.register(CreatureIdentity.MOD_ID,new CreatureServerParticipant());}
    @Override public void maintain(MinecraftServer server,long deadline){
        CreaturePreExplore.maintain(server);
        CreatureIndex.drain(Math.min(deadline,System.nanoTime()+100_000L));
    }
    @Override public BudgetedSearchScheduler.Lane lane(long preparation,long budget){
        return new BudgetedSearchScheduler.Lane(CreatureSearch.taskCount(),CreatureSearch::step);
    }
    @Override public boolean background(long deadline){return CreaturePreExplore.step(deadline);}
    @Override public void leave(ServerPlayer player,boolean logout){
        CreaturePreExplore.cancel(player.getUUID());CreatureSearch.cancel(player.getUUID());
    }
    @Override public void clear(boolean stopping){CreatureSearch.clear();}
    @Override public void directoryInvalidated(MinecraftServer server){CreatureNetwork.invalidateWorldCatalog(server);}
}
