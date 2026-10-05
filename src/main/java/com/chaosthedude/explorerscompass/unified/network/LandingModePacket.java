package com.chaosthedude.explorerscompass.unified.network;

import com.chaosthedude.explorerscompass.unified.data.LandingMode;
import com.chaosthedude.explorerscompass.unified.search.TeleportService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;

public record LandingModePacket(LandingMode mode) {
    public static void encode(LandingModePacket p, FriendlyByteBuf b) { b.writeEnum(p.mode); }
    public static LandingModePacket decode(FriendlyByteBuf b) { return new LandingModePacket(b.readEnum(LandingMode.class)); }
    public static void handle(LandingModePacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> { var player = ctx.get().getSender(); if (player != null) TeleportService.setMode(player.getUUID(), p.mode); });
        ctx.get().setPacketHandled(true);
    }
}
