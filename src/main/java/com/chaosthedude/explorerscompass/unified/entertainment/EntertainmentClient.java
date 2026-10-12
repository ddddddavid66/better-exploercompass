package com.chaosthedude.explorerscompass.unified.entertainment;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import com.chaosthedude.explorerscompass.unified.client.FilterUi;
import com.chaosthedude.explorerscompass.unified.network.UnifiedNetwork;

/** 只保存当前连接的运行状态；关闭界面不取消服务端旅行。 */
public final class EntertainmentClient {
    private static EntertainmentStatus state;
    private EntertainmentClient() {}
    public static void accept(Connection source, EntertainmentStatus packet) {
        var mc = Minecraft.getInstance();
        if (mc.getConnection() == null || mc.getConnection().getConnection() != source) return;
        if (state != null && packet.revision() <= state.revision()) return;
        state = packet;
    }
    public static EntertainmentStatus state() { return state; }
    public static boolean active() { return state != null && state.active(); }
    public static void clear() { state = null; }
    public static void request(EntertainmentRequest.Action action) {
        UnifiedNetwork.CHANNEL.sendToServer(new EntertainmentRequest(action, state == null ? null : state.task()));
    }
    public static Component summary() {
        if (state == null) return Component.translatable("entertainment.description");
        var text = Component.translatable(state.feature().key()).append(" · ")
                .append(Component.translatable("entertainment.progress", state.completed(), state.total()))
                .append(" · ").append(Component.translatable("entertainment.phase." + state.phase()));
        text.append("\n").append(Component.translatable(state.mode().key()))
                .append(" · ").append(Component.translatable("entertainment.attempt",state.attempts(),5));
        if (state.biome() != null) text.append("\n").append(FilterUi.biomeName(state.biome()));
        if (!state.detail().isEmpty()) text.append("\n").append(Component.translatable(state.detail()));
        return text;
    }
}
