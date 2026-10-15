package com.chaosthedude.explorerscompass.unified.search;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;

/** 一次完整来源查询匹配整个适生集合；结果只是加载选区，不冒充真实生物。 */
final class HabitatSearch extends SearchJob {
    private final Set<ResourceLocation> targets;
    private final Set<Long> excluded;
    private final long quota;
    private final boolean async;
    private final BiomeSamplingProfile profile;
    HabitatSearch(ServerLevel level,BlockPos origin,Set<ResourceLocation> targets,Set<Long> excluded,
                  int radius,long quota,boolean async,BiomeSamplingProfile profile) {
        super(level,origin,radius);this.targets=Set.copyOf(targets);this.excluded=Set.copyOf(excluded);
        this.quota=quota;this.async=async;this.profile=profile;
        boolean safe=BiomeSourceSafety.prepare(level).allowed(),background=async && safe;
        var source=level.getChunkSource().getGenerator().getBiomeSource();var noise=level.getChunkSource().randomState().sampler();
        var cache=safe?WorldgenCache.get(level):null;
        Map<Biome,ResourceLocation> allowed=new IdentityHashMap<>();var registry=level.registryAccess().registryOrThrow(Registries.BIOME);
        targets.forEach(id->{Biome b=registry.get(id);if(b!=null)allowed.put(b,id);});
        var budget=new SamplingBudget(Math.max(0,quota));budgets.add(budget);quotaDisabled=quota<=0;
        var geometry=new RectFrontier.Geometry(origin.getX(),origin.getZ(),profile.spacing(),origin.getX(),origin.getZ(),origin.getX(),origin.getZ());
        var lanes=new ArrayList<ScanCoordinator.Lane<SearchCandidate>>();
        for(var partition:RectFrontier.partition(geometry.domain(radius),background?SearchConfig.threads():1)) {
            BiomeColumnLane.Sampler sampler=(dx,dz,y)->{
                int x=origin.getX()+dx*profile.spacing(),z=origin.getZ()+dz*profile.spacing();
                if(this.excluded.contains(ChunkPos.asLong(x>>4,z>>4)))return List.of();
                int qx=QuartPos.fromBlock(x),qy=QuartPos.fromBlock(y),qz=QuartPos.fromBlock(z);
                Biome b=cache==null?source.getNoiseBiome(qx,qy,qz,noise).value():cache.predictBiome(qx,qy,qz);
                ResourceLocation id=allowed.get(b);if(id==null)return List.of();
                var p=new BlockPos(x,y,z);return List.of(new SearchCandidate(p,id,null,null,null,distance(origin,p)));
            };
            lanes.add(new BiomeColumnLane(geometry,partition,(long)radius*radius,budget,profile.heights(),profile.depthInterval(),background,sampler).withPrimaryHeights(profile.primaryHeights()));
        }
        scan=new ScanCoordinator<>(lanes,SearchCandidate::distanceSquared,c->true,(long)radius*radius,async)
                .withBatch(1024,4_000_000L).withUnconditionalConfirmation().withFirstConfirmed();
    }
    public SearchJob synchronousFallback(){return new HabitatSearch(level,start,targets,excluded,radius,quota,false,profile).asFallback();}
    public boolean biome(){return true;}
    public boolean asynchronous(){return async;}
}
