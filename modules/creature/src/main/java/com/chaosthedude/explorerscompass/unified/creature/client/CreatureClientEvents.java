package com.chaosthedude.explorerscompass.unified.creature.client;

import com.chaosthedude.explorerscompass.unified.creature.CreatureIdentity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid=CreatureIdentity.MOD_ID,value=Dist.CLIENT)
public final class CreatureClientEvents {
    private CreatureClientEvents(){}
    @SubscribeEvent public static void disconnect(ClientPlayerNetworkEvent.LoggingOut event){CreatureClient.clear();}
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
        if(event.phase==TickEvent.Phase.END)CreatureClient.tick();
    }
}
