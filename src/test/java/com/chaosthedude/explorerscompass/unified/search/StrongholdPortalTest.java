package com.chaosthedude.explorerscompass.unified.search;

import com.david.compass.runtime.game.search.PortalHint;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StrongholdPortalTest {
    @Test void persistsActualDoorRoomWithoutTouchingCompassSearchPosition(){
        var tag=new CompoundTag();
        tag.putInt("FoundStructureX",-5192);
        var frame=new BlockPos(-5300,-36,1770);
        StrongholdPortal.store(tag,new PortalHint(new BoundingBox(-5305,-41,1761,-5281,-28,1779),List.of(frame)));
        var loaded=StrongholdPortal.read(tag);
        assertNotNull(loaded);
        assertEquals(List.of(frame),loaded.frames());
        assertEquals(-5192,tag.getInt("FoundStructureX"));
        StrongholdPortal.store(tag,null);
        assertNull(StrongholdPortal.read(tag));
    }
    @Test void validatesStoredBoundsAndDoesNotTreatOtherTargetsAsStrongholds(){
        var tag=new CompoundTag();
        tag.putIntArray("ECUnifiedPortalRoom",new int[]{0,0,0,1000,1000,1000});
        assertNull(StrongholdPortal.read(tag));
        tag.putIntArray("ECUnifiedPortalRoom",new int[]{0,0,0,5,5,5});
        tag.putLongArray("ECUnifiedPortalFrames",new long[]{new BlockPos(6,3,3).asLong()});
        assertNull(StrongholdPortal.read(tag));
        assertTrue(StrongholdPortal.applies(new ResourceLocation("minecraft:stronghold")));
        assertTrue(StrongholdPortal.applies(new ResourceLocation("betterstrongholds:stronghold")));
        assertFalse(StrongholdPortal.applies(new ResourceLocation("minecraft:buried_treasure")));
    }
}
