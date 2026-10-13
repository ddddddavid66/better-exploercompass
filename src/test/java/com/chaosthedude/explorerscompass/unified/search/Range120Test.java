package com.chaosthedude.explorerscompass.unified.search;

import com.chaosthedude.explorerscompass.unified.entertainment.TravelExpansion;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Range120Test {
    @Test void continuousProgressIsNotTotalTimeTimeout(){var watch=new ProgressWatchdog(0);assertFalse(watch.stalled(0,0,0));assertFalse(watch.stalled(1,0,39_000_000_000L));assertFalse(watch.stalled(2,0,78_000_000_000L));assertFalse(watch.stalled(2,1,110_000_000_000L));assertFalse(watch.stalled(2,1,150_000_000_000L));assertTrue(watch.stalled(2,1,150_000_000_001L));}
    @Test void onlyTwoExpansionsAndLongQuota(){assertEquals(80000,TravelExpansion.radius(80000,0));assertEquals(160000,TravelExpansion.radius(80000,1));assertEquals(320000,TravelExpansion.radius(80000,2));assertEquals(512000000L,TravelExpansion.quota(32000000L,2));assertEquals(32000000000L,TravelExpansion.quota(2000000000L,2));assertThrows(IllegalArgumentException.class,()->TravelExpansion.radius(80000,3));}
    @Test void annuliCoverExactlyOnceIncludingNegativeCoordinates(){
        var g=new RectFrontier.Geometry(-73,91,4,-73,91,-73,91);var seen=new HashSet<Long>();
        for(int radius:new int[]{40,80,160}){
            var f=new RectFrontier(g,g.domain(radius),8,(long)radius*radius).withInnerRadius(radius==40?-1:radius/2);
            while(f.available(Long.MAX_VALUE)){long p=f.next();int x=(int)(p>>32),z=(int)p;if(f.inRange(x,z))assertTrue(seen.add(p));}
        }
        var all=new HashSet<Long>();for(int x=-40;x<=40;x++)for(int z=-40;z<=40;z++)if((long)x*x+z*z<=1600)all.add(((long)x<<32)|(z&0xffffffffL));assertEquals(all,seen);
    }
    @Test void longBudgetCannotWrap(){var b=new SamplingBudget(512000000L);assertTrue(b.take(511999999L));assertFalse(b.take(Long.MAX_VALUE));assertTrue(b.take());assertFalse(b.take());assertEquals(512000000L,b.used());}
}
