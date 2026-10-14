package com.chaosthedude.explorerscompass.unified.creature.catalog;

import com.google.gson.*;
import com.chaosthedude.explorerscompass.unified.creature.network.CreatureCatalogPackets;
import io.netty.buffer.Unpooled;
import java.io.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogPackets121Test {
    private static List<String> strings(JsonObject n,String key){return n.getAsJsonArray(key).asList().stream().map(JsonElement::getAsString).toList();}
    private static List<ResourceLocation> ids(JsonObject n,String key){return strings(n,key).stream().map(ResourceLocation::new).toList();}
    @Test void actualCatalogueFitsBoundedDefaultNbtDecoder()throws Exception {
        List<CreatureEntry> entries=new ArrayList<>();
        try(var reader=new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/data/explorerscompass/creature_catalog/user-book.json")),java.nio.charset.StandardCharsets.UTF_8)){
            for(var value:JsonParser.parseReader(reader).getAsJsonArray()){
                var n=value.getAsJsonObject();var rules=new ArrayList<CreatureAppearance>();
                for(var a:n.getAsJsonArray("appearances")){var r=a.getAsJsonObject();rules.add(new CreatureAppearance(r.get("kind").getAsString(),r.get("dimension").isJsonNull()?null:new ResourceLocation(r.get("dimension").getAsString()),ids(r,"biomes"),r.get("description").getAsString(),r.get("verification").getAsString()));}
                // 与服务端一致：没有任何已核实维度的条目不发送。
                if(rules.stream().noneMatch(r->r.supported() && r.dimension()!=null))continue;
                entries.add(new CreatureEntry(new ResourceLocation(n.get("entity").getAsString()),n.get("name").getAsString(),n.get("english").getAsString(),strings(n,"aliases"),ids(n,"dimensions"),ids(n,"biomes"),n.get("note").getAsString(),rules,true));
            }
        }
        assertFalse(entries.isEmpty());var old=new CompoundTag();var all=new ListTag();entries.forEach(e->all.add(CreatureCatalog.encode(e)));old.put("entries",all);
        var baseline=new FriendlyByteBuf(Unpooled.buffer());baseline.writeNbt(old);
        System.out.println("catalogue eligible="+entries.size()+" old_wire="+baseline.readableBytes()+" old_accounted="+old.sizeInBytes());
        try{baseline.readNbt();System.out.println("old_single_packet_decoded=true");}catch(RuntimeException error){System.out.println("old_single_packet_error="+error);}finally{baseline.release();}
        UUID request=UUID.randomUUID();var packets=CreatureCatalogPackets.encode(request,entries,true);int count=0,maxWire=0;
        for(var packet:packets){var buf=new FriendlyByteBuf(Unpooled.buffer());try{
            buf.writeNbt(packet);maxWire=Math.max(maxWire,buf.readableBytes());var decoded=buf.readNbt();assertEquals(request,decoded.getUUID("request"));
            assertTrue(decoded.getList("entries",10).size()<=24);count+=decoded.getList("entries",10).size();
            for(var row:decoded.getList("entries",10))assertNotNull(CreatureCatalog.decode((CompoundTag)row));
        }finally{buf.release();}}
        assertEquals(entries.size(),count);System.out.println("catalogue bounded_parts="+packets.size()+" max_wire="+maxWire+" entries="+count);
    }
}
