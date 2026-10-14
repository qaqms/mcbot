package com.neko.mcbot.path;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DescendingClearanceTest {
    private static final class Stair implements DigSampler {
        final Set<Long> blocks = new HashSet<>(List.of(
                DigAStar.pack(0, 2, 0), DigAStar.pack(1, 1, 0),
                DigAStar.pack(1, 2, 0), DigAStar.pack(1, 3, 0),
                DigAStar.pack(1, 4, 0)));
        final Set<Long> denied = new HashSet<>();
        @Override public boolean passable(int x, int y, int z) {
            return inBounds(x, y, z) && !blocks.contains(DigAStar.pack(x, y, z));
        }
        @Override public double digSeconds(int x, int y, int z) {
            return denied.contains(DigAStar.pack(x, y, z)) ? INFEASIBLE : 1;
        }
        @Override public boolean support(int x, int y, int z) {
            return blocks.contains(DigAStar.pack(x, y, z));
        }
        @Override public boolean placeable(int x, int y, int z) { return false; }
        @Override public double placeCost(int x, int y, int z) { return 1; }
        @Override public boolean inBounds(int x, int y, int z) {
            return z == 0 && x >= 0 && x <= 1 && y >= 1 && y <= 5;
        }
        @Override public int maxPlaces() { return 0; }
    }

    private static DigAStar route(Stair world, int budget) {
        var route = new DigAStar(world, 0, 3, 0, 1, 2, 0, 100, budget, DigAStar.H_WEIGHT, 0);
        while (!route.advance(10)) { }
        return route;
    }

    @Test void descendingRouteIncludesTheSweptHeadCellAndClearsTopFirst() {
        var route = route(new Stair(), 3);
        assertNull(route.failure());
        assertEquals(2, route.path().size());
        assertEquals(List.of(DigAStar.pack(1, 4, 0), DigAStar.pack(1, 3, 0),
                DigAStar.pack(1, 2, 0)), route.path().getLast().dig());
        assertEquals(3, route.dugCount());
        assertEquals(0, route.placedCount());
    }

    @Test void anUnbreakableSweptHeadCellRejectsTheRoute() {
        var world = new Stair();
        world.denied.add(DigAStar.pack(1, 4, 0));
        assertTrue(route(world, 3).failure().startsWith("NO_PATH:"));
    }

    @Test void omittedHeadroomCannotFitIntoATwoBlockDigBudget() {
        assertTrue(route(new Stair(), 2).failure().startsWith("NO_PATH:"));
    }

    @Test void alreadyOpenSweptHeadroomAddsNoDig() {
        var world = new Stair();
        world.blocks.remove(DigAStar.pack(1, 4, 0));
        var route = route(world, 2);
        assertNull(route.failure());
        assertEquals(List.of(DigAStar.pack(1, 3, 0), DigAStar.pack(1, 2, 0)),
                route.path().getLast().dig());
    }

    @Test void flatAndAscendingMovesKeepTwoDestinationCells() {
        assertEquals(List.of(DigAStar.pack(1, 4, 0), DigAStar.pack(1, 3, 0)),
                DigAStar.clearanceCells(3, 1, 3, 0));
        assertEquals(List.of(DigAStar.pack(1, 5, 0), DigAStar.pack(1, 4, 0)),
                DigAStar.clearanceCells(3, 1, 4, 0));
    }
}
