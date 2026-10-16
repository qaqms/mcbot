package com.neko.mcbot.path;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NavigationEstimateTest {
    @Test void constructionDoesNotReadTerrainBeforeTheTimedSliceStarts() {
        var reads = new int[1];
        DigSampler terrain = new DigSampler() {
            @Override public boolean passable(int x, int y, int z) { reads[0]++; return true; }
            @Override public double digSeconds(int x, int y, int z) { reads[0]++; return 1; }
            @Override public boolean support(int x, int y, int z) { reads[0]++; return true; }
            @Override public boolean placeable(int x, int y, int z) { reads[0]++; return false; }
            @Override public double placeCost(int x, int y, int z) { reads[0]++; return 1; }
            @Override public boolean inBounds(int x, int y, int z) { return Math.abs(x) < 20; }
            @Override public int maxPlaces() { return 0; }
        };
        var route = new DigAStar(terrain, 0, 1, 0, 8, 1, 0, 8000, 24);
        assertEquals(0, reads[0], "all world-dependent estimates belong to advance's CPU budget");
        route.totalBudget(0);
        assertTrue(route.advance(300, 0));
        assertTrue(route.budgetReached());
    }

    private static final class Slab implements DigSampler {
        final Set<Long> air = new HashSet<>();
        int reads;
        @Override public boolean passable(int x, int y, int z) {
            reads++;
            return y >= 8 || air.contains(DigAStar.pack(x, y, z));
        }
        @Override public double digSeconds(int x, int y, int z) { reads++; return 1; }
        @Override public boolean support(int x, int y, int z) { return y < 8 && !air.contains(DigAStar.pack(x, y, z)); }
        @Override public boolean placeable(int x, int y, int z) { return false; }
        @Override public double placeCost(int x, int y, int z) { return 1; }
        @Override public boolean inBounds(int x, int y, int z) {
            return Math.abs(x) <= 4 && y >= 0 && y <= 10 && Math.abs(z) <= 4;
        }
        @Override public int maxPlaces() { return 0; }
    }

    private static ApproachEstimate estimate(Slab slab, int targetY) {
        var field = new ApproachEstimate(slab, NavigationGoal.position(0, targetY, 0, 0), 0, 8, 0, 2000);
        for (int i = 0; i < 10000 && !field.done(); i++) field.advance();
        assertTrue(field.done());
        return field;
    }

    @Test void buriedTargetsIncludeInterveningWorkAndAnOpenAccessRemovesThatPrice() {
        var slab = new Slab();
        var shallow = estimate(slab, 6);
        var deep = estimate(slab, 3);
        assertTrue(shallow.cost(0, 8, 0, TerrainEdits.EMPTY) >= 2 * 1.3 - 1e-9);
        assertTrue(deep.cost(0, 8, 0, TerrainEdits.EMPTY) > shallow.cost(0, 8, 0, TerrainEdits.EMPTY));
        for (int y = 3; y <= 8; y++) slab.air.add(DigAStar.pack(1, y, 0));
        slab.air.add(DigAStar.pack(0, 3, 0));
        slab.air.add(DigAStar.pack(0, 4, 0));
        assertEquals(0, estimate(slab, 3).cost(0, 8, 0, TerrainEdits.EMPTY), 1e-9);
        assertEquals(8, slab.air.size(), "the estimate must not modify the terrain");
    }

    @Test void priorRemovalsAreDiscountedAndCanNeverProduceNegativeGuidance() {
        var field = estimate(new Slab(), 3);
        var removed = new HashSet<Long>();
        removed.add(DigAStar.pack(0, 3, 0));
        double before = field.cost(0, 8, 0, TerrainEdits.EMPTY);
        assertEquals(before - 1.3, field.cost(0, 8, 0, new TerrainEdits(removed, Set.of())), 1e-9);
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++)
            for (int y = 0; y < 8; y++) removed.add(DigAStar.pack(x, y, z));
        assertEquals(0, field.cost(0, 8, 0, new TerrainEdits(removed, Set.of())), 1e-9);
        assertEquals(before, field.cost(0, 8, 0,
                new TerrainEdits(Set.of(DigAStar.pack(4, 0, 4)), Set.of())), 1e-9,
                "completed work behind the body must not erase the remaining approach price");
    }

    @Test void aDeepRouteStillUsesExecutableStairsWithinTheUnchangedDigAllowance() {
        var slab = new Slab();
        var route = new DigAStar(slab, 0, 8, 0, 0, 3, 0, 8000, 24, DigAStar.H_WEIGHT, 0);
        for (int frame = 0; frame < 100 && !route.done(); frame++) route.advance(300);
        assertNull(route.failure());
        assertTrue(route.estimateExpanded() > 0);
        assertTrue(route.dugCount() > 0 && route.dugCount() <= 24);
        var previous = route.path().getFirst();
        TerrainEdits edits = TerrainEdits.EMPTY;
        for (var step : route.path().subList(1, route.path().size())) {
            var actual = PathTransition.evaluate(slab, edits, previous.x(), previous.y(), previous.z(),
                    step.x(), step.y(), step.z(), 24, 0);
            assertNotNull(actual);
            assertEquals(actual.dig(), step.dig());
            edits = actual.terrain();
            previous = step;
        }
        assertEquals(0, previous.x());
        assertEquals(3, previous.y());
        assertEquals(0, previous.z());
        assertTrue(slab.air.isEmpty());
    }

    @Test void alreadySatisfiedGoalsSkipPreprocessingAndSpendNoEditAllowance() {
        var slab = new Slab();
        var route = new DigAStar(slab, 0, 8, 0, 0, 8, 0, 8000, 24);
        assertTrue(route.advance(1));
        assertEquals(1, route.path().size());
        assertEquals(0, route.pathCost());
        assertEquals(0, route.estimateSeedProbes());
        assertEquals(0, slab.reads);
    }

    @Test void preprocessingAndForwardSearchShareTheOriginalWorkAndStorageCeilings() {
        var slab = new Slab();
        var route = new DigAStar(slab, 0, 8, 0, 0, 3, 0, 32, 24, DigAStar.H_WEIGHT, 0);
        for (int i = 0; i < 200 && !route.done(); i++) route.advance(1);
        assertTrue(route.done());
        assertTrue(route.estimateExpanded() <= 8);
        assertTrue(route.estimateExpanded() + route.expanded() <= 33, "retain the old one-node limit probe");
        assertTrue(route.storedNodes() <= DigAStar.MAX_STORED_NODES);
        assertEquals(1, route.estimateSeedProbes());
    }

    @Test void expiredPreprocessingDoesNoWorldWorkAndNeverInventsAPartialRoute() {
        var slab = new Slab();
        var route = new DigAStar(slab, 0, 8, 0, 0, 3, 0, 8000, 24);
        route.totalBudget(0);
        assertTrue(route.advance(300, 0));
        assertEquals(0, slab.reads);
        assertEquals("TIME", route.budgetKind());
        assertEquals(0, route.expanded());
        assertFalse(route.partialAvailable());
        assertNull(route.path());
    }

    @Test void walkingOnlyDoesNotSpendTimeOnADiggingEstimate() {
        var slab = new Slab();
        var route = new DigAStar(slab, 0, 8, 0, 3, 8, 0, 200, 0);
        while (!route.advance(100)) { }
        assertNull(route.failure());
        assertEquals(0, route.estimateSeedProbes());
        assertEquals(0, route.estimateExpanded());
    }

    @Test void giantOrUnboundedGoalSetsDisableOptionalGuidanceWithinAFixedLimit() {
        NavigationGoal goal = new NavigationGoal() {
            @Override public boolean reached(int x, int y, int z) { return false; }
            @Override public double remaining(int x, int y, int z) { return 1; }
            @Override public Bounds estimateBounds() { return new Bounds(0, 0, 0, 1000000, 1000000, 1000000); }
        };
        var field = new ApproachEstimate(new Slab(), goal, 0, 8, 0, 2000);
        for (int i = 0; i < 9000 && !field.done(); i++) field.advance();
        assertTrue(field.done());
        assertTrue(field.seedProbes() <= 8193);
        assertEquals(0, field.cost(0, 8, 0, TerrainEdits.EMPTY));
        var unbounded = new ApproachEstimate(new Slab(), NavigationGoalStub.INSTANCE, 0, 8, 0, 2000);
        assertTrue(unbounded.done());
    }

    private enum NavigationGoalStub implements NavigationGoal {
        INSTANCE;
        @Override public boolean reached(int x, int y, int z) { return false; }
        @Override public double remaining(int x, int y, int z) { return 1; }
    }

    @Test void anUnusableArrivalDoesNotHideAnotherValidMemberOfTheSameGoal() {
        var world = new DigAStarTest.GridSampler();
        world.placeStock = 0;
        for (int x = 0; x <= 3; x++) world.addSolid(x, 0, 0);
        var goal = new NavigationGoal() {
            @Override public boolean reached(int x, int y, int z) { return y == 1 && z == 0 && x >= 1; }
            @Override public double remaining(int x, int y, int z) { return Math.max(0, 1 - x); }
            @Override public double arrivalCost(DigSampler terrain, int x, int y, int z, TerrainEdits edits) {
                return reached(x, y, z) && x == 2 ? 2 : Double.POSITIVE_INFINITY;
            }
        };
        var route = new DigAStar(world, 0, 1, 0, 1, 1, 0, 200, 0, goal);
        while (!route.advance(100)) { }
        assertNull(route.failure());
        assertEquals(2, route.path().getLast().x());
        assertEquals(4, route.pathCost(), 1e-9, "work price is part of the selected route's total");
    }

    @Test void searchRejectionsAreBoundedEvidenceAndUnknownNeverBecomesAnEditableWall() {
        var slab = new Slab();
        DigSampler unknown = new MemoDigSampler(new DigSampler() {
            @Override public boolean known(int x, int y, int z) { return x == 0 && z == 0; }
            @Override public boolean passable(int x, int y, int z) { return true; }
            @Override public double digSeconds(int x, int y, int z) { return 1; }
            @Override public boolean support(int x, int y, int z) { return true; }
            @Override public boolean placeable(int x, int y, int z) { return false; }
            @Override public double placeCost(int x, int y, int z) { return 1; }
            @Override public boolean inBounds(int x, int y, int z) { return slab.inBounds(x, y, z); }
            @Override public int maxPlaces() { return 0; }
        });
        var route = new DigAStar(unknown, 0, 8, 0, 2, 8, 0, 100, 0);
        while (!route.advance(100)) { }
        assertNotNull(route.failure());
        assertTrue(route.rejections().containsKey("UNKNOWN_TERRAIN"));
        assertTrue(route.rejectionSamples().size() <= 8);
        assertTrue(route.rejections().size() <= 13);
        assertThrows(UnsupportedOperationException.class, () -> route.rejections().clear());
        assertThrows(UnsupportedOperationException.class, () -> route.rejectionSamples().clear());
    }
}
