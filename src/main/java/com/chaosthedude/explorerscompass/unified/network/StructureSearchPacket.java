package com.chaosthedude.explorerscompass.unified.network;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.util.ItemUtils;
import com.chaosthedude.explorerscompass.unified.search.SearchService;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

public record StructureSearchPacket(ResourceLocation key, boolean group, boolean next, ResourceLocation biome, ResourceLocation dimension) {
    public static void encode(StructureSearchPacket packet, FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(packet.key); buffer.writeBoolean(packet.group); buffer.writeBoolean(packet.next);
        buffer.writeBoolean(packet.biome != null); if (packet.biome != null) buffer.writeResourceLocation(packet.biome);
        buffer.writeResourceLocation(packet.dimension);
    }
    public static StructureSearchPacket decode(FriendlyByteBuf buffer) {
        return new StructureSearchPacket(buffer.readResourceLocation(), buffer.readBoolean(), buffer.readBoolean(),
                buffer.readBoolean() ? buffer.readResourceLocation() : null, buffer.readResourceLocation());
    }
    public static void handle(StructureSearchPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            var player = context.get().getSender();
            if (player != null && player.level().dimension().location().equals(packet.dimension))
                SearchService.requestStructures(player, ItemUtils.getHeldItem(player, ExplorersCompass.explorersCompass),
                        packet.key, packet.group, packet.next, packet.biome);
        });
        context.get().setPacketHandled(true);
    }
}
