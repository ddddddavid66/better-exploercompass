package com.chaosthedude.explorerscompass.unified.client;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.unified.network.UnifiedNetwork;
import com.chaosthedude.explorerscompass.unified.network.StructureSearchPacket;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/** 两个基座的按钮和双击都使用扩展协议；不调用不兼容的原搜索包构造器。 */
public final class SearchRequests {
    private SearchRequests() {}
    public static void structure(ResourceLocation key, boolean group) {
        if (key == null || !ClientSession.canSearch()) return;
        var keys = group ? ExplorersCompass.structureKeysToTypeKeys.entrySet().stream()
                .filter(entry -> key.equals(entry.getValue())).map(java.util.Map.Entry::getKey).toList() : List.of(key);
        if (ClientSession.filter(keys).isEmpty()) return;
        send(key, group, false);
    }
    public static void next() {
        if (!ClientSession.canSearch()) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        var stack = com.chaosthedude.explorerscompass.util.ItemUtils.getHeldItem(mc.player, ExplorersCompass.explorersCompass);
        if (com.chaosthedude.explorerscompass.unified.search.SearchService.isBiome(stack)) return;
        var key = ExplorersCompass.explorersCompass.getStructureKey(stack);
        if (key != null) send(key, false, true);
    }
    private static void send(ResourceLocation key, boolean group, boolean next) {
        UnifiedNetwork.CHANNEL.sendToServer(new StructureSearchPacket(key, group, next, ClientSession.selected(), ClientSession.currentDimension()));
        Minecraft.getInstance().setScreen(null);
    }
}
