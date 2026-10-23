package com.neko.mcbot.path;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SegmentNavigationTest {
    private static final class Rock implements DigSampler {
        final Set<Long> air = new HashSet<>();
        boolean sealed;
        Rock() { air.add(DigAStar.pack(0, 40, 0)); air.add(DigAStar.pack(0, 41, 0)); }
        @Override public boolean inBounds(int x, int y, int z) {
            return Math.abs(x) <= 70 && Math.abs(z) <= 8 && y >= -64 && y <= 45;
        }
        @Override public boolean passable(int x, int y, int z) {
            return y >= 42 || air.contains(DigAStar.pack(x, y, z));
        }
        @Override public boolean support(int x, int y, int z) { return !passable(x, y, z); }
        @Override public double digSeconds(int x, int y, int z) { return sealed ? INFEASIBLE : 1; }
        @Override public boolean placeable(int x, int y, int z) { return false; }
        @Override public double placeCost(int x, int y, int z) { return INFEASIBLE; }
        @Override public int maxPlaces() { return 0; }
        @Override public boolean allowsStep(int fx, int fy, int fz, int x, int y, int z) {
            return Math.abs(x - fx) + Math.abs(z - fz) == 1 && Math.abs(y - fy) <= 1;
        }
    }
    private static DigAStar run(Rock world, int tx, int ty, int budget, boolean segments) {
        var route = new DigAStar(world, 0, 40, 0, tx, ty, 0, budget, Integer.MAX_VALUE,
                NavigationGoal.position(tx, ty, 0, 0));
        if (segments) route.segments();
        for (int i = 0; i < 1000 && !route.done(); i++) route.advance(300);
        assertTrue(route.done());
        return route;
    }

    @Test void shortDescendingAndLevelTunnelsProduceFeasibleAnswersInsideTheSame4000Nodes() {
        for (int ty : new int[]{36, 40}) {
            var world = new Rock();
            var route = run(world, 4, ty, 4000, true);
            assertNull(route.failure(), route.failure());
            assertFalse(route.segmentEnd());
            assertEquals(4, route.path().getLast().x());
            assertEquals(ty, route.path().getLast().y());
            assertTrue(route.expanded() + route.estimateExpanded() <= 4000);
            var edits = TerrainEdits.EMPTY;
            var before = route.path().getFirst();
            for (var step : route.path().subList(1, route.path().size())) {
                var checked = PathTransition.check(world, edits, before.x(), before.y(), before.z(),
                        step.x(), step.y(), step.z(), Integer.MAX_VALUE, 0);
                assertNotNull(checked.plan(), checked.reason());
                assertEquals(checked.plan().dig(), step.dig());
                edits = checked.plan().terrain();
                before = step;
            }
        }
    }

    @Test void longTripHandsOverAReal32CellSegmentAndContinuesToTheOriginalGoal() {
        var world = new Rock();
        var first = run(world, 60, 40, 4000, true);
        assertNull(first.failure());
        assertTrue(first.segmentEnd());
        var endpoint = first.path().getLast();
        assertTrue(NavigationGoal.position(60, 40, 0, 0).remaining(endpoint.x(), endpoint.y(), endpoint.z()) <= 28);
        first.path().forEach(step -> world.air.addAll(step.dig()));
        var end = first.path().getLast();
        var next = new DigAStar(world, end.x(), end.y(), end.z(), 60, 40, 0, 4000,
                Integer.MAX_VALUE, NavigationGoal.position(60, 40, 0, 0)).segments();
        while (!next.advance(300)) { }
        assertNull(next.failure());
        assertFalse(next.segmentEnd());
        assertEquals(60, next.path().getLast().x());
    }

    @Test void anAlreadyFoundAnswerSurvivesTheImprovementPassResourceBoundary() {
        var route = run(new Rock(), 4, 36, 40, true);
        assertNull(route.failure(), "a search cap must not discard the greedy answer");
        assertNotNull(route.path());
        assertTrue(route.expanded() + route.estimateExpanded() <= 40);
    }

    @Test void sealedTerrainStillFailsAndFiniteDigAllowancesRemainBinding() {
        var world = new Rock(); world.sealed = true;
        assertTrue(run(world, 4, 36, 4000, true).failure().startsWith("NO_PATH:"));
        var finite = new DigAStar(new Rock(), 0, 40, 0, 4, 36, 0, 4000, 0,
                NavigationGoal.position(4, 36, 0, 0)).segments();
        while (!finite.advance(300)) { }
        assertNotNull(finite.failure());
        assertNull(finite.path());
    }

    @Test void shortPartialProgressRequiresAnActuallyWalkableReturnRoute() {
        for (boolean oneWay : new boolean[]{false, true}) {
            DigSampler hall = new DigSampler() {
                @Override public boolean inBounds(int x, int y, int z) {
                    return x >= 0 && x <= 60 && y >= -1 && y <= 3 && z == 0;
                }
                @Override public boolean passable(int x, int y, int z) { return y >= 0; }
                @Override public boolean support(int x, int y, int z) { return y == -1; }
                @Override public double digSeconds(int x, int y, int z) { return INFEASIBLE; }
                @Override public boolean placeable(int x, int y, int z) { return false; }
                @Override public double placeCost(int x, int y, int z) { return INFEASIBLE; }
                @Override public int maxPlaces() { return 0; }
                @Override public boolean allowsStep(int fx, int fy, int fz, int x, int y, int z) {
                    return !oneWay || x > fx;
                }
            };
            var search = new DigAStar(hall, 0, 0, 0, 60, 0, 0, 2, 0,
                    NavigationGoal.position(60, 0, 0, 0)).segments();
            while (!search.advance(100)) { }
            assertTrue(search.budgetReached());
            assertEquals(!oneWay, search.partialAvailable());
            if (!oneWay) {
                assertEquals(2, search.partialPath().getLast().x());
                assertTrue(search.remainingL1() < 60);
            } else assertNull(search.partialPath(), "one-way progress cannot be handed over as a reversible prefix");
        }
    }

    @Test void tripAllowanceChargesReplacementsAndGrowsWithDistanceWithoutRefillingOnSegments() {
        var near = new NavigationAllowance(4);
        assertEquals(40000, near.limit());
        var far = new NavigationAllowance(320);
        assertEquals(120000, far.limit());
        var route = run(new Rock(), 4, 36, 4000, true);
        near.observe(route);
        long spent = near.used();
        near.observe(route);
        assertEquals(spent, near.used());
        for (int i = 0; i < 12; i++) {
            var failed = run(new Rock(), 4, 36, 4000, false);
            near.observe(failed);
        }
        assertTrue(near.used() > spent);
        assertTrue(near.used() >= near.limit());
        assertEquals(0, near.next());
    }
}
