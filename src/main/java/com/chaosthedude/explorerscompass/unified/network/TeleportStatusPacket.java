package com.chaosthedude.explorerscompass.unified.network;

import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

public record TeleportStatusPacket(UUID token, State state, long revision, String reason) {
    public enum State { PREPARING, NEEDS_CONFIRMATION, FALLBACK_PREPARING, FAILED_RETRYABLE, SUCCEEDED, CANCELLED }
    public static void encode(TeleportStatusPacket p,FriendlyByteBuf b) { b.writeUUID(p.token);b.writeEnum(p.state);b.writeLong(p.revision);b.writeUtf(p.reason,128); }
    public static TeleportStatusPacket decode(FriendlyByteBuf b) { return new TeleportStatusPacket(b.readUUID(),b.readEnum(State.class),b.readLong(),b.readUtf(128)); }
    public static void handle(TeleportStatusPacket p,Supplier<NetworkEvent.Context> ctx) {
        var connection=ctx.get().getNetworkManager();
        ctx.get().enqueueWork(()->DistExecutor.unsafeRunWhenOn(Dist.CLIENT,()->()->com.chaosthedude.explorerscompass.unified.client.LandingClientState.accept(connection,p)));
        ctx.get().setPacketHandled(true);
    }
}
