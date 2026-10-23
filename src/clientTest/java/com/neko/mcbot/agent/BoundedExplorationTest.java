package com.neko.mcbot.agent;

import com.google.gson.*;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class BoundedExplorationTest {
    private static JsonObject json(String s) { return JsonParser.parseString(s).getAsJsonObject(); }
    private static JsonObject state(int x, int z, int count) {
        var state = BoundedGatherTest.state(count);
        state.addProperty("x", x); state.addProperty("z", z);
        return state;
    }
    private static JsonArray points(int x, int z) {
        var points = new JsonArray();
        points.add(json("{\"x\":" + (x + 8) + ",\"y\":90,\"z\":" + z + "}"));
        points.add(json("{\"x\":" + x + ",\"y\":90,\"z\":" + (z + 8) + "}"));
        return points;
    }
    private static final class World {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();
        final List<JsonObject> moves = new ArrayList<>();
        final List<Integer> offsets = new ArrayList<>();
        int x, z, count, cancel, mines, scans;
        long now;
        boolean paged, neverFound, staticPosition, failFirst, partial, wrongDimension;
        CompletableFuture<ToolOutcome> pending;
        CompletableFuture<ToolOutcome> remote(String tool, String text) {
            calls.add(tool); now++;
            var args = json(text);
            var data = new JsonObject();
            switch (tool) {
                case "status" -> {
                    data = state(x, z, count);
                    if (wrongDimension && !moves.isEmpty()) data.addProperty("dimension", "minecraft:the_nether");
                }
                case "scan_area" -> {
                    scans++;
                    data.add("item_targets", new JsonArray());
                    if (args.has("frontiers")) data.add("exploration_points", points(x, z));
                }
                case "find_resource" -> {
                    int offset = args.get("offset").getAsInt();
                    offsets.add(offset);
                    var targets = new JsonArray();
                    if (!neverFound && (paged ? offset == 4096 : x != 0 || z != 0)) {
                        var target = BoundedGatherTest.block(true);
                        target.addProperty("x", x + 2);
                        target.addProperty("z", z);
                        targets.add(target);
                    }
                    data.add("targets", targets);
                    data.addProperty("next_offset", paged && offset == 0 ? 4096 : -1);
                    data.add("scan_center", json("{\"x\":" + x + ",\"y\":90,\"z\":" + z + "}"));
                    data.add("exploration_points", points(x, z));
                    data.addProperty("unknown", 12);
                }
                case "move_to" -> {
                    assertTrue(args.get("walk_only").getAsBoolean());
                    assertTrue(args.get("exact").getAsBoolean());
                    moves.add(args);
                    if (pending != null) return pending;
                    if (failFirst && moves.size() == 1)
                        return CompletableFuture.completedFuture(new ToolOutcome(false, "NO_PATH:cliff", data));
                    if (!staticPosition) { x = args.get("x").getAsInt(); z = args.get("z").getAsInt(); }
                    if (partial) {
                        x -= 3;
                        return CompletableFuture.completedFuture(new ToolOutcome(false, "PARTIAL:actual landing", data));
                    }
                }
                case "break_block" -> { mines++; count++; data.addProperty("removed", true); }
                default -> fail(tool);
            }
            return CompletableFuture.completedFuture(new ToolOutcome(true, "actual receipt", data));
        }
        void drain() {
            int cap = 1000;
            while (!queue.isEmpty()) { assertTrue(--cap > 0); queue.removeFirst().run(); }
        }
        BoundedGather gather(String options) {
            return new BoundedGather(BoundedGather.parse("{\"item\":\"dirt\",\"targets\":[\"dirt\"],\"count\":1"
                    + options + "}"), state(0, 0, 0), () -> now, this::remote, queue::addLast, () -> cancel++, s -> {});
        }
        BoundedExplore explore(ExplorationRoute route) {
            return new BoundedExplore(route, 180, () -> now, this::remote, queue::addLast, () -> cancel++);
        }
    }

    @Test void gatherFindsResourcesAfterVerifiedTravelAndKeepsItsOriginalItemBaseline() {
        var w = new World();
        var f = w.gather(",\"scope\":\"subtask\"");
        var result = f.start(); w.drain();
        assertTrue(result.join().ok(), result.join().feedback());
        assertEquals(1, w.mines);
        assertEquals(1, result.join().data().get("actual_progress").getAsInt());
        assertEquals(1, result.join().data().get("explored_regions").getAsInt());
        assertEquals("subtask", result.join().data().get("scope").getAsString());
    }
    @Test void gatherContinuesPagesBeforeMovingAndUsesTheSameObservedCenter() {
        var w = new World(); w.paged = true;
        var result = w.gather(",\"r\":16").start(); w.drain();
        assertTrue(result.join().ok());
        assertEquals(List.of(0, 4096), w.offsets);
        assertTrue(w.moves.isEmpty());
    }
    @Test void defaultGatherFindsAndWorksAResourceBeyondTheOldEightBlockRadius() {
        var queue = new ArrayDeque<Runnable>();
        var offsets = new ArrayList<Integer>();
        int[] x = {0}, mined = {0};
        var gather = new BoundedGather(BoundedGather.parse(
                "{\"item\":\"dirt\",\"targets\":[\"dirt\"],\"count\":1,\"scope\":\"subtask\",\"explore_steps\":0}"),
                state(0,0,0), () -> 0, (name,text) -> {
            var args = json(text);
            var data = new JsonObject();
            switch (name) {
                case "status" -> data = state(x[0],0,mined[0]);
                case "scan_area" -> data.add("item_targets",new JsonArray());
                case "find_resource" -> {
                    assertEquals(16,args.get("r").getAsInt());
                    int offset = args.get("offset").getAsInt();
                    offsets.add(offset);
                    data = BoundedSearchTest.page(offset,false);
                    data.getAsJsonObject("scan_center").addProperty("y",90);
                    if (offset == 16384) {
                        var block = BoundedGatherTest.block(false);
                        block.addProperty("x",12);
                        var stands = new JsonArray();
                        stands.add(json("{\"x\":11,\"y\":90,\"z\":0}"));
                        block.add("stand_positions",stands);
                        data.getAsJsonArray("targets").add(block);
                    }
                }
                case "move_to" -> {
                    assertEquals(11,args.get("x").getAsInt());
                    assertEquals(12,args.getAsJsonObject("work_target").get("x").getAsInt());
                    x[0] = 11;
                }
                case "break_block" -> {
                    assertEquals(12,args.get("x").getAsInt());
                    mined[0]++;
                    data.addProperty("removed",true);
                }
                default -> fail(name);
            }
            return CompletableFuture.completedFuture(new ToolOutcome(true,"actual receipt",data));
        },queue::addLast,()->fail("unexpected cancellation"),s->{});
        var result = gather.start();
        while (!queue.isEmpty()) queue.removeFirst().run();
        assertTrue(result.join().ok(),result.join().feedback());
        assertEquals(List.of(0,4096,8192,12288,16384),offsets);
        assertEquals(1,mined[0]);
        assertEquals(0,result.join().data().get("exploration_attempts").getAsInt());
        assertEquals(0,result.join().data().get("coverage_regions").getAsInt(),"removal invalidates coverage");
        assertEquals(0,result.join().data().get("estimated_new_coverage").getAsDouble());
    }
    @Test void unreachableExplorationDirectionIsNotRetriedAndNewDirectionUsesFreshStatus() {
        var w = new World(); w.failFirst = true;
        var result = w.gather("").start(); w.drain();
        assertTrue(result.join().ok());
        assertEquals(2, w.moves.size());
        assertNotEquals(w.moves.get(0), w.moves.get(1));
        assertEquals(1, result.join().data().get("explored_regions").getAsInt());
    }
    @Test void partialMovementIsReobservedAndCanDiscoverResourcesFromTheActualLanding() {
        var w = new World(); w.partial = true;
        var result = w.gather("").start(); w.drain();
        assertTrue(result.join().ok());
        assertEquals(5, w.x);
        assertEquals(1, w.mines);
    }
    @Test void explorationBudgetStopsAnEmptyWorldWithoutMiningOrClaimingAbsence() {
        var w = new World(); w.neverFound = true;
        var result = w.gather(",\"explore_steps\":3").start(); w.drain();
        assertTrue(result.join().feedback().startsWith("EXPLORATION_LIMIT:"));
        assertEquals(3, w.moves.size());
        assertEquals(0, w.mines);
        assertTrue(result.join().feedback().contains("不证明无资源"));
    }
    @Test void disablingExplorationOrLeavingTheDimensionStopsWithoutExpandingScope() {
        var local = new World(); local.neverFound = true;
        var result = local.gather(",\"explore_steps\":0").start(); local.drain();
        assertTrue(result.join().feedback().startsWith("RESOURCE_BLOCKED:"));
        assertTrue(local.moves.isEmpty());
        var changed = new World(); changed.wrongDimension = true;
        var other = changed.gather("").start(); changed.drain();
        assertTrue(other.join().feedback().startsWith("SCOPE_CHANGED:"));
        assertEquals(0, changed.mines);
    }
    @Test void directExploreDoesNotNeedAResourceOrItemGoalAndSharesBudgetAcrossCalls() {
        var w = new World();
        var route = new ExplorationRoute(2, 64);
        for (int i = 0; i < 2; i++) {
            var result = w.explore(route).start(); w.drain();
            assertTrue(result.join().ok());
            assertTrue(result.join().data().get("exploration_advanced").getAsBoolean());
            assertFalse(result.join().data().has("goal_satisfied"));
        }
        var ended = w.explore(route).start(); w.drain();
        assertTrue(ended.join().feedback().startsWith("EXPLORATION_LIMIT:"));
        assertEquals(2, w.moves.size());
        assertFalse(w.calls.contains("find_resource"));
        assertFalse(w.calls.contains("break_block"));
    }
    @Test void anOkayMoveReceiptWithoutActualDisplacementDoesNotResetSearchOrCountARegion() {
        var w = new World(); w.staticPosition = true;
        var result = w.explore(new ExplorationRoute(2, 64)).start(); w.drain();
        assertFalse(result.join().ok());
        assertEquals(0, result.join().data().get("explored_regions").getAsInt());
        assertFalse(result.join().data().get("exploration_advanced").getAsBoolean());
    }
    @Test void cancelledOrTimedOutExplorationCannotContinueFromALateReceipt() {
        for (boolean timeout : List.of(false, true)) {
            var w = new World(); w.pending = new CompletableFuture<>();
            var f = w.explore(new ExplorationRoute(8, 64));
            var result = f.start(); w.drain();
            assertFalse(result.isDone());
            if (timeout) { w.now += 180001; f.tick(); } else f.stop("CANCELLED:owner");
            int calls = w.calls.size();
            w.pending.complete(new ToolOutcome(true, "late", new JsonObject()));
            w.drain();
            assertEquals(calls, w.calls.size());
            assertFalse(result.join().ok());
            assertEquals(1, w.cancel);
        }
    }
    @Test void malformedExplorationOptionsAreRejected() {
        for (String options : List.of(",\"explore_steps\":2147483648", ",\"explore_steps\":-1",
                ",\"explore_radius\":65", ",\"explore_radius\":8", ",\"explore_steps\":\"8\""))
            assertThrows(RuntimeException.class, () -> new World().gather(options));
    }
}
