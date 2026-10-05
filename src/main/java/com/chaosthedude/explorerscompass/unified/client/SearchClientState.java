package com.chaosthedude.explorerscompass.unified.client;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.unified.network.SearchStatusPacket;
import net.minecraft.client.Minecraft;

public final class SearchClientState {
    private static SearchStatusPacket pending;
    private SearchClientState() {}
    public static void accept(SearchStatusPacket packet) { pending = packet; tick(); }
    public static void tick() {
        var player = Minecraft.getInstance().player;
        if (player == null || pending == null) return;
        for (var stack : java.util.List.of(player.getMainHandItem(), player.getOffhandItem())) {
            // 原版物品同步先确认是同一枚指南针；不把延迟通知写到另一枚物品。
            if (stack.getItem() == ExplorersCompass.explorersCompass && stack.getTag() != null
                    && stack.getTag().hasUUID("ECFiltersSearchToken")
                    && pending.token().equals(stack.getTag().getUUID("ECFiltersSearchToken"))) {
                stack.setTag(pending.tag().copy()); pending = null; return;
            }
        }
    }
    public static void clear() { pending = null; }
}
