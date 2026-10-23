package com.neko.mcbot.agent;

import com.google.gson.*;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class BoundedSurveyTest {
    static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    static final class World {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();
        final List<JsonObject> moves = new ArrayList<>();
        final ExplorationRoute route = new ExplorationRoute(8, 64);
        int x, y = 90, z, damage, food = 20, foundAfter = 2;
        long now;
        boolean failMove, unknownPosition, environmentSafe = true;
        String moveFailure = "PARTIAL:short route";
        CompletableFuture<ToolOutcome> pending;
        JsonObject state() {
            var data = BoundedGatherTest.state(0);
            data.addProperty("x", x); data.addProperty("y", y); data.addProperty("z", z);
            data.addProperty("hp", 20); data.addProperty("food", food); data.addProperty("on_fire", false);
            data.addProperty("environment_work_safe", environmentSafe);
            var inv = data.getAsJsonObject("inventory");
            inv.addProperty("selected_slot", 0); inv.addProperty("slots_used", 1);
            var slot = inv.getAsJsonArray("slots").get(0).getAsJsonObject();
            slot.addProperty("item", "minecraft:stone_pickaxe"); slot.addProperty("count", 1);
            slot.addProperty("damage", damage); slot.addProperty("max_damage", 131);
            return data;
        }
        CompletableFuture<ToolOutcome> remote(String name, String text) {
            calls.add(name); now++;
            var args = json(text);
            var data = new JsonObject();
            if (name.equals("status")) data = state();
            else if (name.equals("find_resource")) {
                int offset = ExplorationRoute.integer(args, "offset");
                data = BoundedSearchTest.page(offset, false);
                data.add("scan_center", json("{\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + "}"));
                if (moves.size() >= foundAfter && offset == 0) {
                    var target = BoundedGatherTest.block(false);
                    target.addProperty("x", x + 1); target.addProperty("y", y - 2);
                    data.getAsJsonArray("targets").add(target);
                    data.addProperty("matches_observed", 1);
                }
            } else if (name.equals("move_to")) {
                assertTrue(args.get("underground").getAsBoolean());
                assertTrue(args.get("exact").getAsBoolean());
                assertTrue(args.has("bounds"));
                moves.add(args);
                if (pending != null) return pending;
                boolean back = args.get("walk_only").getAsBoolean();
                int digs = back ? 0 : 12;
                assertTrue(ExplorationRoute.integer(args, "max_digs") >= digs);
                if (!unknownPosition) {
                    x = ExplorationRoute.integer(args, "x"); y = ExplorationRoute.integer(args, "y");
                    z = ExplorationRoute.integer(args, "z");
                }
                damage += digs;
                data.addProperty("removed_blocks", digs); data.addProperty("placed_blocks", 0);
                if (failMove) return CompletableFuture.completedFuture(new ToolOutcome(false, moveFailure, data));
            } else fail(name);
            return CompletableFuture.completedFuture(new ToolOutcome(true, "actual", data));
        }
        BoundedSurvey survey(boolean back) {
            var args = back ? json("{\"mode\":\"return\"}")
                    : json("{\"mode\":\"underground\",\"target_y\":70,\"direction\":\"east\",\"targets\":[\"copper_ore\"],\"r\":16}");
            args.addProperty("timeout_seconds", 180);
            return new BoundedSurvey(route, args, () -> now, this::remote, queue::addLast, () -> {});
        }
        void drain() {
            int budget = 1000;
            while (!queue.isEmpty()) { assertTrue(--budget > 0); queue.removeFirst().run(); }
        }
    }

    @Test void descentSearchAndReturnShareTheOriginalBudgetAndNeverCountDiscoveryAsInventory() {
        var w = new World();
        var future = w.survey(false).start(); w.drain();
        var receipt = future.join();
        assertTrue(receipt.ok(), receipt.feedback());
        assertEquals(2, w.moves.size());
        assertEquals(82, w.y);
        assertEquals(2, receipt.data().get("return_steps").getAsInt());
        assertFalse(receipt.data().has("goal_satisfied"));
        assertEquals(15, w.calls.stream().filter("find_resource"::equals).count(),
                "each observation must finish all five pages even if the first page has a candidate");
        var back = w.survey(true).start(); w.drain();
        assertTrue(back.join().ok(), back.join().feedback());
        assertEquals(0, w.x); assertEquals(90, w.y);
        assertEquals(4, w.route.attempts());
        assertEquals(Integer.MAX_VALUE, w.route.remainingDigs());
        assertEquals(24, w.route.undergroundDigs());
        assertTrue(w.moves.subList(2, 4).stream().allMatch(p -> p.get("walk_only").getAsBoolean()));
    }
    @Test void emptySurveyReservesReturnAttemptsAndCannotRefreshThemOnAnotherCall() {
        var w = new World(); w.foundAfter = 999;
        var future = w.survey(false).start(); w.drain();
        assertTrue(future.join().feedback().startsWith("EXPLORATION_LIMIT:"));
        assertEquals(4, w.moves.size());
        var repeated = w.survey(false).start(); w.drain();
        assertFalse(repeated.join().ok());
        assertEquals(4, w.moves.size());
        var back = w.survey(true).start(); w.drain();
        assertTrue(back.join().ok(), back.join().feedback());
        assertEquals(8, w.route.attempts());
        assertEquals(0, w.x);
    }
    @Test void lowSuppliesStopBeforeSearchingOrMovingAndStillAllowReturn() {
        var w = new World(); w.damage = 120;
        var future = w.survey(false).start(); w.drain();
        assertTrue(future.join().feedback().startsWith("SUPPLY_LOW:"));
        assertEquals(List.of("status"), w.calls);
        var back = w.survey(true).start(); w.drain();
        assertTrue(back.join().ok());
    }
    @Test void sharedEnvironmentFactStopsBothDescentAndReturnWithoutRefreshingOriginalLedger() {
        var fresh = new World();
        fresh.environmentSafe = false;
        var stopped = fresh.survey(false).start(); fresh.drain();
        assertTrue(stopped.join().feedback().startsWith("ENVIRONMENT_UNSAFE:"));
        assertEquals(List.of("status"), fresh.calls);
        assertEquals(0, fresh.route.attempts());
        var progressed = new World();
        progressed.foundAfter = 1;
        var first = progressed.survey(false).start(); progressed.drain();
        assertTrue(first.join().ok(), first.join().feedback());
        int attempts = progressed.route.attempts(), digs = progressed.route.remainingDigs();
        progressed.environmentSafe = false;
        progressed.calls.clear();
        var back = progressed.survey(true).start(); progressed.drain();
        assertTrue(back.join().feedback().startsWith("ENVIRONMENT_UNSAFE:"));
        assertEquals(List.of("status"), progressed.calls);
        assertEquals(attempts, progressed.route.attempts());
        assertEquals(digs, progressed.route.remainingDigs());
        assertTrue(progressed.route.hasUndergroundTrail());
    }
    @Test void partialRouteReadsTheActualLandingBeforeReportingAndKeepsItsReturnPoint() {
        var w = new World(); w.failMove = true;
        var future = w.survey(false).start(); w.drain();
        assertTrue(future.join().feedback().startsWith("PARTIAL:"));
        assertEquals("status", w.calls.getLast());
        assertEquals(1, w.route.returnSteps());
        assertEquals(Integer.MAX_VALUE, w.route.remainingDigs());
        assertEquals(12, w.route.undergroundDigs());
    }
    @Test void cancellationAndLateMovementNeverStartAnotherSearchOrReturn() {
        var w = new World(); w.pending = new CompletableFuture<>();
        var run = w.survey(false);
        var future = run.start(); w.drain();
        int count = w.calls.size();
        run.stop("CANCELLED:owner");
        w.pending.complete(new ToolOutcome(true, "late", json("{\"removed_blocks\":12}")));
        w.drain();
        assertTrue(future.join().feedback().startsWith("CANCELLED:"));
        assertEquals(count, w.calls.size());
        assertEquals(1, w.moves.size());
    }
    @Test void returnWithoutObservedTrailCannotInventAnOrigin() {
        var w = new World();
        var future = w.survey(true).start(); w.drain();
        assertTrue(future.join().feedback().startsWith("NO_ROUTE:"));
        assertEquals(List.of("status"), w.calls);
    }
    @Test void workMovementBetweenSurveysIsReconciledBeforeReturning() {
        var w = new World();
        var outbound = w.survey(false).start(); w.drain();
        assertTrue(outbound.join().ok());
        w.z = 3;
        var back = w.survey(true).start(); w.drain();
        assertTrue(back.join().ok(), back.join().feedback());
        assertEquals(5, w.route.attempts());
        assertEquals(0, w.z);
        assertEquals(90, w.y);
    }
    @Test void nonlocalFailureStillAccountsKnownChangesAndFreshLandingWithoutRetrying() {
        var w = new World(); w.failMove = true; w.moveFailure = "TOOL_CHANGED:actual tool change";
        var future = w.survey(false).start(); w.drain();
        assertTrue(future.join().feedback().startsWith("TOOL_CHANGED:"));
        assertEquals(Integer.MAX_VALUE, w.route.remainingDigs());
        assertEquals(12, w.route.undergroundDigs());
        assertEquals(1, w.route.returnSteps());
        assertEquals("status", w.calls.getLast());
        assertEquals(1, w.moves.size());
    }
    @Test void timeoutAndOutOfScopeLandingsCannotStartFurtherWork() {
        var timed = new World(); timed.pending = new CompletableFuture<>();
        var run = timed.survey(false); var result = run.start(); timed.drain();
        int count = timed.calls.size();
        timed.now += 180000; run.tick();
        timed.pending.complete(new ToolOutcome(true, "late", json("{\"removed_blocks\":12}")));
        timed.drain();
        assertTrue(result.join().feedback().startsWith("TIMEOUT:"));
        assertEquals(count, timed.calls.size());

        var moved = new World(); moved.pending = new CompletableFuture<>();
        var outside = moved.survey(false).start(); moved.drain();
        moved.x = 70;
        moved.pending.complete(new ToolOutcome(false, "PARTIAL:external movement", json("{\"removed_blocks\":12}")));
        moved.drain();
        assertTrue(outside.join().feedback().startsWith("SCOPE_CHANGED:"));
        assertEquals(Integer.MAX_VALUE, moved.route.remainingDigs());
        assertEquals(12, moved.route.undergroundDigs());
        assertEquals("status", moved.calls.getLast());
    }
    @Test void undergroundScopeUsesTheSameFeetSphereAsServerBounds() {
        var route = new ExplorationRoute(8, 16);
        var state = new World().state();
        state.addProperty("x", .9); state.addProperty("z", .9);
        route.beginUnderground(state);
        assertEquals(json("{\"x\":0,\"y\":90,\"z\":0,\"radius\":16}"), route.bounds());
        state.addProperty("x", -15.1);
        assertDoesNotThrow(() -> route.observe(state), "feet -16 is on the server sphere");
        state.addProperty("x", 16.9);
        assertDoesNotThrow(() -> route.observe(state), "feet 16 is also on the sphere");
        state.addProperty("x", 17);
        assertThrows(IllegalArgumentException.class, () -> route.observe(state));
    }
}
