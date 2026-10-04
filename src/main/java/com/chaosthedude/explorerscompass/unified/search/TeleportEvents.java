package com.chaosthedude.explorerscompass.unified.search;

import com.chaosthedude.explorerscompass.unified.network.UnifiedNetwork;
import com.chaosthedude.explorerscompass.unified.network.TeleportSucceededPacket;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** 只由指南针成功传送入口调用，命令或搜索请求不会触发。 */
public final class TeleportEvents {
    private static final java.util.Map<UUID, TeleportSucceededPacket> latest = new java.util.HashMap<>();
    private TeleportEvents() {}
    public static TeleportSucceededPacket latest(UUID player) { return latest.get(player); }
    public static void clear(UUID player) { latest.remove(player); }
    public static void clear() { latest.clear(); }
    public static void succeeded(ServerPlayer player, TeleportSucceededPacket.Kind kind, ResourceLocation target,
                                 ResourceLocation dimension, double x, double y, double z) {
        if (target == null || !player.level().dimension().location().equals(dimension)
                || player.distanceToSqr(x, y, z) > .0001) return;
        var packet = new TeleportSucceededPacket(UUID.randomUUID(), player.getUUID(), kind, target);
        latest.put(player.getUUID(), packet);
        UnifiedNetwork.send(player, packet);
    }
}
