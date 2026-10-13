package com.neko.mcbot.agent;

import com.google.gson.*;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class BoundedGatherTest {
    static JsonObject state(int count) {
        var state = JsonParser.parseString("{\"x\":0,\"y\":90,\"z\":0,\"dimension\":\"minecraft:overworld\"}").getAsJsonObject();
        var inventory = new JsonObject();
        var slots = new JsonArray();
        for (int i = 0; i < 36; i++) {
            var slot = new JsonObject();
            slot.addProperty("slot", i);
            slot.addProperty("item", i == 0 ? "minecraft:dirt" : "");
            slot.addProperty("count", i == 0 ? count : 0);
            slots.add(slot);
        }
        inventory.add("slots", slots);
        state.add("inventory", inventory);
        return state;
    }

    static JsonObject block(boolean inReach) {
        var block = JsonParser.parseString("{\"x\":2,\"y\":90,\"z\":0,\"block\":\"minecraft:dirt\","
                + "\"stand_positions\":[{\"x\":1,\"y\":90,\"z\":0}]}").getAsJsonObject();
        block.addProperty("can_work_here", inReach);
        return block;
    }

    static final class Fixture {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();
        long now;
        int count, dug, moved, cancelled;
        boolean ground;
        boolean missing;
        boolean noYield;
        int yieldAfter;
        boolean needConfirm;
        boolean full;
        CompletableFuture<ToolOutcome> mining;
        final BoundedGather gather;
        Fixture(int requested, int baseline) {
            count = baseline;
            gather = new BoundedGather(BoundedGather.parse("{\"item\":\"dirt\",\"targets\":[\"dirt\"],\"count\":"
                    + requested + "}"), state(baseline), () -> now, this::remote,
                    queue::addLast, () -> cancelled++, text -> { });
        }
        CompletableFuture<ToolOutcome> remote(String tool, String json) {
            calls.add(tool);
            now++;
            var data = new JsonObject();
            switch (tool) {
                case "status" -> data = state(count);
                case "scan_area" -> {
                    var items = new JsonArray();
                    if (ground) {
                        var item = block(false);
                        item.addProperty("item", "minecraft:dirt");
                        item.addProperty("target_uuid", "drop-" + dug);
                        item.addProperty("count", 1);
                        item.addProperty("can_touch_here", false);
                        item.addProperty("pickup_allowed", true);
                        item.addProperty("pickup_delayed", true);
                        items.add(item);
                    }
                    data.add("item_targets", items);
                }
                case "find_resource" -> {
                    var targets = new JsonArray();
                    if (!missing) targets.add(block(true));
                    data.add("targets", targets);
                    data.addProperty("unknown", 12);
                    data.addProperty("truncated", true);
                }
                case "break_block" -> {
                    if (mining != null) return mining;
                    dug++;
                    ground = !noYield && dug > yieldAfter;
                    data.addProperty("removed", true);
                    data.addProperty("collected_count", 0);
                    data.addProperty("remaining_count", 1);
                }
                case "move_to" -> {
                    moved++;
                    if (needConfirm) return CompletableFuture.completedFuture(new ToolOutcome(false, "NEED_CONFIRM:route", data));
                }
                case "collect" -> {
                    if (full) return CompletableFuture.completedFuture(new ToolOutcome(false, "INVENTORY_FULL:drop remains", data));
                    int pickup = ground ? 1 : 0;
                    count += pickup;
                    ground = false;
                    data.addProperty("collected_count", pickup);
                }
                case "wait" -> { }
                default -> fail(tool);
            }
            return CompletableFuture.completedFuture(new ToolOutcome(true, "receipt", data));
        }
        void drain() {
            int budget = 10000;
            while (!queue.isEmpty()) {
                if (--budget == 0) fail("local continuation did not settle");
                queue.removeFirst().run();
            }
        }
        ToolOutcome run() {
            var result = gather.start();
            drain();
            return result.join();
        }
    }

    @Test void sixtyFourActualItemsRunLocallyPastTheOldTwelveStepBatchBoundary() {
        var f = new Fixture(64, 0);
        var result = f.run();
        assertTrue(result.ok(), result.feedback());
        assertEquals(64, f.count);
        assertEquals(64, f.dug);
        assertEquals(64, f.moved, "must walk to the real drops before pickup");
        assertTrue(result.data().get("goal_satisfied").getAsBoolean());
        assertEquals(64, result.data().get("actual_progress").getAsInt());
        assertEquals(0, f.cancelled);
    }

    @Test void additionalQuantityUsesTaskStartBaselineRatherThanExistingInventory() {
        var f = new Fixture(3, 5);
        assertTrue(f.run().ok());
        assertEquals(8, f.count);
        assertEquals(3, f.dug);
    }

    @Test void missingResourcesStopWithoutInventingCompletenessOrDiggingToExplore() {
        var f = new Fixture(3, 0);
        f.missing = true;
        var result = f.run();
        assertFalse(result.ok());
        assertTrue(result.feedback().startsWith("RESOURCE_BLOCKED:"));
        assertFalse(f.calls.contains("break_block"));
        assertEquals(12, result.data().getAsJsonObject("last_observation").get("unknown").getAsInt());
    }

    @Test void noInventoryProgressStopsAfterThreeRealOperations() {
        var f = new Fixture(64, 0);
        f.noYield = true;
        var result = f.run();
        assertFalse(result.ok());
        assertTrue(result.feedback().startsWith("NO_PROGRESS:"));
        assertEquals(3, f.dug);
        assertEquals(0, f.count);
    }

    @Test void thirdMiningDropIsPickedUpBeforeApplyingTheNoProgressGuard() {
        var f = new Fixture(1, 0);
        f.yieldAfter = 2;
        var result = f.run();
        assertTrue(result.ok(), result.feedback());
        assertEquals(3, f.dug);
        assertEquals(1, f.count);
        assertTrue(f.calls.contains("collect"));
    }

    @Test void routeConsentAndFullInventoryStopAndPreserveGroundItems() {
        for (boolean confirmation : List.of(true, false)) {
            var f = new Fixture(64, 0);
            f.needConfirm = confirmation;
            f.full = !confirmation;
            var result = f.run();
            assertFalse(result.ok());
            assertTrue(result.feedback().startsWith(confirmation ? "NEED_CONFIRM:" : "INVENTORY_FULL:"));
            assertEquals(1, f.dug);
            assertTrue(f.ground);
            assertEquals(0, f.count);
        }
    }

    @Test void pendingMiningDoesNotStartAnotherStepAndLateTerminalCannotResumeCancellation() {
        var f = new Fixture(64, 0);
        f.mining = new CompletableFuture<>();
        var result = f.gather.start();
        f.drain();
        assertFalse(result.isDone());
        assertEquals("break_block", f.calls.getLast());
        int calls = f.calls.size();
        f.gather.stop("CANCELLED:owner");
        f.mining.complete(new ToolOutcome(true, "late", JsonParser.parseString("{\"removed\":true}").getAsJsonObject()));
        f.drain();
        assertFalse(result.join().ok());
        assertEquals(calls, f.calls.size());
        assertEquals(1, f.cancelled);
    }

    @Test void overallTimeoutStopsThePendingChildWithoutPolling() {
        var f = new Fixture(64, 0);
        f.mining = new CompletableFuture<>();
        var result = f.gather.start();
        f.drain();
        f.now += 600001;
        f.gather.tick();
        assertTrue(result.join().feedback().startsWith("TIMEOUT:"));
        assertEquals(1, f.cancelled);
    }

    @Test void malformedPlanIsRejectedBeforeAnyWorldAction() {
        for (String json : List.of("{}", "{\"item\":\"dirt\",\"targets\":[\"dirt\"],\"count\":1.2}",
                "{\"item\":\"dirt\",\"targets\":[],\"count\":1}",
                "{\"item\":\"dirt\",\"targets\":[\"dirt\"],\"count\":1,\"r\":17}",
                "{\"item\":\"dirt\",\"targets\":[\"dirt\"],\"count\":1,\"unexpected\":true}"))
            assertThrows(RuntimeException.class, () -> BoundedGather.parse(json));
    }
}
