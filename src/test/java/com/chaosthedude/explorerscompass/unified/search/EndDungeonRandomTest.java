package com.chaosthedude.explorerscompass.unified.search;

import java.util.*;
import java.util.concurrent.Executors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EndDungeonRandomTest {
    @Test void prefixMatchesVanillaRandomAndTransformAcrossSeedsNegativeCoordinatesAndStepIndices() {
        int hits=0;
        for(long seed:new long[]{1,42,789123,5988016379855497799L,-987654321})
            for(int x=-64;x<=64;x++)for(int z=-64;z<=64;z++) {
                int step=Math.floorMod(x,11),index=Math.floorMod(z,397);
                var reference=new WorldgenRandom(new XoroshiroRandomSource(0));
                long decoration=reference.setDecorationSeed(seed,x*16,z*16);reference.setFeatureSeed(decoration,index,step);
                if(reference.nextInt(1_000_000)+1>1500) {assertNull(EndDungeonRandom.predict(seed,x,z,step,index));continue;}
                assertEquals(1,reference.nextInt(1)+1);
                int originX=x*16+reference.nextInt(16),originZ=z*16+reference.nextInt(16);
                var mirror=Mirror.values()[reference.nextInt(2)];var rotation=Rotation.values()[reference.nextInt(3)];
                var offset=StructureTemplate.calculateRelativePosition(new StructurePlaceSettings().setMirror(mirror).setRotation(rotation),new BlockPos(1,2,1));
                assertEquals(new EndDungeonRandom.Hit(originX+offset.getX(),originZ+offset.getZ(),mirror,rotation),EndDungeonRandom.predict(seed,x,z,step,index));hits++;
            }
        assertTrue(hits>75);
    }
    @Test void threadLocalRandomIsIndependentAcrossFourParallelLanes() throws Exception {
        var expected=new ArrayList<EndDungeonRandom.Hit>();
        for(int i=-5000;i<5000;i++)expected.add(EndDungeonRandom.predict(42,i,-i,4,73));
        var executor=Executors.newFixedThreadPool(4);
        try {
            var checks=new ArrayList<java.util.concurrent.Future<?>>();
            for(int lane=0;lane<4;lane++)checks.add(executor.submit(() -> {
                for(int i=4999;i>=-5000;i--)assertEquals(expected.get(i+5000),EndDungeonRandom.predict(42,i,-i,4,73));
            }));
            for(var check:checks)check.get();
        }finally{executor.shutdownNow();}
    }
    @Test void jitterAndRotationRemainInsideTheConservativeGridFrontier() {
        for(int x=-30;x<=30;x++)for(int z=-30;z<=30;z++)for(int px=0;px<16;px++) {
            long minimum=Math.max(0L,(long)Math.floor(Math.sqrt((long)x*x+(long)z*z))*16-32);
            for(int ox:new int[]{-1,16})for(int oz:new int[]{-1,16}) {
                long dx=x*16L+ox-px,dz=z*16L+oz-15;
                assertTrue(dx*dx+dz*dz>=minimum*minimum,"remaining chunk must not hide a nearer target");
            }
        }
    }
}
