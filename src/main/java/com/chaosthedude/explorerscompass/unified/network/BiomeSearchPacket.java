package com.chaosthedude.explorerscompass.unified.network;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.util.ItemUtils;
import com.chaosthedude.explorerscompass.unified.search.SearchService;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

public record BiomeSearchPacket(ResourceLocation biome, ResourceLocation dimension) {
    public BiomeSearchPacket(ResourceLocation biome) { this(biome, null); }
    public static void encode(BiomeSearchPacket packet, FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(packet.biome()); buffer.writeBoolean(packet.dimension != null);
        if (packet.dimension != null) buffer.writeResourceLocation(packet.dimension);
    }
    public static BiomeSearchPacket decode(FriendlyByteBuf buffer) { return new BiomeSearchPacket(buffer.readResourceLocation(), buffer.readBoolean() ? buffer.readResourceLocation() : null); }
    public static void handle(BiomeSearchPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            var player = context.get().getSender();
            if (player != null && (packet.dimension == null || packet.dimension.equals(player.level().dimension().location())))
                SearchService.startBiome(player, ItemUtils.getHeldItem(player, ExplorersCompass.explorersCompass), packet.biome());
        });
        context.get().setPacketHandled(true);
    }
}
