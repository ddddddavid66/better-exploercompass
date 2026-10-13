package com.chaosthedude.explorerscompass.unified.search;

import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Aether121Test {
    @Test void priorityHeightComesFromActualGradientAndTargetDepth() {
        var gradient=new BiomeSamplingProfile.LinearDepth(0,320,1.5,-1.5);
        assertEquals(164,gradient.preferred(-.15,.05,0,320).orElseThrow());
        assertEquals(84,new BiomeSamplingProfile.LinearDepth(0,160,1.5,-1.5).preferred(-.15,.05,0,160).orElseThrow());
        assertTrue(new BiomeSamplingProfile.LinearDepth(0,320,1.5,1.5).preferred(-.15,.05,0,320).isEmpty());
    }
    @Test void fullHeightFallbackRetainsOriginAndDeduplicatesQuart() {
        var profile=BiomeSamplingProfile.aether(0,320,107,32,4,OptionalInt.of(164),"runtime_gradient");
        assertEquals(List.of(164,107),profile.heights().subList(0,2));
        assertEquals(80,profile.heights().size());assertEquals(2,profile.primaryHeights());
        assertEquals(80,profile.heights().stream().map(y->Math.floorDiv(y,4)).distinct().count());
        var same=BiomeSamplingProfile.aether(-64,320,166,32,4,OptionalInt.of(164),"runtime_gradient");
        assertEquals(1,same.primaryHeights());assertEquals(166,same.heights().get(0));assertEquals(96,same.heights().size());
    }

    @Test void noHintStillCoversEveryQuartAndZeroQuotaStaysDisabled() {
        var profile=BiomeSamplingProfile.aether(-63,257,100,32,4,OptionalInt.empty(),"unknown_source");
        assertEquals(1,profile.primaryHeights());assertEquals(81,profile.heights().size());
        assertTrue(profile.heights().stream().allMatch(y->y>=-63 && y<257));
        assertEquals(0,profile.quota(0,80000,-1));
        assertEquals(7_000_000_000L,profile.quota(7_000_000_000L,80000,-1));
        var ordinary=BiomeSamplingProfile.legacy(-64,320,64,32,64,4);
        assertFalse(ordinary.aether());assertEquals(32_000_000L,ordinary.quota(32_000_000L,80000,-1));
    }

    @Test void automaticCoverageUsesLongAndOnlyNewAnnulus() {
        var profile=BiomeSamplingProfile.aether(0,320,107,32,4,OptionalInt.of(164),"runtime_gradient");
        assertEquals(134_983_800L,profile.samples(80000,-1));
        assertEquals(539_949_296L,profile.samples(160000,-1));
        assertEquals(2_159_832_896L,profile.samples(320000,-1));
        assertEquals(2_159_832_897L,profile.quota(512_000_000L,320000,-1));
        assertEquals(profile.samples(320000,-1),profile.samples(80000,-1)+profile.samples(160000,80000)+profile.samples(320000,160000));
    }

    @Test void actualLaneVisitsFrozenProfileExactlyOnceAcrossExpansions() {
        var profile=BiomeSamplingProfile.aether(-8,24,3,4,2,OptionalInt.of(16),"fixture");
        var geometry=new RectFrontier.Geometry(-73,91,4,-73,91,-73,91);
        var all=new HashSet<String>();long sampled=0;
        for(int radius:new int[]{12,24,48}) {
            int inner=radius==12?-1:radius/2;
            var budget=new SamplingBudget(profile.quota(1,radius,inner));
            var lane=new BiomeColumnLane(geometry,geometry.domain(radius),(long)radius*radius,budget,
                    profile.heights(),profile.depthInterval(),false,(x,z,y)-> {
                        assertTrue(all.add(x+":"+z+":"+y));return List.of();
                    }).withPrimaryHeights(profile.primaryHeights()).innerRadius(inner);
            int guard=0;
            while(lane.available((long)radius*radius)){lane.sample();assertTrue(++guard<100000);}
            assertFalse(lane.budgetLimited((long)radius*radius));
            assertEquals(profile.samples(radius,inner),budget.used());sampled+=budget.used();
        }
        var expected=new HashSet<String>();
        for(int x=-12;x<=12;x++)for(int z=-12;z<=12;z++)if((long)x*x+z*z<=144) {
            int count=Math.floorMod(x,2)==0 && Math.floorMod(z,2)==0?profile.heights().size():profile.primaryHeights();
            for(int i=0;i<count;i++)expected.add(x+":"+z+":"+profile.heights().get(i));
        }
        assertEquals(expected,all);assertEquals(sampled,all.size());
    }
}
