package com.chaosthedude.explorerscompass.unified.network;

import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

public record CatalogInvalidatedPacket() {
    public static void encode(CatalogInvalidatedPacket packet, FriendlyByteBuf buffer) {}
    public static CatalogInvalidatedPacket decode(FriendlyByteBuf buffer) { return new CatalogInvalidatedPacket(); }
    public static void handle(CatalogInvalidatedPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.chaosthedude.explorerscompass.unified.client.ClientSession.invalidate()));
        context.get().setPacketHandled(true);
    }
}
