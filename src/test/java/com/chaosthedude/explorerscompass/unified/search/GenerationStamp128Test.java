package com.david.compass.runtime.game.search;

import com.david.compass.runtime.game.search.GenerationStamp;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GenerationStamp128Test {
    @Test void everyFrozenIdentityAndEpochMatters(){
        Object[] objects={new Object(),new Object(),new Object(),new Object(),new Object()};
        var first=stamp(objects,7);assertTrue(first.same(stamp(objects,7)));
        for(int i=0;i<objects.length;i++){var changed=objects.clone();changed[i]=new Object();assertFalse(first.same(stamp(changed,7)));}
        assertFalse(first.same(stamp(objects,8)));assertFalse(first.same(null));
    }
    @Test void customEqualsCannotHideGeneratorReplacement(){
        Object equal=new Object(){public boolean equals(Object o){return true;}};
        Object same=new Object();
        assertFalse(new GenerationStamp(equal,same,same,same,same,0).same(new GenerationStamp(new Object(),same,same,same,same,0)));
    }
    private GenerationStamp stamp(Object[] o,long epoch){return new GenerationStamp(o[0],o[1],o[2],o[3],o[4],epoch);}
}
