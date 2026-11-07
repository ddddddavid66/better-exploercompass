package com.david.compass.runtime.game.search;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LandingFailuresTest {
    @Test void warnsOnlyOnceOnFourthFailureForSameTarget(){
        var ledger=new LandingFailures();var player=UUID.randomUUID();var target=UUID.randomUUID();var world=new Object();
        for(int i=1;i<=8;i++)assertEquals(i==4,ledger.record(player,world,target));
        ledger.clear(player);
        for(int i=1;i<=4;i++)assertEquals(i==4,ledger.record(player,world,target));
    }
    @Test void worldPlayerAndTargetChangesDoNotCarryFailures(){
        var ledger=new LandingFailures();var player=UUID.randomUUID();var target=UUID.randomUUID();var world=new Object();
        for(int i=0;i<3;i++)assertFalse(ledger.record(player,world,target));
        assertFalse(ledger.record(UUID.randomUUID(),world,target));
        var newWorld=new Object();
        assertFalse(ledger.record(player,newWorld,target));
        var newTarget=UUID.randomUUID();
        ledger.target(player,newWorld,newTarget);
        for(int i=1;i<=4;i++)assertEquals(i==4,ledger.record(player,newWorld,newTarget));
    }
}
