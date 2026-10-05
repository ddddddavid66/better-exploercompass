package com.chaosthedude.explorerscompass.unified.network;

import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/** 每次成功传送产生独立事件，重复传送同一目标仍然计数。 */
public record TeleportSucceededPacket(UUID eventId, UUID playerId, Kind kind, ResourceLocation target) {
    public enum Kind { STRUCTURE, BIOME }
    public static void encode(TeleportSucceededPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.eventId); buffer.writeUUID(packet.playerId);
        buffer.writeEnum(packet.kind); buffer.writeResourceLocation(packet.target);
    }
    public static TeleportSucceededPacket decode(FriendlyByteBuf buffer) {
        return new TeleportSucceededPacket(buffer.readUUID(), buffer.readUUID(), buffer.readEnum(Kind.class), buffer.readResourceLocation());
    }
    public static void handle(TeleportSucceededPacket packet, Supplier<NetworkEvent.Context> context) {
        var ctx = context.get();
        var connection = ctx.getNetworkManager();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.chaosthedude.explorerscompass.unified.client.TeleportClientState.accept(connection, packet)));
        ctx.setPacketHandled(true);
    }
}
