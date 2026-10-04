package com.chaosthedude.explorerscompass.unified.data;

import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LandingColumnsTest {
    @Test void strictAndFallbackColumnsCoverTheirWholeSquareWithoutDuplicates() {
        assertEquals(289,LandingColumns.STRICT.size());assertEquals(4225,LandingColumns.RELAXED.size());
        assertEquals(4225,new HashSet<>(LandingColumns.RELAXED).size());
        assertTrue(LandingColumns.RELAXED.containsAll(LandingColumns.STRICT));
    }
    @Test void candidatesStartAtTargetAndExpandByHorizontalDistance() {
        int previous=-1;
        for(var p:LandingColumns.RELAXED){int next=p.x()*p.x()+p.z()*p.z();assertTrue(next>=previous);previous=next;}
        assertEquals(new LandingColumns.Column(0,0),LandingColumns.RELAXED.get(0));
    }
    @Test void verticalSearchCoversBothSidesOfTargetWithoutGaps() {
        var visited=new HashSet<Integer>();
        for(int i=0;i<129;i++)visited.add(LandingColumns.vertical(i));
        assertEquals(129,visited.size());for(int y=-64;y<=64;y++)assertTrue(visited.contains(y));
    }
}
