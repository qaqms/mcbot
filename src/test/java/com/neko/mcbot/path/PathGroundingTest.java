package com.neko.mcbot.path;

import org.junit.jupiter.api.Test;

import static com.neko.mcbot.path.PathGrounding.Decision.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PathGroundingTest {
    @Test void anAirborneSpawnWaitsForTheRealFirstLanding() {
        var state = new PathGrounding();
        assertEquals(WAIT, state.poll(false, 80));
        assertEquals(WAIT, state.poll(false, 77));
        assertEquals(READY, state.poll(true, 74));
    }

    @Test void aFallDuringWorkWaitsThenInvalidatesTheOldHeightOnce() {
        var state = new PathGrounding();
        assertEquals(READY, state.poll(true, 65));
        assertEquals(WAIT, state.poll(false, 65));
        assertEquals(WAIT, state.poll(false, 64));
        assertEquals(REPLAN, state.poll(true, 62));
        assertEquals(READY, state.poll(true, 62));
    }

    @Test void intentionalCommittedPathStepsDoNotSpendReplans() {
        var state = new PathGrounding();
        assertEquals(READY, state.poll(true, 65));
        state.committed(66);
        assertEquals(READY, state.poll(true, 66));
        state.committed(63);
        assertEquals(WAIT, state.poll(false, 63));
        assertEquals(READY, state.poll(true, 63));
    }

    @Test void temporaryLossOfGroundAtTheSameHeightDoesNotInvalidateAPath() {
        var state = new PathGrounding();
        assertEquals(READY, state.poll(true, 65));
        assertEquals(WAIT, state.poll(false, 65));
        assertEquals(READY, state.poll(true, 65));
    }

    @Test void negativeWorldHeightsAndUpwardDisplacementUseTheActualLanding() {
        var state = new PathGrounding();
        assertEquals(READY, state.poll(true, -60));
        assertEquals(WAIT, state.poll(false, -61));
        assertEquals(REPLAN, state.poll(true, -61));
        assertEquals(REPLAN, state.poll(true, -59));
    }
}
