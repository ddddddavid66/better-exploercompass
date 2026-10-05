package com.chaosthedude.explorerscompass.unified.network;

import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/** 物品原有 NBT 驱动渲染；序号避免旧搜索的状态覆盖新物品。 */
public record SearchStatusPacket(UUID token, CompoundTag tag) {
    public static void encode(SearchStatusPacket packet, FriendlyByteBuf buffer) { buffer.writeUUID(packet.token()); buffer.writeNbt(packet.tag()); }
    public static SearchStatusPacket decode(FriendlyByteBuf buffer) {
        UUID token = buffer.readUUID(); CompoundTag tag = buffer.readNbt();
        if (tag == null) throw new IllegalArgumentException("Missing search state");
        return new SearchStatusPacket(token, tag);
    }
    public static void handle(SearchStatusPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.chaosthedude.explorerscompass.unified.client.SearchClientState.accept(packet)));
        context.get().setPacketHandled(true);
    }
}
