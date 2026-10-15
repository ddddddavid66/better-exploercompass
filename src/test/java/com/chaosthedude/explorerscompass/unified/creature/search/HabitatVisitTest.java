package com.chaosthedude.explorerscompass.unified.creature.search;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HabitatVisitTest {
    @Test void observationAvoidsPlayerExclusionAndDespawningDistance(){
        assertFalse(HabitatVisit.observerDistance(24*24));
        assertFalse(HabitatVisit.observerDistance(27.99*27.99));
        assertTrue(HabitatVisit.observerDistance(28*28));
        assertTrue(HabitatVisit.observerDistance(80*80+64*64));
        assertFalse(HabitatVisit.observerDistance(112.01*112.01));
        assertFalse(HabitatVisit.observerDistance(Double.NaN));
    }
}
