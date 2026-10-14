package com.neko.mcbot.path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExactPathTest {
    private static final DigSampler FLOOR = new DigSampler() {
        @Override public boolean passable(int x, int y, int z) { return y >= 0; }
        @Override public double digSeconds(int x, int y, int z) { return INFEASIBLE; }
        @Override public boolean support(int x, int y, int z) { return y == -1; }
        @Override public boolean placeable(int x, int y, int z) { return false; }
        @Override public double placeCost(int x, int y, int z) { return 1; }
        @Override public int maxPlaces() { return 0; }
        @Override public boolean inBounds(int x, int y, int z) {
            return Math.abs(x) <= 4 && Math.abs(z) <= 4 && y >= -1 && y <= 4;
        }
    };

    @Test void exactStandPositionCannotResolveToItsAdjacentStartingCell() {
        var route = new DigAStar(FLOOR, 0, 0, 0, 1, 0, 0, 1000, 0, DigAStar.H_WEIGHT, 0);
        while (!route.advance(100)) { }
        assertNull(route.failure());
        assertEquals(2, route.path().size());
        assertEquals(1, route.path().getLast().x());
        assertEquals(DigAStar.ACT_START, route.path().getFirst().action());
    }

    @Test void nearbyModeStillAllowsAnAlreadyAdjacentBody() {
        var route = new DigAStar(FLOOR, 0, 0, 0, 1, 0, 0, 1000, 0);
        assertTrue(route.advance(100));
        assertEquals(1, route.path().size());
    }
}
