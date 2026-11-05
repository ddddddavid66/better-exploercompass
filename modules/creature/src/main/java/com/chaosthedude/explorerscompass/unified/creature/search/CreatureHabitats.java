package com.chaosthedude.explorerscompass.unified.creature.search;

import com.chaosthedude.explorerscompass.unified.creature.CreatureIdentity;
import com.chaosthedude.explorerscompass.unified.creature.catalog.CreatureCatalog;
import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/** 冻结已生效规则；Feature创建实体不能当作普通自然刷怪空等。 */
public final class CreatureHabitats {
    public record Rules(Set<ResourceLocation> biomes,boolean natural,boolean feature){public Rules{biomes=Set.copyOf(biomes);}}
    private static final Map<ServerLevel,Map<ResourceLocation,Rules>> cache=new IdentityHashMap<>();
    private CreatureHabitats(){}
    public static Rules get(ServerLevel level,ResourceLocation type){return cache.computeIfAbsent(level,k->new HashMap<>()).computeIfAbsent(type,k->prepare(level,k));}
    private static Rules prepare(ServerLevel level,ResourceLocation type){
        var entity=ForgeRegistries.ENTITY_TYPES.getValue(type);var allowed=new HashSet<ResourceLocation>();boolean natural=false;
        if(entity==null)return new Rules(allowed,false,false);
        var possible=level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes();
        for(var biome:possible)for(var spawn:biome.value().getMobSettings().getMobs(entity.getCategory()).unwrap())
            if(spawn.type==entity && spawn.getWeight().asInt()>0 && spawn.maxCount>0){natural=true;biome.unwrapKey().ifPresent(key->allowed.add(key.location()));}
        var entry=CreatureCatalog.get(level.getServer(),type);
        if(entry!=null)for(var appearance:entry.appearances())if(appearance.kind().equals("natural_spawn") && appearance.verification().equals("verified") && level.dimension().location().equals(appearance.dimension()))
            for(var id:appearance.biomes())if(possible.stream().anyMatch(b->b.unwrapKey().map(k->k.location().equals(id)).orElse(false)))allowed.add(id);
        if(type.toString().equals("iceandfire:hydra") && ModList.get().getModContainerById("iceandfire").map(m->m.getModInfo().getVersion().toString().equals("2.1.13-1.20.1")).orElse(false)){
            try{
                Class<?> config=Class.forName("com.github.alexthe666.iceandfire.config.BiomeConfig");Object rule=config.getField("hydraBiomes").get(null);
                var test=config.getMethod("test",org.apache.commons.lang3.tuple.Pair.class,Holder.class);
                allowed.clear();
                for(var biome:possible){
                    boolean active=biome.value().getGenerationSettings().features().stream().flatMap(s->s.stream())
                            .anyMatch(p->p.value().feature().value().feature().getClass().getName().equals("com.github.alexthe666.iceandfire.world.gen.WorldGenHydraCave"));
                    if(active && Boolean.TRUE.equals(test.invoke(null,rule,biome)))biome.unwrapKey().ifPresent(key->allowed.add(key.location()));
                }
                Class<?> settings=Class.forName("com.github.alexthe666.iceandfire.IafConfig");
                CreatureIdentity.LOGGER.info("九头蛇地物选区 biomes={} chance={} spawn_distance={} separation={}",allowed,settings.getField("generateHydraChance").get(null),settings.getField("dangerousWorldGenDistanceLimit").get(null),settings.getField("dangerousWorldGenSeparationLimit").get(null));
                return new Rules(allowed,false,true);
            }catch(ReflectiveOperationException error){CreatureIdentity.LOGGER.warn("九头蛇规则适配不可用，保留真实实体检索",error);return new Rules(Set.of(),false,true);}
        }
        return new Rules(allowed,natural,false);
    }
    public static void clear(){cache.clear();}
}
