package com.neko.mcbot.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExplorationRouteTest {
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
