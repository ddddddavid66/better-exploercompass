package com.chaosthedude.explorerscompass.unified.network;

import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.util.ItemUtils;

public record TeleportRequestPacket(UUID token, UUID requestId, boolean fallback, com.chaosthedude.explorerscompass.unified.data.LandingMode mode) {
    public TeleportRequestPacket(UUID token, UUID requestId, boolean fallback) { this(token, requestId, fallback, com.chaosthedude.explorerscompass.unified.data.LandingMode.DEFAULT); }
    public static void encode(TeleportRequestPacket p,FriendlyByteBuf b) { b.writeBoolean(p.token!=null);if(p.token!=null)b.writeUUID(p.token);b.writeUUID(p.requestId);b.writeBoolean(p.fallback);b.writeEnum(p.mode); }
    public static TeleportRequestPacket decode(FriendlyByteBuf b) { return new TeleportRequestPacket(b.readBoolean()?b.readUUID():null,b.readUUID(),b.readBoolean(),b.readEnum(com.chaosthedude.explorerscompass.unified.data.LandingMode.class)); }
    public static void handle(TeleportRequestPacket p,Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(()->{var player=ctx.get().getSender();if(player!=null) { com.chaosthedude.explorerscompass.unified.search.TeleportService.setMode(player.getUUID(),p.mode); com.chaosthedude.explorerscompass.unified.search.TeleportService.request(player,ItemUtils.getHeldItem(player,ExplorersCompass.explorersCompass),p.token,p.requestId,p.fallback); } });
        ctx.get().setPacketHandled(true);
    }
}
