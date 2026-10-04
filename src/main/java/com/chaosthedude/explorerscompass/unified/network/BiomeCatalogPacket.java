package com.chaosthedude.explorerscompass.unified.network;

import com.chaosthedude.explorerscompass.unified.client.ClientSession;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

public record BiomeCatalogPacket(UUID requestId, List<ResourceLocation> biomes,
                                 Map<ResourceLocation, Set<ResourceLocation>> structures,
                                 ResourceLocation currentDimension,
                                 Map<ResourceLocation, Set<ResourceLocation>> dimensionBiomes,
                                 Map<ResourceLocation, Set<ResourceLocation>> dimensionStructures,
                                 Map<ResourceLocation, ResourceLocation> groups, Map<ResourceLocation, Integer> xpLevels,
                                 boolean canTeleport, int maxNextSearches, boolean infiniteXp) {
    public BiomeCatalogPacket(UUID requestId, List<ResourceLocation> biomes, Map<ResourceLocation, Set<ResourceLocation>> structures) {
        this(requestId, biomes, structures, new ResourceLocation("minecraft:overworld"), Map.of(), Map.of(), Map.of(), Map.of(), false, 0, false);
    }
    public static void encode(BiomeCatalogPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.requestId);
        buffer.writeVarInt(packet.biomes.size());
        Map<ResourceLocation, Integer> indexes = new HashMap<>();
        for (int index = 0; index < packet.biomes.size(); index++) {
            ResourceLocation biome = packet.biomes.get(index);
            buffer.writeResourceLocation(biome);
            indexes.put(biome, index);
        }
        // 群系 ID 只发送一份，各结构使用索引，减少大型整合包的同步流量。
        buffer.writeVarInt(packet.structures.size());
        for (var entry : packet.structures.entrySet()) {
            buffer.writeResourceLocation(entry.getKey());
            buffer.writeVarInt(entry.getValue().size());
            for (ResourceLocation biome : entry.getValue()) buffer.writeVarInt(indexes.get(biome));
        }
        buffer.writeResourceLocation(packet.currentDimension);
        writeRelations(buffer, packet.dimensionBiomes);
        writeRelations(buffer, packet.dimensionStructures);
        buffer.writeVarInt(packet.groups.size());
        packet.groups.forEach((key, group) -> { buffer.writeResourceLocation(key); buffer.writeResourceLocation(group); });
        buffer.writeVarInt(packet.xpLevels.size());
        packet.xpLevels.forEach((key, cost) -> { buffer.writeResourceLocation(key); buffer.writeVarInt(cost); });
        buffer.writeBoolean(packet.canTeleport); buffer.writeVarInt(packet.maxNextSearches); buffer.writeBoolean(packet.infiniteXp);
    }

    public static BiomeCatalogPacket decode(FriendlyByteBuf buffer) {
        UUID request = buffer.readUUID();
        int biomeCount = boundedSize(buffer, 65536);
        List<ResourceLocation> biomes = new ArrayList<>(biomeCount);
        for (int index = 0; index < biomeCount; index++) biomes.add(buffer.readResourceLocation());
        int structureCount = boundedSize(buffer, 65536);
        Map<ResourceLocation, Set<ResourceLocation>> structures = new HashMap<>();
        int edges = 0;
        for (int index = 0; index < structureCount; index++) {
            ResourceLocation key = buffer.readResourceLocation();
            int count = boundedSize(buffer, biomeCount);
            edges += count;
            if (edges > 2_000_000) throw new IllegalArgumentException("Biome catalog too large");
            Set<ResourceLocation> generating = new HashSet<>();
            for (int biomeIndex = 0; biomeIndex < count; biomeIndex++) {
                int offset = buffer.readVarInt();
                if (offset < 0 || offset >= biomeCount) throw new IllegalArgumentException("Invalid biome index");
                generating.add(biomes.get(offset));
            }
            structures.put(key, Set.copyOf(generating));
        }
        return new BiomeCatalogPacket(request, List.copyOf(biomes), Map.copyOf(structures),
                buffer.readResourceLocation(), readRelations(buffer), readRelations(buffer), readGroups(buffer), readCosts(buffer), buffer.readBoolean(), buffer.readVarInt(), buffer.readBoolean());
    }

    private static void writeRelations(FriendlyByteBuf buffer, Map<ResourceLocation, Set<ResourceLocation>> relations) {
        buffer.writeVarInt(relations.size());
        for (var entry : relations.entrySet()) {
            buffer.writeResourceLocation(entry.getKey()); buffer.writeVarInt(entry.getValue().size());
            for (var value : entry.getValue()) buffer.writeResourceLocation(value);
        }
    }

    private static Map<ResourceLocation, Set<ResourceLocation>> readRelations(FriendlyByteBuf buffer) {
        int dimensions = boundedSize(buffer, 4096), edges = 0;
        Map<ResourceLocation, Set<ResourceLocation>> result = new HashMap<>();
        for (int index = 0; index < dimensions; index++) {
            ResourceLocation id = buffer.readResourceLocation(); int size = boundedSize(buffer, 65536);
            if ((edges += size) > 2_000_000) throw new IllegalArgumentException("Dimension catalog too large");
            Set<ResourceLocation> values = new HashSet<>();
            for (int entry = 0; entry < size; entry++) values.add(buffer.readResourceLocation());
            result.put(id, Set.copyOf(values));
        }
        return Map.copyOf(result);
    }

    private static Map<ResourceLocation, ResourceLocation> readGroups(FriendlyByteBuf buffer) {
        int count = boundedSize(buffer, 65536); var result = new HashMap<ResourceLocation, ResourceLocation>();
        for (int i = 0; i < count; i++) result.put(buffer.readResourceLocation(), buffer.readResourceLocation());
        return Map.copyOf(result);
    }
    private static Map<ResourceLocation, Integer> readCosts(FriendlyByteBuf buffer) {
        int count = boundedSize(buffer, 65536); var result = new HashMap<ResourceLocation, Integer>();
        for (int i = 0; i < count; i++) {
            var key = buffer.readResourceLocation(); int cost = buffer.readVarInt();
            if (cost < 0 || cost > 3) throw new IllegalArgumentException("Invalid XP cost");
            result.put(key, cost);
        }
        return Map.copyOf(result);
    }
    private static int boundedSize(FriendlyByteBuf buffer, int maximum) {
        int count = buffer.readVarInt();
        if (count < 0 || count > maximum) throw new IllegalArgumentException("Invalid catalog size");
        return count;
    }

    public static void handle(BiomeCatalogPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientSession.acceptCatalog(packet)));
        context.get().setPacketHandled(true);
    }
}
