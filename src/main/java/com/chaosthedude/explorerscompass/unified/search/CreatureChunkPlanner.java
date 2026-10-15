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
    private record Ranked(ChunkPos chunk,int priority,int order) {}
    private CreatureChunkPlanner() {}
    public static Supplier<List<ChunkPos>> prepare(ServerLevel level,BlockPos anchor,int count,Set<Long> excluded,
            BlockPos origin,int radius,ResourceLocation target,Set<ResourceLocation> habitats){
        var entity=ForgeRegistries.ENTITY_TYPES.getValue(target);
        if(entity==null || !BiomeSourceSafety.prepare(level).allowed())return ()->CreatureSearch.chunkPlan(anchor,count,excluded,origin,radius);
        var cache=WorldgenCache.get(level);var registry=level.registryAccess().registryOrThrow(Registries.BIOME);
        var allowed=Collections.newSetFromMap(new IdentityHashMap<net.minecraft.world.level.biome.Biome,Boolean>());
        habitats.forEach(id->{var biome=registry.get(id);if(biome!=null)allowed.add(biome);});
        var manager=level.getBiomeManager().withDifferentSource((x,y,z)->net.minecraft.core.Holder.direct(cache.predictBiome(x,y,z)));
        int top=level.getMaxBuildHeight()-1;long seed=level.getSeed();boolean initial=entity.getCategory()==MobCategory.CREATURE;
        return ()->{
            var ranked=new ArrayList<Ranked>();var random=new WorldgenRandom(new LegacyRandomSource(0));int order=0;
            // 扩大廉价的生成规则选区，不扩大实际区块额度。未知或边界误差只降低优先级，不永久排除。
            for(var chunk:CreatureSearch.chunkPlan(anchor,4096,excluded,origin,radius)){
                if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
                var biome=manager.getBiome(chunk.getWorldPosition().atY(top)).value();
                var local=manager.getBiome(new BlockPos(chunk.getMiddleBlockX(),anchor.getY(),chunk.getMiddleBlockZ())).value();
                int priority=allowed.contains(biome) || allowed.contains(local)?1:2;
                if(initial && allowed.contains(biome)){
                    var settings=biome.getMobSettings();random.setDecorationSeed(seed,chunk.getMinBlockX(),chunk.getMinBlockZ());
                    if(random.nextFloat()<settings.getCreatureProbability() && settings.getMobs(MobCategory.CREATURE).getRandom(random).map(s->s.type==entity).orElse(false))priority=0;
                }
                ranked.add(new Ranked(chunk,priority,order++));
            }
            ranked.sort(Comparator.comparingInt(Ranked::priority).thenComparingInt(Ranked::order));
            return ranked.stream().limit(count).map(Ranked::chunk).toList();
        };
    }
}
