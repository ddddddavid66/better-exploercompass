package com.chaosthedude.explorerscompass.unified.network;

import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/** 独立于物品同步，取消/换指南针后仍可结束原请求的日志。 */
public record SearchLifecyclePacket(UUID token, boolean terminal, CompoundTag data) {
    public static void encode(SearchLifecyclePacket p, FriendlyByteBuf b) { b.writeUUID(p.token); b.writeBoolean(p.terminal); b.writeNbt(p.data); }
    public static SearchLifecyclePacket decode(FriendlyByteBuf b) {
        UUID id = b.readUUID(); boolean terminal = b.readBoolean(); var data = b.readNbt();
        if (data == null) throw new IllegalArgumentException("Missing lifecycle data");
        return new SearchLifecyclePacket(id, terminal, data);
    }
    public static void handle(SearchLifecyclePacket p, Supplier<NetworkEvent.Context> ctx) {
        var connection = ctx.get().getNetworkManager();
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.chaosthedude.explorerscompass.unified.client.SearchLogClient.accept(connection, p)));
        ctx.get().setPacketHandled(true);
    }
}
