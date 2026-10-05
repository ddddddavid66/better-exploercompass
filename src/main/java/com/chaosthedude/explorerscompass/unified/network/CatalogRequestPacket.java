package com.chaosthedude.explorerscompass.unified.network;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.util.ItemUtils;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

public record CatalogRequestPacket(UUID requestId) {
    public static void encode(CatalogRequestPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.requestId);
    }

    public static CatalogRequestPacket decode(FriendlyByteBuf buffer) {
        return new CatalogRequestPacket(buffer.readUUID());
    }

    public static void handle(CatalogRequestPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (player == null || ItemUtils.getHeldItem(player, ExplorersCompass.explorersCompass).isEmpty()) return;
            // 仅枚举当前服务器的注册表与标签，不生成或加载任何新区块。
            UnifiedNetwork.send(player, CatalogService.snapshot(player, packet.requestId));
        });
        context.get().setPacketHandled(true);
    }
}
