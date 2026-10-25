package com.neko.mcbot.path;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NavigationGoalSetTest {
    @Test void oneSearchCanReachTheSecondGoalWhileTheFirstIsSealed() {
        var terrain = new DigAStarTest.GridSampler();
        terrain.placeStock = 0;
        for (int x = -6; x <= 8; x++) for (int z = -6; z <= 6; z++) terrain.addSolid(x, -1, z);
        for (int x = 3; x <= 5; x++) for (int z = -1; z <= 1; z++) {
            if (x == 4 && z == 0) continue;
            for (int y = 0; y <= 5; y++) {
                terrain.addSolid(x, y, z);
                terrain.unbreak.add(DigAStar.pack(x, y, z));
            }
        }
        var goals = NavigationGoal.anyOf(List.of(NavigationGoal.position(4, 0, 0, 0),
                NavigationGoal.position(-3, 0, 0, 0)));
        var route = new DigAStar(terrain, 0, 0, 0, 4, 0, 0, 4000, 0, goals);
        while (!route.advance(100)) { }
        assertNull(route.failure());
        assertEquals(-3, route.path().getLast().x());
        assertEquals(0, route.dugCount());
        assertEquals(0, route.placedCount());
    }

    @Test void arrivalCostMustBelongToAMemberReachedInTheSameEditedState() {
        var edits = TerrainEdits.EMPTY;
        NavigationGoal unreached = new NavigationGoal() {
            @Override public boolean reached(int x, int y, int z) { return false; }
            @Override public double remaining(int x, int y, int z) { return 0; }
            @Override public double arrivalCost(DigSampler s, int x, int y, int z, TerrainEdits state) { return 0; }
        };
        NavigationGoal reached = new NavigationGoal() {
            @Override public boolean reached(int x, int y, int z) { return false; }
            @Override public boolean reached(int x, int y, int z, TerrainEdits state) {
                assertSame(edits, state); return x == 2;
            }
            @Override public double remaining(int x, int y, int z) { return 1; }
            @Override public double arrivalCost(DigSampler s, int x, int y, int z, TerrainEdits state) {
                assertSame(edits, state); return 7;
            }
        };
        var goal = NavigationGoal.anyOf(List.of(unreached, reached));
        assertEquals(7, goal.arrivalCost(null, 2, 0, 0, edits));
        assertEquals(Double.POSITIVE_INFINITY, goal.arrivalCost(null, 0, 0, 0, edits));
        assertNull(goal.estimateBounds(), "an unbounded member cannot be silently clipped");
        assertNull(goal.snapshot(null), "a live member keeps the whole composite on its original thread");
    }

    @Test void boundedMembersRetainTheirUnionAndCanFreezeTogether() {
        var goal = NavigationGoal.anyOf(List.of(NavigationGoal.position(-3, 2, 4, 0),
                NavigationGoal.position(5, 6, -7, 1)));
        assertEquals(new NavigationGoal.Bounds(-3, 2, -8, 6, 7, 4), goal.estimateBounds());
        assertTrue(goal.snapshot(null).reached(5, 6, -7));
        assertThrows(IllegalArgumentException.class, () -> NavigationGoal.anyOf(List.of()));
    }
}
