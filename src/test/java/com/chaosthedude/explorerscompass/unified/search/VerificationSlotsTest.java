package com.chaosthedude.explorerscompass.unified.search;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VerificationSlotsTest {
    @Test void fourSharedSlotsAndFifoSurviveCancellationAndRepeatedRelease() {
        Object[] owners={new Object(),new Object(),new Object(),new Object(),new Object(),new Object()};
        try {
            for(int i=0;i<4;i++)assertTrue(VerificationSlots.acquire(owners[i]));
            assertEquals(4,VerificationSlots.count());
            assertFalse(VerificationSlots.acquire(owners[4]));assertFalse(VerificationSlots.acquire(owners[5]));
            VerificationSlots.release(owners[1]);
            assertFalse(VerificationSlots.acquire(owners[5]));assertTrue(VerificationSlots.acquire(owners[4]));
            VerificationSlots.release(owners[5]);VerificationSlots.release(owners[5]);
            assertEquals(4,VerificationSlots.count());assertEquals(0,VerificationSlots.queued());
        }finally{for(Object owner:owners)VerificationSlots.release(owner);}
        assertEquals(0,VerificationSlots.count());
    }
    @Test void aCancelledQueueHeadDoesNotBlockOtherPlayers() {
        Object[] owners={new Object(),new Object(),new Object(),new Object(),new Object(),new Object()};
        try {
            for(int i=0;i<4;i++)assertTrue(VerificationSlots.acquire(owners[i]));
            assertFalse(VerificationSlots.acquire(owners[4]));assertFalse(VerificationSlots.acquire(owners[5]));
            VerificationSlots.release(owners[4]);VerificationSlots.release(owners[0]);
            assertTrue(VerificationSlots.acquire(owners[5]));
        }finally{for(Object owner:owners)VerificationSlots.release(owner);}
    }
}
