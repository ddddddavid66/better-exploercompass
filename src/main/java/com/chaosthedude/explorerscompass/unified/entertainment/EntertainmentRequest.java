package com.chaosthedude.explorerscompass.unified.entertainment;

import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/** 客户端只指定功能和操作，不能提交目标坐标或进度条件。 */
public record EntertainmentRequest(Action action, UUID task) {
    public enum Action { ADVENTURING_TIME, AETHER_BIOMES, STOP, STATUS }
    public static void encode(EntertainmentRequest p, FriendlyByteBuf b) {
        b.writeEnum(p.action); b.writeBoolean(p.task != null); if (p.task != null) b.writeUUID(p.task);
    }
    public static EntertainmentRequest decode(FriendlyByteBuf b) {
        return new EntertainmentRequest(b.readEnum(Action.class), b.readBoolean() ? b.readUUID() : null);
    }
    public static void handle(EntertainmentRequest p, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            var player = context.get().getSender(); if (player == null) return;
            switch (p.action) {
                case ADVENTURING_TIME -> AdventureTravel.start(player,EntertainmentFeature.ADVENTURING_TIME);
                case AETHER_BIOMES -> AdventureTravel.start(player,EntertainmentFeature.AETHER_BIOMES);
                case STOP -> AdventureTravel.stop(player, p.task);
                case STATUS -> AdventureTravel.sync(player);
            }
        }); context.get().setPacketHandled(true);
    }
}
