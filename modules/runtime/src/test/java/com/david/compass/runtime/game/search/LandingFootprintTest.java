package com.david.compass.runtime.game.search;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LandingFootprintTest {
 @Test void cornerRequiresFourStableChunks(){assertEquals(4,LandingFootprint.chunks(15.2,15.8,15.2,15.8,1).size());}
 @Test void centerNeedsOnlyOne(){assertEquals(1,LandingFootprint.chunks(8.2,8.8,8.2,8.8,1).size());}
 @Test void negativeCornerUsesFloor(){var p=LandingFootprint.chunks(-.8,-.2,-.8,-.2,1);assertEquals(4,p.size());assertTrue(p.stream().anyMatch(c->c.x==-1 && c.z==-1));}
}
