package com.neko.mcbot.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExplorationRouteTest {
    @Test void checkpointKeepsOriginalScopeSpentAttemptsAndUndergroundReturnTrail() {
        var route = new ExplorationRoute(3, 32);
        route.beginUnderground(point(0, 70, 0));
        route.attempt(point(1, 69, 0)); route.observe(point(1, 69, 0));
        route.reachedUnderground(point(1, 69, 0), false);
        var movement = new JsonObject(); movement.addProperty("removed_blocks", 3); route.recordDigs(movement);
        var restored = ExplorationRoute.restore(route.snapshot());
        assertEquals(1, restored.attempts()); assertEquals(2, restored.remainingSteps());
        assertEquals(125, restored.remainingDigs()); assertEquals(route.bounds(), restored.bounds());
        assertEquals(route.trail(), restored.trail()); assertEquals(route.returnPoint(), restored.returnPoint());
        assertTrue(restored.tried(point(1, 69, 0)));
        assertThrows(IllegalArgumentException.class, () -> restored.observe(point(33, 70, 0)));
        restored.attemptReturn(restored.returnPoint());
        assertThrows(IllegalStateException.class, restored::snapshot);
    }
    @Test void changingTerrainDuringPaginationCannotCertifyOneCompleteCoverageRegion() {
        var route = new ExplorationRoute(8, 64);
        route.observe(point(0, 70, 0));
        var args = BoundedSearchTest.json("{\"targets\":[\"dirt\"],\"r\":16,\"offset\":0}");
        for (int offset = 0; offset <= 16384; offset += 4096) {
            args.addProperty("offset", offset);
            if (offset > 0) args.add("scan_center", BoundedSearchTest.json("{\"x\":0,\"y\":70,\"z\":0}"));
            var page = BoundedSearchTest.page(offset, false);
            page.add("spatial_facts", BoundedSearchTest.json("{\"dimension\":\"minecraft:overworld\","
                    + "\"chunks\":{\"0,0\":\"chunk:" + (offset < 8192 ? 1 : 2) + "\"}}"));
            route.searchPage(args, new com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome(true, "page", page), 10);
        }
        assertEquals(0, route.coverageRegions());
        assertEquals(0, route.attempts());
    }

    @Test void unloadInvalidatesCoverageWithoutResettingExplorationBudgetOrVisitedPositions() {
        var route = new ExplorationRoute(8, 64);
        route.observe(point(0, 70, 0));
        route.attempt(point(8, 70, 0));
        route.observe(point(8, 70, 0));
        var args = BoundedSearchTest.json("{\"targets\":[\"dirt\"],\"r\":16,\"offset\":0}");
        for (int offset = 0; offset <= 16384; offset += 4096) {
            args.addProperty("offset", offset);
            if (offset > 0) args.add("scan_center", BoundedSearchTest.json("{\"x\":0,\"y\":70,\"z\":0}"));
            var page = BoundedSearchTest.page(offset, false);
            page.add("spatial_facts", BoundedSearchTest.json("{\"dimension\":\"minecraft:overworld\","
                    + "\"chunks\":{\"0,0\":\"chunk:1\"}}"));
            route.searchPage(args, new com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome(true, "page", page), 10);
        }
        assertEquals(1, route.coverageRegions());
        var state = point(8, 70, 0);
        state.add("spatial_facts", BoundedSearchTest.json("{\"dimension\":\"minecraft:overworld\","
                + "\"chunks\":{\"0,0\":null}}"));
        route.terrainObserved(state);
        assertEquals(0, route.coverageRegions());
        assertEquals(1, route.attempts());
        assertNull(route.choose(points(point(0, 70, 0)), 0));
    }
    private static JsonObject point(int x, int y, int z) {
        var p = new JsonObject();
        p.addProperty("x", x); p.addProperty("y", y); p.addProperty("z", z);
        p.addProperty("dimension", "minecraft:overworld");
        return p;
    }
    private static JsonArray points(JsonObject... points) {
        var array = new JsonArray();
        for (var p : points) array.add(p);
        return array;
    }

    @Test void verifiedArrivalIsRememberedAcrossCallsAndDifferentElevationDoesNotMakeItNew() {
        var route = new ExplorationRoute(8, 64);
        route.observe(point(0, 70, 0));
        route.attempt(point(8, 70, 0));
        assertTrue(route.observe(point(8, 70, 0)));
        assertNull(route.choose(points(point(0, 78, 0), point(8, 78, 0)), 0));
        route.attempt(point(8, 78, 0));
        assertFalse(route.observe(point(8, 78, 0)));
        assertEquals(1, route.regions());
    }

    @Test void headingContinuesOverDiagonalZigzagAndCanTurnWhenStraightGroundIsUnavailable() {
        var route = new ExplorationRoute(8, 64);
        route.observe(point(0, 70, 0));
        route.attempt(point(8, 70, 0));
        route.observe(point(8, 70, 0));
        assertEquals(16, route.choose(points(point(14, 70, 6), point(16, 70, 0),
                point(14, 70, -6)), 0).get("x").getAsInt());
        assertNotNull(route.choose(points(point(14, 70, 6)), 0));
    }

    @Test void returnToAnObservedRegionAndCancelledPendingMovementDoNotCreateProgress() {
        var route = new ExplorationRoute(8, 64);
        route.observe(point(0, 70, 0));
        route.attempt(point(8, 70, 0));
        route.observe(point(8, 70, 0));
        route.attempt(point(0, 70, 0));
        assertFalse(route.observe(point(0, 70, 0)));
        route.attempt(point(16, 70, 0));
        route.abandon();
        assertFalse(route.observe(point(16, 70, 0)));
        assertEquals(1, route.regions());
    }

    @Test void longUphillSamplesAreNotRejectedByCombiningHeightWithTheHorizontalHopLimit() {
        var route = new ExplorationRoute(8, 64);
        route.observe(point(0, 70, 0));
        assertNotNull(route.choose(points(point(16, 75, 0)), 16));
        assertNull(route.choose(points(point(18, 70, 0)), 16));
        assertNull(route.choose(points(point(16, 70, 0)), 8));
        assertNotNull(route.choose(points(point(8, 70, 0)), 8));
    }

    @Test void onlyCompleteKnownCurrentQueryPagesInfluenceCoverageAndExpiredOrChangedWorldFactsAreDropped() {
        var route = new ExplorationRoute(8, 64);
        route.observe(point(0, 70, 0));
        var args = BoundedSearchTest.json("{\"targets\":[\"dirt\"],\"r\":16,\"offset\":0}");
        for (int offset = 0; offset <= 16384; offset += 4096) {
            args.addProperty("offset", offset);
            if (offset > 0) args.add("scan_center", BoundedSearchTest.json("{\"x\":0,\"y\":70,\"z\":0}"));
            route.searchPage(args, new com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome(
                    true, "page", BoundedSearchTest.page(offset, false)), 10);
            assertEquals(offset == 16384 ? 1 : 0, route.coverageRegions());
        }
        route.choose(points(point(16,70,0)),16);
        assertTrue(route.selectedCoverage() > 0 && route.selectedCoverage() < 1);
        args.addProperty("offset",0); args.remove("scan_center");
        var unknown = BoundedSearchTest.page(0,false);
        unknown.addProperty("unknown",1); unknown.addProperty("cells_read",4095);
        route.searchPage(args, new com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome(true,"page",unknown), 120011);
        assertEquals(0, route.coverageRegions());
        route.invalidateCoverage();
        assertEquals(0, route.coverageRegions());
    }
}
