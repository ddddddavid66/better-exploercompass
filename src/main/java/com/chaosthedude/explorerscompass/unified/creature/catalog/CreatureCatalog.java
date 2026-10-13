package com.chaosthedude.explorerscompass.unified.creature.catalog;

import com.google.gson.*;
import com.chaosthedude.explorerscompass.ExplorersCompass;
import java.io.*;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.ForgeRegistries;

/** 服务端目录，可由数据包覆盖。维度只从明确配置/实际出生规则推导，不猜模组命名空间。 */
public final class CreatureCatalog {
    private static Map<ResourceLocation,CreatureEntry> entries;
    private static Map<ResourceLocation,String> pending=Map.of();
    private CreatureCatalog() {}
    public static Collection<CreatureEntry> all(MinecraftServer server){load(server);return entries.values();}
    public static CreatureEntry get(MinecraftServer server,ResourceLocation id){load(server);return entries.get(id);}
    public static Map<ResourceLocation,String> pending(MinecraftServer server){load(server);return pending;}
    public static void clear(){entries=null;pending=Map.of();}
    private static List<ResourceLocation> ids(JsonObject o,String key) {
        if(!o.has(key))return List.of();List<ResourceLocation> result=new ArrayList<>();
        for(var value:o.getAsJsonArray(key)){var id=ResourceLocation.tryParse(value.getAsString());if(id!=null && !id.getPath().isEmpty())result.add(id);}
        return List.copyOf(result);
    }
    private static String text(JsonObject o,String key){return o.has(key)?o.get(key).getAsString():"";}
    private static void load(MinecraftServer server) {
        if(entries!=null)return;var parsed=new LinkedHashMap<ResourceLocation,JsonObject>();var deferred=new TreeMap<ResourceLocation,String>();
        var resources=server.getResourceManager().listResources("creature_catalog",key->key.getPath().endsWith(".json"));
        resources.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(resource->{
            try(var reader=resource.getValue().openAsReader()) {
                for(var row:JsonParser.parseReader(reader).getAsJsonArray()){
                    JsonObject o=row.getAsJsonObject();var id=ResourceLocation.tryParse(text(o,"entity"));
                    if(id==null)continue;
                    if(!ForgeRegistries.ENTITY_TYPES.containsKey(id)){deferred.put(id,text(o,"name")+"：当前未注册该实体 ID（翻译键不是注册证明）");continue;}
                    if(ForgeRegistries.ENTITY_TYPES.getValue(id)!=EntityType.PLAYER)parsed.put(id,o);
                }
            }catch(Exception error){ExplorersCompass.LOGGER.warn("生物目录无法读取 {}",resource.getKey(),error);}
        });
        // 单次遍历生效出生表，只补维度；文档未写的群系继续保留空集合。
        Map<ResourceLocation,Set<ResourceLocation>> spawnDimensions=new HashMap<>(),biomeDimensions=new HashMap<>();
        for(var level:server.getAllLevels())for(var holder:level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes()) {
            ResourceLocation biome=holder.unwrapKey().map(k->k.location()).orElse(null),dimension=level.dimension().location();
            if(biome!=null)biomeDimensions.computeIfAbsent(biome,k->new LinkedHashSet<>()).add(dimension);
            for(var category:MobCategory.values())for(var spawn:holder.value().getMobSettings().getMobs(category).unwrap()) {
                if(spawn.getWeight().asInt()<=0)continue;var id=ForgeRegistries.ENTITY_TYPES.getKey(spawn.type);
                if(id!=null)spawnDimensions.computeIfAbsent(id,k->new LinkedHashSet<>()).add(dimension);
            }
        }
        var result=new LinkedHashMap<ResourceLocation,CreatureEntry>();
        parsed.forEach((id,o)->{
            // 翻译资源可能残留已移除群系；目录不能把译名当成注册证明。
            var biomes=ids(o,"biomes").stream().filter(b->server.registryAccess().registryOrThrow(Registries.BIOME).containsKey(b)).toList();var dimensions=new LinkedHashSet<>(ids(o,"dimensions"));
            if(dimensions.isEmpty()) {
                dimensions.addAll(spawnDimensions.getOrDefault(id,Set.of()));
                for(var biome:biomes)dimensions.addAll(biomeDimensions.getOrDefault(biome,Set.of()));
            }
            dimensions.removeIf(d->server.levelKeys().stream().noneMatch(k->k.location().equals(d)));
            if(dimensions.isEmpty()){deferred.put(id,text(o,"name")+"：没有明确维度或生效自然出生规则");return;}
            List<String> aliases=new ArrayList<>();if(o.has("aliases"))for(var a:o.getAsJsonArray("aliases"))aliases.add(a.getAsString());
            result.put(id,new CreatureEntry(id,text(o,"name"),text(o,"english"),List.copyOf(aliases),List.copyOf(dimensions),biomes,text(o,"note")));
        });
        entries=Collections.unmodifiableMap(result);
        pending=Collections.unmodifiableMap(deferred);
        ExplorersCompass.LOGGER.info("生物目录：{} 条已安装类型，{} 条维度已核实，其余暂缓",parsed.size(),entries.size());
    }
    public static net.minecraft.nbt.CompoundTag encode(CreatureEntry e) {
        var n=new net.minecraft.nbt.CompoundTag();n.putString("id",e.entity().toString());n.putString("name",e.name());n.putString("english",e.english());n.putString("note",e.note());
        n.put("aliases",strings(e.aliases()));n.put("dimensions",strings(e.dimensions().stream().map(Object::toString).toList()));n.put("biomes",strings(e.biomes().stream().map(Object::toString).toList()));return n;
    }
    private static net.minecraft.nbt.ListTag strings(List<String> values){var n=new net.minecraft.nbt.ListTag();values.forEach(v->n.add(net.minecraft.nbt.StringTag.valueOf(v)));return n;}
    public static CreatureEntry decode(net.minecraft.nbt.CompoundTag n) {
        return new CreatureEntry(new ResourceLocation(n.getString("id")),n.getString("name"),n.getString("english"),strings(n,"aliases"),
                strings(n,"dimensions").stream().map(ResourceLocation::new).toList(),strings(n,"biomes").stream().map(ResourceLocation::new).toList(),n.getString("note"));
    }
    private static List<String> strings(net.minecraft.nbt.CompoundTag n,String key){return n.getList(key,8).stream().map(net.minecraft.nbt.Tag::getAsString).toList();}
}
