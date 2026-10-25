package com.neko.mcbot.path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PillarSteeringTest {
    @Test void takeoffWaitsForTheBodyToCenterAndBrake() {
        var steering = new PathSteering(0, 1, 0, true);
        var offCenter = steering.tickPillar(new PathSteering.Body(.2, 0, .5, 0, 0, true));
        assertFalse(offCenter.jump());
        assertTrue(offCenter.x() > 0);
        assertFalse(steering.tickPillar(new PathSteering.Body(.5, 0, .5, .1, 0, true)).jump());
        assertTrue(steering.tickPillar(new PathSteering.Body(.5, 0, .5, 0, 0, true)).jump());
    }

    @Test void placementWaitsUntilTheWholeBodyClearsTheBlockAndCannotPlaceFromAnEdge() {
        var steering = new PathSteering(0, 1, 0, true);
        assertFalse(steering.canPlaceUnder(new PathSteering.Body(.5, 1, .5, 0, 0, false), .6));
        assertTrue(steering.canPlaceUnder(new PathSteering.Body(.5, 1.1, .5, 0, 0, false), .6));
        assertFalse(steering.canPlaceUnder(new PathSteering.Body(.75, 1.1, .5, 0, 0, false), .6));
        assertFalse(steering.canPlaceUnder(new PathSteering.Body(.5, 1.1, .5, 0, 0, true), .6));
    }

    @Test void controlledGravityLandsOnEachSupportBeforeTheNextPillarStarts() {
        double y = 0, vy = 0;
        boolean grounded = true;
        int placed = 0, jumps = 0;
        for (int level = 1; level <= 4; level++) {
            var steering = new PathSteering(0, level, 0, true);
            boolean support = false, arrived = false;
            for (int tick = 0; tick < 60 && !arrived; tick++) {
                var body = new PathSteering.Body(.5, y, .5, 0, 0, grounded);
                var command = support ? steering.tick(body) : steering.tickPillar(body);
                assertFalse(command.stuck());
                if (!support && steering.canPlaceUnder(body, .6)) { support = true; placed++; }
                if (command.jump() && grounded) { vy = .42; grounded = false; jumps++; }
                if (!grounded) {
                    y += vy;
                    vy = (vy - .08) * .98;
                    if (support && y <= level && vy < 0) { y = level; vy = 0; grounded = true; }
                }
                arrived = command.arrived();
            }
            assertTrue(arrived);
            assertEquals(level, y);
            assertTrue(grounded);
        }
        assertEquals(4, placed);
        assertEquals(4, jumps);
    }
}
