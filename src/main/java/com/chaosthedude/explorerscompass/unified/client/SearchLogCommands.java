package com.chaosthedude.explorerscompass.unified.client;

import com.chaosthedude.explorerscompass.unified.CompassUnified;
import net.minecraft.commands.Commands;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid=CompassUnified.MOD_ID,value=Dist.CLIENT)
public final class SearchLogCommands {
    private SearchLogCommands() {}
    @SubscribeEvent public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("david").then(Commands.literal("log")
            .executes(ctx->{SearchLogClient.status();return 1;})
            .then(Commands.literal("open").executes(ctx->{SearchLogClient.enabled(true);return 1;}))
            .then(Commands.literal("close").executes(ctx->{SearchLogClient.enabled(false);return 1;}))
            .then(Commands.literal("clsoe").executes(ctx->{SearchLogClient.enabled(false);return 1;}))
            .then(Commands.literal("sort").executes(ctx->{SearchLogClient.message(SearchLogClient.writer().sort()?"开始排序，后台处理期间可以继续搜索":"已有排序任务正在执行");return 1;}))));
    }
}
