package com.neko.mcbot.path;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class SearchStallTest {
    static class Fixture {
        final AtomicLong clock=new AtomicLong();
        final SearchStall stall=new SearchStall(clock::get);
        boolean tick(boolean searching,double x,int digs,int places) {
            clock.addAndGet(50_000_000);
            return stall.observe(searching,x,64,2,digs,places);
        }
    }

    @Test void aStationarySearchReturnsAtThirtySecondsWithoutSleepingInTheTest() {
        var f=new Fixture();
        assertFalse(f.tick(true,1,0,0));
        for (int tick=1;tick<600;tick++) assertFalse(f.tick(true,1,0,0));
        assertEquals(29950,f.stall.idleMillis());
        assertTrue(f.tick(true,1,0,0));
        assertEquals(30000,f.stall.idleMillis());
    }

    @Test void smallJitterCannotKeepAStationarySearchAlive() {
        var f=new Fixture();
        for (int tick=0;tick<600;tick++) assertFalse(f.tick(true,1+(tick%2)*.05,0,0));
        assertTrue(f.tick(true,1,0,0));
    }

    @Test void aLongRouteCanKeepMakingPhysicalProgressEvenAwayFromItsGoal() {
        var f=new Fixture();
        for (int tick=0;tick<12000;tick++) assertFalse(f.tick(true,tick/100,0,0));
        assertTrue(f.stall.idleMillis()<5000);
    }

    @Test void actualEditsInPlaceRenewTheSearchWindow() {
        var f=new Fixture();
        for (int tick=0;tick<12000;tick++) assertFalse(f.tick(true,1,tick/400,tick/500));
        assertTrue(f.stall.idleMillis()<20000);
    }

    @Test void miningAndSteeringDoNotConsumeOrResetIdleSearchTime() {
        var f=new Fixture();
        for (int tick=0;tick<600;tick++) assertFalse(f.tick(true,1,0,0));
        for (int tick=0;tick<10000;tick++) assertFalse(f.tick(false,1,0,0));
        assertEquals(29950,f.stall.idleMillis());
        assertFalse(f.tick(true,1,0,0),"phase changes do not invent elapsed search time");
        assertTrue(f.tick(true,1,0,0),"phase changes also cannot erase the previous wait");
    }

    @Test void aCompletedScaffoldResetsTheWindowAfterALongStationaryAction() {
        var f=new Fixture();
        for (int tick=0;tick<600;tick++) assertFalse(f.tick(true,1,0,0));
        for (int tick=0;tick<10000;tick++) assertFalse(f.tick(false,1,0,0));
        assertFalse(f.tick(false,1,0,1));
        assertEquals(0,f.stall.idleMillis());
        assertFalse(f.tick(true,1,0,1));
        for (int tick=1;tick<600;tick++) assertFalse(f.tick(true,1,0,1));
        assertTrue(f.tick(true,1,0,1));
    }

    @Test void suspensionDoesNotConsumeTimeOrResetPriorSearchWaiting() {
        var f=new Fixture();
        for (int tick=0;tick<600;tick++) assertFalse(f.tick(true,1,0,0));
        f.stall.suspend();
        f.clock.addAndGet(600_000_000_000L);
        assertFalse(f.tick(true,1,0,0));
        assertEquals(29950,f.stall.idleMillis());
        assertTrue(f.tick(true,1,0,0));
    }

    @Test void aLongUnscheduledGapCannotInstantlyExhaustTheSearchWindow() {
        var f=new Fixture();
        assertFalse(f.tick(true,1,0,0));
        f.clock.addAndGet(600_000_000_000L);
        assertFalse(f.tick(true,1,0,0));
        assertEquals(1000,f.stall.idleMillis(),"only a bounded scheduled sample is charged");
    }

    @Test void rapidServerTicksDoNotPretendThatThirtySecondsHavePassed() {
        var f=new Fixture();
        for (int tick=0;tick<10000;tick++) assertFalse(f.stall.observe(true,1,64,2,0,0));
        assertEquals(0,f.stall.idleMillis());
    }
}
