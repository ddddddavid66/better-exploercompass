package com.chaosthedude.explorerscompass.unified.network;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.util.ItemUtils;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

public record ClearCachePacket() {
    public static void encode(ClearCachePacket packet, FriendlyByteBuf buffer) {}
    public static ClearCachePacket decode(FriendlyByteBuf buffer) { return new ClearCachePacket(); }
    public static void handle(ClearCachePacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            var player = context.get().getSender();
            if (player != null && !ItemUtils.getHeldItem(player, ExplorersCompass.explorersCompass).isEmpty())
                com.chaosthedude.explorerscompass.unified.search.SearchService.clearCache(player.getUUID());
        }); context.get().setPacketHandled(true);
    }
}
