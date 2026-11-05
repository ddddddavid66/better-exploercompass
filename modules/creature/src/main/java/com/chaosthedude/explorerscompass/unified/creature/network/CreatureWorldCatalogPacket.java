package com.chaosthedude.explorerscompass.unified.creature.network;

import java.util.*;
import java.util.function.Supplier;
import com.david.compass.runtime.game.data.WorldDirectory;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

public record CreatureWorldCatalogPacket(UUID request, ResourceLocation current, WorldDirectory.Snapshot directory) {
    public static void encode(CreatureWorldCatalogPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.request); buffer.writeResourceLocation(packet.current);
        buffer.writeVarInt(packet.directory.biomes().size());
        packet.directory.biomes().forEach(buffer::writeResourceLocation);
        buffer.writeVarInt(packet.directory.dimensions().size());
        packet.directory.dimensions().forEach((id, values) -> {
            buffer.writeResourceLocation(id); buffer.writeVarInt(values.size()); values.forEach(buffer::writeResourceLocation);
        });
    }
    public static CreatureWorldCatalogPacket decode(FriendlyByteBuf buffer) {
        UUID request=buffer.readUUID(); var current=buffer.readResourceLocation();
        int count=size(buffer,65536); var biomes=new ArrayList<ResourceLocation>(count);
        for (int i=0;i<count;i++) biomes.add(buffer.readResourceLocation());
        int dimensions=size(buffer,4096), edges=0; var mapping=new LinkedHashMap<ResourceLocation,Set<ResourceLocation>>();
        var registered=new HashSet<>(biomes);
        for (int i=0;i<dimensions;i++) {
            var id=buffer.readResourceLocation(); int amount=size(buffer,65536); edges+=amount;
            if (edges>2_000_000) throw new IllegalArgumentException("World directory too large");
            var values=new LinkedHashSet<ResourceLocation>();
            for (int n=0;n<amount;n++) {
                var biome=buffer.readResourceLocation();
                if (!registered.contains(biome)) throw new IllegalArgumentException("Unknown directory biome");
                values.add(biome);
            }
            if (mapping.put(id,values)!=null) throw new IllegalArgumentException("Duplicate directory dimension");
        }
        return new CreatureWorldCatalogPacket(request,current,new WorldDirectory.Snapshot(biomes,mapping));
    }
    private static int size(FriendlyByteBuf buffer,int limit) {
        int value=buffer.readVarInt();
        if (value<0 || value>limit) throw new IllegalArgumentException("Invalid directory size");
        return value;
    }
    public static void handle(CreatureWorldCatalogPacket packet, Supplier<NetworkEvent.Context> context) {
        var ctx=context.get();
        ctx.enqueueWork(() -> com.chaosthedude.explorerscompass.unified.creature.client.CreatureClient.acceptWorldCatalog(packet));
        ctx.setPacketHandled(true);
    }
}
