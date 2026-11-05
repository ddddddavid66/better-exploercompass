package com.chaosthedude.explorerscompass.unified.creature.search;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid="creaturecompass")
public final class CreatureIndex {
    private static final Map<ServerLevel,Map<ResourceLocation,Map<UUID,Entity>>> levels=new WeakHashMap<>();
    private static final ConcurrentLinkedQueue<Entity> joined=new ConcurrentLinkedQueue<>();
    private CreatureIndex() {}
    public static void record(Entity e){if(e.level() instanceof ServerLevel level && e.isAlive() && !e.isRemoved())levels.computeIfAbsent(level,k->new HashMap<>()).computeIfAbsent(ForgeRegistries.ENTITY_TYPES.getKey(e.getType()),k->new LinkedHashMap<>()).put(e.getUUID(),e);}
    @SubscribeEvent public static void join(EntityJoinLevelEvent e){if(!e.getLevel().isClientSide())joined.add(e.getEntity());}
    @SubscribeEvent public static void leave(EntityLeaveLevelEvent e){if(e.getLevel() instanceof ServerLevel level){var map=levels.get(level);if(map!=null){var type=map.get(ForgeRegistries.ENTITY_TYPES.getKey(e.getEntity().getType()));if(type!=null)type.remove(e.getEntity().getUUID());}
        // 区块卸载保留待复核条目；死亡或销毁才永久移除结果。
        var reason=e.getEntity().getRemovalReason();if(reason!=null && reason.shouldDestroy())CreatureSearch.destroyed(level,e.getEntity().getUUID());
    }}
    public static void drain(long deadline){int count=0;while(System.nanoTime()<deadline && count++<256){Entity e=joined.poll();if(e==null)break;if(e.isRemoved() || !(e.level() instanceof ServerLevel l))continue;levels.computeIfAbsent(l,k->new HashMap<>()).computeIfAbsent(ForgeRegistries.ENTITY_TYPES.getKey(e.getType()),k->new LinkedHashMap<>()).put(e.getUUID(),e);}}
    public static List<Entity> entities(ServerLevel level,ResourceLocation type){var map=levels.get(level);return map==null?List.of():List.copyOf(map.getOrDefault(type,Map.of()).values());}
    public static boolean ready(ServerLevel level,net.minecraft.core.BlockPos p){
        for(int x=(p.getX()-4)>>4;x<=(p.getX()+4)>>4;x++)for(int z=(p.getZ()-4)>>4;z<=(p.getZ()+4)>>4;z++)if(level.getChunkSource().getChunkNow(x,z)==null)return false;
        return level.areEntitiesLoaded(new net.minecraft.world.level.ChunkPos(p).toLong());
    }
    public static MobPoint point(ServerLevel level,Entity e){if(e==null || e.isRemoved() || !e.isAlive() || e.level()!=level || level.getEntity(e.getUUID())!=e || !ready(level,e.blockPosition()))return null;var biome=level.getBiome(e.blockPosition()).unwrapKey().map(k->k.location()).orElse(null);if(biome==null)return null;return new MobPoint(e.getUUID(),ForgeRegistries.ENTITY_TYPES.getKey(e.getType()),level.dimension().location(),biome,e.getX(),e.getY(),e.getZ(),true);}
    public static void clear(){levels.clear();joined.clear();}
}
