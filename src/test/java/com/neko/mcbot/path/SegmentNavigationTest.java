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
            assertTrue(route.expanded() <= 4000);
            assertTrue(route.estimateExpanded() <= 1000);
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
        assertTrue(route.expanded() <= 40);
        assertTrue(route.estimateExpanded() <= 10);
    }

    @Test void anExecutableShortTunnelDoesNotWaitForThousandsOfImprovementExpansions() {
        var route = run(new Rock(), 4, 36, 4000, true);
        assertNull(route.failure());
        assertTrue(route.expanded() < 100, "a safe first answer needs only a small improvement window");
        assertEquals(0, route.estimateExpanded(), "expensive relaxed guidance is deferred until feasibility fails");
        assertEquals(0, route.estimateSeedProbes());
        assertEquals(36, route.path().getLast().y());
    }

    @Test void optionalPreparationDoesNotConsumeTheSegmentExpansionAllowance() {
        var position = NavigationGoal.position(4, 36, 0, 0);
        NavigationGoal notYetUsable = new NavigationGoal() {
            @Override public boolean reached(int x, int y, int z) { return false; }
            @Override public double remaining(int x, int y, int z) { return position.remaining(x, y, z); }
            @Override public Bounds estimateBounds() { return position.estimateBounds(); }
        };
        var route = new DigAStar(new Rock(), 0, 40, 0, 4, 36, 0, 100, Integer.MAX_VALUE,
                notYetUsable).segments();
        while (!route.advance(50)) { }
        assertEquals("NODES", route.budgetKind());
        assertEquals(100, route.expanded());
        assertTrue(route.estimateExpanded() > 0 && route.estimateExpanded() <= 25);
        assertEquals(100, route.chargedNodes());
        var allowance = new NavigationAllowance(8);
        allowance.observe(route);
        assertEquals(100, allowance.used());
        assertTrue(route.storedNodes() <= DigAStar.MAX_STORED_NODES);
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

    @Test void tripDiagnosticsChargeReplacementsWithoutAnImplicitDistanceQuota() {
        var near = new NavigationAllowance(4);
        assertEquals(Long.MAX_VALUE, near.limit());
        var far = new NavigationAllowance(320);
        assertEquals(Long.MAX_VALUE, far.limit());
        var route = run(new Rock(), 4, 36, 4000, true);
        near.observe(route);
        long spent = near.used();
        near.observe(route);
        assertEquals(spent, near.used());
        for (int i = 0; i < 22; i++) {
            var failed = run(new Rock(), 4, 36, 4000, false);
            near.observe(failed);
        }
        assertTrue(near.used() > spent);
        assertTrue(near.used() > 40000);
        assertEquals(4000, near.next());
    }

    private static class Corridors implements DigSampler {
        final Set<Long> cells = new HashSet<>();
        Corridors add(int x, int z) { cells.add(DigAStar.pack(x, 0, z)); return this; }
        @Override public boolean inBounds(int x, int y, int z) {
            return y >= -1 && y <= 2 && cells.contains(DigAStar.pack(x, 0, z));
        }
        @Override public boolean passable(int x, int y, int z) { return inBounds(x, y, z) && y >= 0; }
        @Override public boolean support(int x, int y, int z) { return inBounds(x, y, z) && y == -1; }
        @Override public double digSeconds(int x, int y, int z) { return INFEASIBLE; }
        @Override public boolean placeable(int x, int y, int z) { return false; }
        @Override public double placeCost(int x, int y, int z) { return INFEASIBLE; }
        @Override public int maxPlaces() { return 0; }
        @Override public boolean allowsStep(int fx, int fy, int fz, int x, int y, int z) {
            return fy == y && Math.abs(x - fx) + Math.abs(z - fz) == 1;
        }
    }

    @Test void anIrreversibleTailKeepsTheVerifiedReversiblePrefix() {
        var hall = new Corridors() {
            @Override public boolean allowsStep(int fx, int fy, int fz, int x, int y, int z) {
                return super.allowsStep(fx, fy, fz, x, y, z) && !(fx == 4 && x == 3);
            }
        };
        for (int x = 0; x <= 60; x++) hall.add(x, 0);
        var search = new DigAStar(hall, 0, 0, 0, 60, 0, 0, 4, 0,
                NavigationGoal.position(60, 0, 0, 0)).segments();
        while (!search.advance(1)) { }
        assertTrue(search.budgetReached());
        assertTrue(search.partialAvailable());
        assertEquals(3, search.partialPath().getLast().x());
    }

    @Test void anotherCandidateSurvivesWhenTheNearestBranchHasNoReturn() {
        var hall = new Corridors() {
            @Override public boolean allowsStep(int fx, int fy, int fz, int x, int y, int z) {
                return super.allowsStep(fx, fy, fz, x, y, z) && !(fx == 1 && fz == 0 && x == 0);
            }
        };
        for (int x = 0; x <= 6; x++) hall.add(x, 0);
        hall.add(0, 1);
        for (int x = 0; x <= 20; x++) hall.add(x, 2);
        var search = new DigAStar(hall, 0, 0, 0, 10, 0, 0, 18, 0,
                NavigationGoal.position(10, 0, 0, 0)).segments();
        while (!search.advance(1)) { }
        assertTrue(search.budgetReached());
        assertTrue(search.partialAvailable(), "the closest branch must not hide another usable prefix");
        var end = search.partialPath().getLast();
        assertEquals(2, end.z());
        assertTrue(end.x() > 2);
    }

    @Test void furtherBatchesKeepTheUnexpandedFrontierWhenTheRouteMustInitiallyMoveAway() {
        var hall = new Corridors().add(0, 0).add(-1, 0).add(-1, 1).add(-1, 2)
                .add(0, 2).add(1, 2).add(2, 2).add(2, 1).add(2, 0);
        var search = new DigAStar(hall, 0, 0, 0, 2, 0, 0, 2, 0,
                NavigationGoal.position(2, 0, 0, 0)).segments();
        while (!search.advance(1)) { }
        assertTrue(search.budgetReached());
        assertFalse(search.partialAvailable(), "the beginning of this detour is farther from the goal");
        int stored = search.storedNodes(), batches = 1;
        while (search.failure() != null) {
            assertTrue(search.continueSearch(2));
            assertEquals(stored, search.storedNodes(), "continuing must not rebuild the initial queue");
            while (!search.advance(1)) { }
            stored = search.storedNodes();
            assertTrue(++batches < 10);
        }
        assertEquals(2, search.path().getLast().x());
        assertEquals(0, search.path().getLast().z());
        assertTrue(search.path().stream().anyMatch(step -> step.x() == -1));
        assertTrue(search.expanded() < 12, "settled nodes must not be searched again each batch");
    }

    @Test void timeAndStorageBoundariesCannotBeResumedAsNodeBatches() {
        for (boolean time : new boolean[]{true, false}) {
            var hall = new Corridors();
            for (int x = 0; x <= 60; x++) hall.add(x, 0);
            var search = new DigAStar(hall, 0, 0, 0, 60, 0, 0, 4000, 0,
                    NavigationGoal.position(60, 0, 0, 0)).segments();
            if (time) search.totalBudget(0);
            else search.storageBudget(3);
            while (!search.advance(1)) { }
            assertEquals(time ? "TIME" : "STORAGE", search.budgetKind());
            assertFalse(search.continueSearch(4000));
            if (time) assertFalse(search.partialAvailable());
        }
    }

    @Test void nearbyUnreachableHeightDoesNotAuditEveryStoredStateAsAPartialEndpoint() {
        var floor = new Corridors();
        for (int x = -45; x <= 45; x++) for (int z = -45; z <= 45; z++) floor.add(x, z);
        var search = new DigAStar(floor, 0, 0, 0, 0, 1, 0, 4000, 0,
                NavigationGoal.position(0, 1, 0, 0)).segments();
        while (!search.advance(300)) { }
        assertTrue(search.budgetReached());
        assertFalse(search.partialAvailable());
        assertEquals(0, search.partialCandidateChecks(),
                "no closer landing exists; thousands of equal or worse states need no return audit");
        assertTrue(search.storedNodes() > 1000);
    }
}
