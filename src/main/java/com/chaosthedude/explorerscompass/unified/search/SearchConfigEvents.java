package com.chaosthedude.explorerscompass.unified.search;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

/** 配置文件监视线程不直接访问任务和世界，统一切回服务端线程失效目录。 */
@Mod.EventBusSubscriber(modid = ExplorersCompass.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class SearchConfigEvents {
    private SearchConfigEvents() {}
    @SubscribeEvent public static void reload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != ConfigHandler.GENERAL_SPEC) return;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) server.execute(() -> SearchEvents.invalidate(server));
    }
}
