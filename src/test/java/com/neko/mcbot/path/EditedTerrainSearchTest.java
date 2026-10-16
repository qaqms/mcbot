package com.neko.mcbot.path;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EditedTerrainSearchTest {
    private static final class Hill implements DigSampler {
        final Set<Long> blocks = new HashSet<>();
        final int width;
        Hill(int width) {
            this.width = width;
            for (int x = 0; x < width; x++) for (int y = 0; y < 4; y++)
                blocks.add(DigAStar.pack(x, y, 0));
        }
        @Override public boolean passable(int x, int y, int z) {
            return inBounds(x, y, z) && !blocks.contains(DigAStar.pack(x, y, z));
        }
        @Override public double digSeconds(int x, int y, int z) {
            return inBounds(x, y, z) && y > 0 ? 1 : INFEASIBLE;
        }
        @Override public boolean support(int x, int y, int z) {
            return blocks.contains(DigAStar.pack(x, y, z));
        }
        @Override public boolean placeable(int x, int y, int z) { return false; }
        @Override public double placeCost(int x, int y, int z) { return INFEASIBLE; }
        @Override public boolean inBounds(int x, int y, int z) {
            return x >= 0 && x < width && y >= 0 && y <= 5 && z == 0;
        }
        @Override public int maxPlaces() { return 0; }
    }

    private static DigAStar search(Hill hill, int targetX, int targetY, int digs) {
        var route = new DigAStar(hill, 0, 4, 0, targetX, targetY, 0, 8000, digs,
                DigAStar.H_WEIGHT, 0);
        for (int frame = 0; frame < 100 && !route.done(); frame++) route.advance(300);
        assertTrue(route.done(), "search must retain its finite budget");
        return route;
    }

    @Test void aSealedShaftCannotBeReachedByExcavatingTheDepartureSupport() {
        var route = search(new Hill(1), 0, 1, 24);
        assertNotNull(route.failure(), "a path that digs under its own feet must be rejected during search");
        assertTrue(route.failure().startsWith("NO_PATH:"));
    }

    @Test void descendingWorkFindsAStairInsteadOfReturningAnUnexecutableShaft() {
        var hill = new Hill(3);
        var original = Set.copyOf(hill.blocks);
        var route = search(hill, 1, 1, 24);
        assertNull(route.failure());
        assertEquals(original, hill.blocks, "planning must not modify terrain");
        var live = new HashSet<>(hill.blocks);
        var previous = route.path().getFirst();
        for (var step : route.path().subList(1, route.path().size())) {
            long departureFloor = DigAStar.pack(previous.x(), previous.y() - 1, previous.z());
            for (long cell : step.dig()) {
                assertNotEquals(departureFloor, cell, "the body still stands here while clearing the next step");
                assertTrue(live.remove(cell), "already removed cells must not consume another dig");
            }
            assertTrue(live.contains(DigAStar.pack(step.x(), step.y() - 1, step.z())),
                    "an earlier removal must not become phantom support");
            for (long cell : DigAStar.clearanceCells(previous.y(), step.x(), step.y(), step.z()))
                assertFalse(live.contains(cell), "all swept cells must be open after the planned edits");
            previous = step;
        }
        assertEquals(1, previous.x());
        assertEquals(1, previous.y());
        assertTrue(route.dugCount() <= 24);
    }

    @Test void insufficientDigBudgetCannotBeReplenishedByRevisitingAColumn() {
        var route = search(new Hill(3), 1, 1, 2);
        assertNotNull(route.failure());
        assertNull(route.path());
    }

    @Test void returningToClearedColumnsDoesNotChargeAgainOrMutateSiblingHistories() {
        var world = new DigAStarTest.GridSampler();
        world.placeStock = 0;
        for (int x = 0; x < 3; x++) world.addSolid(x, 0, 0);
        world.addSolid(1, 1, 0);
        world.addSolid(1, 2, 0);
        var first = PathTransition.evaluate(world, TerrainEdits.EMPTY, 0, 1, 0, 1, 1, 0, 2, 0);
        assertNotNull(first);
        assertEquals(2, first.dig().size());
        var across = PathTransition.evaluate(world, first.terrain(), 1, 1, 0, 2, 1, 0, 0, 0);
        assertNotNull(across);
        var back = PathTransition.evaluate(world, across.terrain(), 2, 1, 0, 1, 1, 0, 0, 0);
        assertNotNull(back);
        assertTrue(back.dig().isEmpty());
        assertTrue(TerrainEdits.EMPTY.removed().isEmpty());
        assertEquals(2, first.terrain().removed().size());
        assertFalse(world.passable(1, 1, 0), "only the route view changes");
    }

    @Test void previousPlacementSupportsLaterStepsWithoutSpendingAnotherBlock() {
        var world = new DigAStarTest.GridSampler();
        world.addSolid(0, 0, 0);
        world.addSolid(2, 0, 0);
        var bridge = PathTransition.evaluate(world, TerrainEdits.EMPTY, 0, 1, 0, 1, 1, 0, 0, 1);
        assertNotNull(bridge);
        assertEquals(List.of(DigAStar.pack(1, 0, 0)), bridge.place());
        var back = PathTransition.evaluate(world, bridge.terrain(), 2, 1, 0, 1, 1, 0, 0, 0);
        assertNotNull(back);
        assertTrue(back.place().isEmpty());
        assertFalse(world.support(1, 0, 0));
    }

    @Test void plannedSupportsAreNotRecycledByLaterMining() {
        var world = new DigAStarTest.GridSampler();
        world.addSolid(0, 0, 0);
        world.addSolid(1, -1, 0);
        var edits = TerrainEdits.EMPTY.after(List.of(), List.of(DigAStar.pack(1, 0, 0)));
        assertNull(PathTransition.evaluate(world, edits, 0, 1, 0, 1, 0, 0, 24, 0));
        assertEquals(Set.of(DigAStar.pack(1, 0, 0)), edits.placed());
    }

    @Test void terrainSnapshotsCopyTheirInputsAndPreserveTheirParents() {
        long cell = DigAStar.pack(1, 1, 0);
        var input = new HashSet<>(Set.of(cell));
        var parent = new TerrainEdits(input, Set.of());
        input.clear();
        var child = parent.after(List.of(), List.of(cell));
        assertEquals(Set.of(cell), parent.removed());
        assertTrue(parent.placed().isEmpty());
        assertTrue(child.removed().isEmpty());
        assertEquals(Set.of(cell), child.placed());
        assertThrows(UnsupportedOperationException.class, () -> parent.removed().clear());
        assertThrows(IllegalArgumentException.class, () -> new TerrainEdits(Set.of(cell), Set.of(cell)));
    }

    @Test void previouslyRemovedFloorCannotSupportALaterLanding() {
        var world = new Hill(3);
        long floor = DigAStar.pack(1, 2, 0);
        var edits = TerrainEdits.EMPTY.after(List.of(floor, DigAStar.pack(1, 3, 0)), List.of());
        assertTrue(world.support(1, 2, 0));
        assertNull(PathTransition.evaluate(world, edits, 0, 4, 0, 1, 3, 0, 10, 0));
    }

    @Test void geometryAndBudgetsKeepAlternativeHistoriesAtTheSameJunction() {
        var world = new DigAStarTest.GridSampler();
        world.placeStock = 0;
        for (int x = 0; x <= 4; x++) for (int z = 0; z <= 1; z++) world.addSolid(x, 0, z);
        for (int y = 1; y <= 2; y++) {
            world.addSolid(1, y, 0);
            world.addSolid(3, y, 0);
            world.addSolid(3, y, 1);
            world.unbreak.add(DigAStar.pack(3, y, 1));
        }
        DigSampler corridor = new DigSampler() {
            @Override public boolean passable(int x, int y, int z) { return world.passable(x, y, z); }
            @Override public double digSeconds(int x, int y, int z) {
                return x == 1 ? 0 : world.digSeconds(x, y, z);
            }
            @Override public boolean support(int x, int y, int z) { return world.support(x, y, z); }
            @Override public boolean placeable(int x, int y, int z) { return false; }
            @Override public double placeCost(int x, int y, int z) { return INFEASIBLE; }
            @Override public boolean inBounds(int x, int y, int z) {
                return x >= 0 && x <= 4 && y >= 0 && y <= 2 && z >= 0 && z <= 1;
            }
            @Override public boolean allowsStep(int fx, int fy, int fz, int x, int y, int z) {
                return y == 1;
            }
            @Override public int maxPlaces() { return 0; }
        };
        var route = new DigAStar(corridor, 0, 1, 0, 4, 1, 0, 8000, 2, DigAStar.H_WEIGHT, 0);
        while (!route.advance(300)) { }
        assertNull(route.failure(), "a cheaper arrival with spent dig budget must not erase the usable arrival");
        assertEquals(2, route.dugCount());
        assertTrue(route.path().stream().anyMatch(step -> step.x() == 1 && step.z() == 1));
        assertTrue(route.path().stream().flatMap(step -> step.dig().stream())
                .allMatch(cell -> DigAStar.unpackX(cell) == 3));
    }

    @Test void searchMemoryRemainsBoundedAndExhaustionDoesNotMeanNoRoute() {
        var world = new DigAStarTest.GridSampler();
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) world.addSolid(x, 0, z);
        var neverReached = new NavigationGoal() {
            @Override public boolean reached(int x, int y, int z) { return false; }
            @Override public double remaining(int x, int y, int z) { return 1; }
        };
        var route = new DigAStar(world, 0, 1, 0, 2, 1, 0, 8000, 24, neverReached);
        for (int frame = 0; frame < 100 && !route.done(); frame++) route.advance(300);
        assertTrue(route.done());
        assertTrue(route.budgetReached());
        assertTrue(route.failure().startsWith("BUDGET_EXCEEDED:"));
        assertEquals(DigAStar.MAX_STORED_NODES, route.storedNodes(), "the fixture exercises the storage ceiling");
    }

    @Test void yieldingTheCurrentSliceCannotSkipTheAccumulatedTimeCeiling() {
        var world = new DigAStarTest.GridSampler();
        for (int x = 0; x <= 40; x++) world.addSolid(x, 0, 0);
        var route = new DigAStar(world, 0, 1, 0, 40, 1, 0, 8000, 0);
        route.totalBudget(0);
        assertTrue(route.advance(300, 0), "both ceilings expire; the total ceiling must stop rather than yield");
        assertTrue(route.budgetReached());
        assertTrue(route.failure().startsWith("BUDGET_EXCEEDED:"));
        assertEquals(16, route.expanded(), "retain the existing clock sampling cadence");
    }
}
