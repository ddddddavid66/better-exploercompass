package com.chaosthedude.explorerscompass.unified.data;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TeleportOffsetsTest {
    @Test void completeUniqueNearbyVolumeIsOrderedByThreeDimensionalDistance() {
        var offsets = TeleportOffsets.NEARBY;
        assertEquals(17*17*33, offsets.size()); assertEquals(offsets.size(), offsets.stream().distinct().count());
        assertEquals(new TeleportOffsets.Offset(0,0,0), offsets.get(0));
        int previous = -1;
        for (var offset : offsets) {
            assertTrue(offset.squared() >= previous); previous = offset.squared();
            assertTrue(Math.abs(offset.x()) <= 8 && Math.abs(offset.z()) <= 8 && Math.abs(offset.y()) <= 16);
        }
    }
    @Test void floorHeadroomAndCeilingExcludeUnsafeHeightBoundaries() {
        assertFalse(TeleportOffsets.validHeight(-64,-64,320,false,384));
        assertTrue(TeleportOffsets.validHeight(-63,-64,320,false,384));
        assertFalse(TeleportOffsets.validHeight(319,-64,320,false,384));
        assertTrue(TeleportOffsets.validHeight(126,0,256,true,128));
        assertFalse(TeleportOffsets.validHeight(127,0,256,true,128));
        assertTrue(TeleportOffsets.validHeight(200,0,256,false,128));
    }
}
