package com.chaosthedude.explorerscompass.unified.search;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class VerificationSlots128Test {
    @Test void backgroundCannotBypassQueuedForegroundAndCapacityStaysFour(){
        Object[] owners={new Object(),new Object(),new Object(),new Object(),new Object(),new Object()};
        try{
            for(int i=0;i<4;i++)assertTrue(VerificationSlots.acquire(owners[i]));
            assertFalse(VerificationSlots.acquire(owners[4]));VerificationSlots.release(owners[0]);
            assertFalse(VerificationSlots.acquireBackground(owners[5]));
            assertTrue(VerificationSlots.acquire(owners[4]));assertEquals(4,VerificationSlots.count());
        }finally{for(Object owner:owners)VerificationSlots.release(owner);}
        assertEquals(0,VerificationSlots.count());assertEquals(0,VerificationSlots.queued());
    }
    @Test void cancelledQueueHeadDoesNotBlockNextOwner(){
        Object[] owners={new Object(),new Object(),new Object(),new Object(),new Object(),new Object()};
        try{
            for(int i=0;i<4;i++)VerificationSlots.acquire(owners[i]);
            assertFalse(VerificationSlots.acquire(owners[4]));assertFalse(VerificationSlots.acquire(owners[5]));
            VerificationSlots.release(owners[4]);VerificationSlots.release(owners[0]);assertTrue(VerificationSlots.acquire(owners[5]));
        }finally{for(Object owner:owners)VerificationSlots.release(owner);}
    }
}
