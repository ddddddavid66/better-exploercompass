package com.chaosthedude.explorerscompass.unified.search;

import com.chaosthedude.explorerscompass.unified.creature.search.CreatureSearch;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.*;
import net.minecraftforge.registries.ForgeRegistries;

/** 出生表与首组随机抽签只决定加载顺序，永远不能代替真实UUID核对。 */
public final class CreatureChunkPlanner {
    private static final class Ranked {
        final ChunkPos chunk;final int priority;
        Ranked(ChunkPos chunk,int priority){this.chunk=chunk;this.priority=priority;}
    }
    private CreatureChunkPlanner() {}
    public static Supplier<List<ChunkPos>> prepare(ServerLevel level,BlockPos anchor,int count,Set<Long> excluded,
            BlockPos origin,int radius,ResourceLocation target,Set<ResourceLocation> habitats){
        var entity=ForgeRegistries.ENTITY_TYPES.getValue(target);
        if(entity==null || !BiomeSourceSafety.prepare(level).allowed())return ()->CreatureSearch.chunkPlan(anchor,count,excluded,origin,radius);
        var cache=WorldgenCache.get(level);var registry=level.registryAccess().registryOrThrow(Registries.BIOME);
        var allowed=Collections.newSetFromMap(new IdentityHashMap<net.minecraft.world.level.biome.Biome,Boolean>());
        habitats.forEach(id->{var biome=registry.get(id);if(biome!=null)allowed.add(biome);});
        var manager=level.getBiomeManager().withDifferentSource((x,y,z)->net.minecraft.core.Holder.direct(cache.predictBiome(x,y,z)));
        int top=level.getMaxBuildHeight()-1,sea=level.getSeaLevel();long seed=level.getSeed();boolean initial=entity.getCategory()==MobCategory.CREATURE;
        var heightmap=net.minecraft.world.entity.SpawnPlacements.getHeightmapType(entity);
        return ()->{
            var ranked=new ArrayList<Ranked>();var random=new WorldgenRandom(new LegacyRandomSource(0));int hints=0;
            // 扩大廉价的生成规则选区，不扩大实际区块额度。未知或边界误差只降低优先级，不永久排除。
            var patches=entity==net.minecraft.world.entity.EntityType.TURTLE?List.of(anchor,anchor.offset(4096,0,0),anchor.offset(-4096,0,0),anchor.offset(0,0,4096),anchor.offset(0,0,-4096)):List.of(anchor);
            for(var patch:patches)for(var chunk:CreatureSearch.chunkPlan(patch,entity==net.minecraft.world.entity.EntityType.TURTLE?16384:4096,excluded,origin,radius)){
                if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
                var biome=manager.getBiome(chunk.getWorldPosition().atY(top)).value();
                var local=manager.getBiome(new BlockPos(chunk.getMiddleBlockX(),anchor.getY(),chunk.getMiddleBlockZ())).value();
                int priority=allowed.contains(biome) || allowed.contains(local)?1:2;
                if(initial && allowed.contains(biome)){
                    var settings=biome.getMobSettings();random.setDecorationSeed(seed,chunk.getMinBlockX(),chunk.getMinBlockZ());
                    if(random.nextFloat()<settings.getCreatureProbability()){
                        var selected=settings.getMobs(MobCategory.CREATURE).getRandom(random).orElse(null);
                        if(selected!=null && selected.type==entity){
                            random.nextInt(selected.maxCount-selected.minCount+1);
                            int sx=chunk.getMinBlockX()+random.nextInt(16),sz=chunk.getMinBlockZ()+random.nextInt(16);
                            priority=0;
                            // 海龟的官方条件要求低于海平面+4；高处的海滩不能仅凭群系名优先。
                            if(entity==net.minecraft.world.entity.EntityType.TURTLE){
                                int surface=cache.predictor.initialSpawnHeight(sx,sz,heightmap);
                                int ground=cache.predictor.initialSpawnHeight(sx,sz,Heightmap.Types.OCEAN_FLOOR_WG);
                                if(surface>=sea+4 || ground<sea)priority=2;
                            }
                        }
                    }
                }
                if(priority==0)hints++;ranked.add(new Ranked(chunk,priority));
            }
            ranked.sort(Comparator.comparingInt(r->r.priority));
            var chosen=new ArrayList<ChunkPos>();var covered=new HashSet<Long>();
            for(var row:ranked){var chunk=row.chunk;if(covered.contains(chunk.toLong()))continue;
                chosen.add(chunk);for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)covered.add(ChunkPos.asLong(chunk.x+x,chunk.z+z));
                if(chosen.size()>=count)break;
            }
            com.chaosthedude.explorerscompass.ExplorersCompass.LOGGER.info("CREATURE_PLAN {} hints={} count={}",target,hints,chosen.size());
            return List.copyOf(chosen);
        };
    }
}
