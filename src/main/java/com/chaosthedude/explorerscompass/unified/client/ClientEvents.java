package com.chaosthedude.explorerscompass.unified.client;

import com.chaosthedude.explorerscompass.unified.CompassUnified;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = CompassUnified.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {
    private ClientEvents() {}

    @SubscribeEvent
    public static void disconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        SearchLogClient.disconnect();
        ClientSession.clear();
        SearchClientState.clear(); LandingClientState.clear();
    }
    @SubscribeEvent public static void tick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase == net.minecraftforge.event.TickEvent.Phase.END) { SearchClientState.tick(); ClientSession.tick(); TeleportClientState.tick(); LandingClientState.tick(); }
    }
}
