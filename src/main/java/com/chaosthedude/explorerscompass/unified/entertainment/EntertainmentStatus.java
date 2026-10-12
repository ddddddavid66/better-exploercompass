package com.chaosthedude.explorerscompass.unified.entertainment;

import java.util.UUID;
import com.chaosthedude.explorerscompass.unified.data.LandingMode;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

public record EntertainmentStatus(UUID task, boolean active, int completed, int total,
                                  ResourceLocation biome, String phase, String detail, long revision,
                                  EntertainmentFeature feature, LandingMode mode, int attempts) {
    public static void encode(EntertainmentStatus p, FriendlyByteBuf b) {
        b.writeUUID(p.task); b.writeBoolean(p.active); b.writeVarInt(p.completed); b.writeVarInt(p.total);
        b.writeBoolean(p.biome != null); if (p.biome != null) b.writeResourceLocation(p.biome);
        b.writeUtf(p.phase, 64); b.writeUtf(p.detail, 256); b.writeLong(p.revision);
        b.writeEnum(p.feature);b.writeEnum(p.mode);b.writeVarInt(p.attempts);
    }
    public static EntertainmentStatus decode(FriendlyByteBuf b) {
        return new EntertainmentStatus(b.readUUID(), b.readBoolean(), b.readVarInt(), b.readVarInt(),
                b.readBoolean() ? b.readResourceLocation() : null, b.readUtf(64), b.readUtf(256), b.readLong(), b.readEnum(EntertainmentFeature.class), b.readEnum(LandingMode.class), b.readVarInt());
    }
    public static void handle(EntertainmentStatus p, Supplier<NetworkEvent.Context> context) {
        var connection = context.get().getNetworkManager();
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> EntertainmentClient.accept(connection, p)));
        context.get().setPacketHandled(true);
    }
}
