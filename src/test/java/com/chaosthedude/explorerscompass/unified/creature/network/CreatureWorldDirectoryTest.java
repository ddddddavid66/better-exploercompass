package com.chaosthedude.explorerscompass.unified.creature.network;

import java.util.*;
import java.nio.file.*;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import com.david.compass.runtime.game.data.WorldDirectory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CreatureWorldDirectoryTest {
    private ResourceLocation id(String name) { return new ResourceLocation(name); }
    @Test void directorySnapshotsFreezeBothLevels() {
        var values=new HashSet<ResourceLocation>();values.add(id("minecraft:plains"));
        var map=new HashMap<ResourceLocation,Set<ResourceLocation>>();map.put(id("minecraft:overworld"),values);
        var biomes=new ArrayList<>(values);var snapshot=new WorldDirectory.Snapshot(biomes,map);
        values.clear();map.clear();biomes.clear();
        assertEquals(1,snapshot.biomes().size());assertEquals(1,snapshot.dimensions().get(id("minecraft:overworld")).size());
        assertThrows(UnsupportedOperationException.class,()->snapshot.dimensions().clear());
        assertThrows(UnsupportedOperationException.class,()->snapshot.dimensions().get(id("minecraft:overworld")).clear());
    }
    @Test void roundTripRetainsManyToManyRelations() {
        var biome=id("example:shared");var first=id("example:first");var second=id("example:second");
        var packet=new CreatureWorldCatalogPacket(UUID.randomUUID(),first,new WorldDirectory.Snapshot(List.of(biome),Map.of(first,Set.of(biome),second,Set.of(biome))));
        var buffer=new FriendlyByteBuf(Unpooled.buffer());
        try {CreatureWorldCatalogPacket.encode(packet,buffer);assertEquals(packet,CreatureWorldCatalogPacket.decode(buffer));}
        finally {buffer.release();}
    }
    @Test void rejectsNegativeSizeBeforeAllocation() {
        var buffer=header();try {buffer.writeVarInt(-1);assertThrows(IllegalArgumentException.class,()->CreatureWorldCatalogPacket.decode(buffer));}
        finally {buffer.release();}
    }
    @Test void rejectsUnknownBiomeRelationship() {
        var buffer=header();try {
            buffer.writeVarInt(0);buffer.writeVarInt(1);buffer.writeResourceLocation(id("example:dimension"));
            buffer.writeVarInt(1);buffer.writeResourceLocation(id("example:unknown"));
            assertThrows(IllegalArgumentException.class,()->CreatureWorldCatalogPacket.decode(buffer));
        } finally {buffer.release();}
    }
    @Test void rejectsDuplicateDimensions() {
        var buffer=header();try {
            buffer.writeVarInt(0);buffer.writeVarInt(2);
            for(int i=0;i<2;i++){buffer.writeResourceLocation(id("example:dimension"));buffer.writeVarInt(0);}
            assertThrows(IllegalArgumentException.class,()->CreatureWorldCatalogPacket.decode(buffer));
        } finally {buffer.release();}
    }
    @Test void creatureClientAndNetworkNoLongerLoadExplorerDirectory() throws Exception {
        var root=Path.of("modules/creature/src/main/java/com/chaosthedude/explorerscompass/unified");
        for(var path:List.of(root.resolve("creature/client/CreatureClient.java"),root.resolve("creature/client/CreatureScreen.java"),root.resolve("creature/network/CreatureNetwork.java"))) {
            var text=Files.readString(path);assertFalse(text.contains("ClientSession"));assertFalse(text.contains("UnifiedNetwork"));
        }
        var directory=Files.readString(Path.of("modules/runtime/src/main/java/com/david/compass/runtime/game/data/WorldDirectory.java"));
        assertFalse(directory.contains("Registries.STRUCTURE"));assertFalse(directory.contains("ensureStructuresGenerated"));
        assertFalse(directory.contains("ConfigHandler"));
    }
    private FriendlyByteBuf header() {
        var buffer=new FriendlyByteBuf(Unpooled.buffer());buffer.writeUUID(UUID.randomUUID());buffer.writeResourceLocation(id("minecraft:overworld"));return buffer;
    }
}
