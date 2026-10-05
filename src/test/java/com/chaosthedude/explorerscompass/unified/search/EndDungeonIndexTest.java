package com.chaosthedude.explorerscompass.unified.search;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EndDungeonIndexTest {
    @Test void indexRoundTripsAcrossRestartAndDeduplicatesActualAnchors() throws Exception {
        var index=new EndDungeonIndex();BlockPos a=new BlockPos(-33,70,17),b=new BlockPos(29,1,-50);
        index.remember(a,false);index.remember(a,true);index.remember(b,false);
        var loader=EndDungeonIndex.class.getDeclaredMethod("load",CompoundTag.class);loader.setAccessible(true);
        var restored=(EndDungeonIndex)loader.invoke(null,index.save(new CompoundTag()));
        assertEquals(Set.of(a,b),Set.copyOf(restored.positions()));
        restored.forget(a);assertEquals(java.util.List.of(b),restored.positions());
        assertEquals(2,index.positions().size());
    }
}
