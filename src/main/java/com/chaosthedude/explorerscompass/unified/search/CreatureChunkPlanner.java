package com.chaosthedude.explorerscompass.unified.search;

import com.chaosthedude.explorerscompass.unified.data.PatchConnectivity;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;

/** 9×9窗口按短批次增量探测；只决定有限加载顺序，不预测实体存在。 */
public final class CreatureChunkPlanner {
    public static final class Plan {
        private final WorldgenCache cache;
        private final net.minecraft.world.level.biome.BiomeSource source;
        private final net.minecraft.world.level.biome.Climate.Sampler sampler;
        private final Set<Biome> allowed;
        private final List<Integer> heights;
        private final BlockPos anchor,origin;
        private final Set<Long> excluded,loaded;
        private final int count,radius,cx,cz;
        private final boolean background;
        private final boolean feature;
        private final java.util.concurrent.CompletableFuture<Set<Long>> occupied;
        private final boolean[] matches=new boolean[81];
        private int cell,height;
        private List<ChunkPos> result;
        private Set<Long> covered=Set.of();
        private String kind="unknown";
        private Plan(ServerLevel level,BlockPos anchor,int count,Set<Long> excluded,BlockPos origin,int radius,ResourceLocation target,Set<ResourceLocation> habitats){
            this.anchor=anchor;this.origin=origin;this.count=count;this.radius=radius;this.excluded=Set.copyOf(excluded);
            cx=anchor.getX()>>4;cz=anchor.getZ()>>4;background=BiomeSourceSafety.prepare(level).allowed();
            feature=CreatureHabitats.get(level,target).feature();occupied=feature?GeneratedChunkHints.read(level,cx,cz):java.util.concurrent.CompletableFuture.completedFuture(Set.of());
            cache=background?WorldgenCache.get(level):null;source=level.getChunkSource().getGenerator().getBiomeSource();sampler=level.getChunkSource().randomState().sampler();
            allowed=Collections.newSetFromMap(new IdentityHashMap<>());var registry=level.registryAccess().registryOrThrow(Registries.BIOME);
            habitats.forEach(id->{var biome=registry.get(id);if(biome!=null)allowed.add(biome);});
            var hs=new LinkedHashSet<Integer>();hs.add(anchor.getY());
            if(!habitats.isEmpty())hs.addAll(BiomeQueryFactory.prepare(level,anchor,habitats.stream().sorted().findFirst().orElseThrow()).profile().heights());
            heights=List.copyOf(hs);var ready=new HashSet<Long>();
            for(int x=cx-4;x<=cx+4;x++)for(int z=cz-4;z<=cz+4;z++)if(level.getChunkSource().getChunkNow(x,z)!=null)ready.add(ChunkPos.asLong(x,z));
            loaded=Set.copyOf(ready);
        }
        public boolean asynchronous(){return background;}
        public boolean done(){return result!=null;}
        public List<ChunkPos> result(){return result;}
        public Set<Long> covered(){return covered;}
        public String kind(){return kind;}
        public boolean step(long nanos){
            if(!occupied.isDone())return false;
            long deadline=System.nanoTime()+nanos;
            while(cell<81 && System.nanoTime()<deadline){
                if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
                int x=(cx-4+cell%9)*16+8,z=(cz-4+cell/9)*16+8,y=heights.get(height++);
                Biome biome=cache==null?source.getNoiseBiome(QuartPos.fromBlock(x),QuartPos.fromBlock(y),QuartPos.fromBlock(z),sampler).value():cache.predictBiome(QuartPos.fromBlock(x),QuartPos.fromBlock(y),QuartPos.fromBlock(z));
                if(allowed.isEmpty() || allowed.contains(biome)){matches[cell]=true;cell++;height=0;}
                else if(height>=heights.size()){cell++;height=0;}
            }
            if(cell==81 && result==null)finish();return done();
        }
        private void finish(){
            int seed=matches[40]?40:-1;long best=Long.MAX_VALUE;
            if(seed<0)for(int i=0;i<81;i++)if(matches[i]){long dx=i%9-4,dz=i/9-4,d=dx*dx+dz*dz;if(d<best){best=d;seed=i;}}
            if(seed<0){kind="unmatched";result=excluded.contains(ChunkPos.asLong(cx,cz))?List.of():List.of(new ChunkPos(cx,cz));return;}
            var component=PatchConnectivity.connected(matches,9,seed);kind=component.small()?"small":component.large()?"large":"ordinary";
            var all=new ArrayList<ChunkPos>();var patch=new HashSet<Long>();
            for(int i:component.cells()){
                var p=new ChunkPos(cx-4+i%9,cz-4+i/9);patch.add(p.toLong());
                long dx=(long)p.getMiddleBlockX()-origin.getX(),dz=(long)p.getMiddleBlockZ()-origin.getZ();
                if(!excluded.contains(p.toLong()) && dx*dx+dz*dz<=(long)radius*radius)all.add(p);
            }
            var generated=occupied.getNow(Set.of());
            all.sort(Comparator.<ChunkPos>comparingInt(p->feature?(loaded.contains(p.toLong()) || generated.contains(p.toLong())?1:0):(loaded.contains(p.toLong())?0:1)).thenComparingLong(p->{long dx=(long)p.getMiddleBlockX()-anchor.getX(),dz=(long)p.getMiddleBlockZ()-anchor.getZ();return dx*dx+dz*dz;}).thenComparingInt(p->p.x).thenComparingInt(p->p.z));
            int limit=component.small()?1:count;result=List.copyOf(all.subList(0,Math.min(limit,all.size())));covered=Set.copyOf(patch);
        }
    }
    private CreatureChunkPlanner(){}
    public static Plan prepare(ServerLevel level,BlockPos anchor,int count,Set<Long> excluded,BlockPos origin,int radius,ResourceLocation target,Set<ResourceLocation> habitats){
        return new Plan(level,anchor,count,excluded,origin,radius,target,habitats);
    }
}
