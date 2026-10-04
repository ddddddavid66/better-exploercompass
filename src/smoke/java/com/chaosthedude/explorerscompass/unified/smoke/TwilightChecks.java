package com.chaosthedude.explorerscompass.unified.smoke;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import com.chaosthedude.explorerscompass.unified.search.StructureSearch;
import static com.chaosthedude.explorerscompass.unified.smoke.SmokeChecks.check;

/** 使用用户同版本 jar 和实际暮色世界核对，禁止假造结构记录或性能数据。 */
public final class TwilightChecks {
    public static void verify(MinecraftServer server) throws Exception {
        if(!net.minecraftforge.fml.ModList.get().isLoaded("twilightforest"))return;
        var level=server.getLevel(ResourceKey.create(Registries.DIMENSION,new ResourceLocation("twilightforest:twilight_forest")));
        check(level!=null,"actual twilight dimension loaded");
        var registry=level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        var state=level.getChunkSource().getGeneratorState();state.ensureStructuresGenerated();
        var centerMethod=Class.forName("twilightforest.util.LegacyLandmarkPlacements").getMethod("getNearestCenterXZ",int.class,int.class);
        var centerCheck=Class.forName("twilightforest.util.LegacyLandmarkPlacements").getMethod("chunkHasLandmarkCenter",int.class,int.class);
        var centers=new java.util.HashSet<Long>();
        for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++) {
            var center=(BlockPos)centerMethod.invoke(null,x*16,z*16);var chunk=new net.minecraft.world.level.ChunkPos(center);
            check(centers.add(chunk.toLong()) && (boolean)centerCheck.invoke(null,chunk.x,chunk.z),"unique official centers across negative boundaries");
        }
        int placed=0;
        for(var holder:registry.holders().toList())if(holder.unwrapKey().orElseThrow().location().getNamespace().equals("twilightforest"))
            for(var placement:state.getPlacementsForStructure(holder)) {
                check(placement.getClass().getName().endsWith("BiomeForcedLandmarkPlacement"),"actual twilight adapter dispatch");placed++;
            }
        check(placed>=16,"all sixteen twilight placements dispatch");
        for(String name:List.of("labyrinth","hydra_lair","knight_stronghold","dark_tower")) {
            var key=new ResourceLocation("twilightforest",name);
            var holder=registry.getHolder(ResourceKey.create(Registries.STRUCTURE,key)).orElseThrow();
            check(!state.getPlacementsForStructure(holder).isEmpty(),"twilight placement catalog "+name);
            var job=new StructureSearch(level,BlockPos.ZERO,List.of(key),Set.of(),40000,25000000,true);
            long until=System.nanoTime()+120_000_000_000L;
            try {
                while(!job.done() && System.nanoTime()<until){boolean advanced=job.step();if(!SmokeChecks.pump(server) && !advanced)Thread.sleep(1);}
                check(job.done() && job.result()!=null,"actual twilight search finds "+name);
                var found=job.result();var chunk=level.getChunk(found.chunk().x,found.chunk().z,net.minecraft.world.level.chunk.ChunkStatus.STRUCTURE_STARTS);
                var start=level.structureManager().getStartForStructure(net.minecraft.core.SectionPos.bottomOf(chunk),found.structure(),chunk);
                check(start!=null && start.isValid(),"real structure record "+name);
                System.out.println("TWILIGHT PASS "+name+" "+found.position()+" samples="+job.samples());
                if(name.equals("labyrinth"))check(!found.placement().isStructureChunk(state,found.chunk().x,found.chunk().z),"labyrinth reproduces old generic-predicate rejection");
                if(name.equals("labyrinth")) {
                    var section=net.minecraft.core.SectionPos.bottomOf(chunk);
                    level.structureManager().setStartForStructure(section,found.structure(),net.minecraft.world.level.levelgen.structure.StructureStart.INVALID_START,chunk);
                    var retry=new StructureSearch(level,BlockPos.ZERO,List.of(key),Set.of(),40000,25000000,false);
                    try {
                        long deadline=System.nanoTime()+120_000_000_000L;
                        while(!retry.done() && System.nanoTime()<deadline){boolean advanced=retry.step();if(!SmokeChecks.pump(server) && !advanced)Thread.sleep(1);}
                        check(retry.done() && retry.result()!=null && !retry.result().chunk().equals(found.chunk()),"missing old twilight start resumes to another labyrinth");
                    } finally {
                        retry.cancel();level.structureManager().setStartForStructure(section,found.structure(),start,chunk);
                    }
                }
            } finally {job.cancel();}
        }
    }
    private TwilightChecks() {}
}
