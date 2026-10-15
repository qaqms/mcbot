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
        final List<JsonObject> moves = new ArrayList<>();
        final ArrayDeque<String> breakFailures = new ArrayDeque<>();
        final ArrayDeque<String> moveFailures = new ArrayDeque<>();
        int[] dropStances = {1};
        boolean pickupOnFailedMove, leaveScopeOnFailedMove;
        int missedPickupMoves;
        int hiddenDropScans;
        int[] dropStancesAfterFailedMove;
        boolean reportDrops;
        double stateX;
        Double stateXAfterFirstMining;
        boolean trackRemovedTargets;
        final java.util.Set<Integer> removedTargets = new java.util.HashSet<>();
        String failureAfterFirstMining;
        int pendingDrops;
        List<JsonObject> blockTargets;
        long now;
        int count, dug, moved, cancelled, breakAttempts;
        boolean ground;
        boolean missing;
        boolean noYield;
        int yieldAfter;
        boolean needConfirm;
        boolean full;
        boolean pickupStillDelayed;
        boolean touching;
        boolean pickupDuringMining;
        boolean stolen;
        int pickupAttempts;
        int clearPerMining;
        final List<JsonObject> breakArgs = new ArrayList<>();
        CompletableFuture<ToolOutcome> mining;
        CompletableFuture<ToolOutcome> movement;
        CompletableFuture<ToolOutcome> waiting;
        final BoundedGather gather;
        Fixture(int requested, int baseline) {
            this(requested, baseline, "task");
        }
        Fixture(int requested, int baseline, String scope) {
            count = baseline;
            gather = new BoundedGather(BoundedGather.parse("{\"item\":\"dirt\",\"targets\":[\"dirt\"],\"count\":"
                    + requested + ",\"scope\":\"" + scope + "\"}"), state(baseline), () -> now, this::remote,
                    queue::addLast, () -> cancelled++, text -> { });
        }
        CompletableFuture<ToolOutcome> remote(String tool, String json) {
            calls.add(tool);
            now++;
            var data = new JsonObject();
            switch (tool) {
                case "status" -> {
                    data = state(count);
                    data.addProperty("x", stateX);
                }
                case "scan_area" -> {
                    var items = new JsonArray();
                    boolean hidden = dug > 0 && hiddenDropScans-- > 0;
                    if (ground && !hidden) {
                        var item = withStances(false, dropStances);
                        item.addProperty("item", "minecraft:dirt");
                        item.addProperty("target_uuid", "drop-" + dug);
                        item.addProperty("count", 1);
                        item.addProperty("can_touch_here", touching);
                        item.addProperty("pickup_allowed", true);
                        item.addProperty("pickup_delayed", true);
                        items.add(item);
                    }
                    data.add("item_targets", items);
                }
                case "find_resource" -> {
                    var targets = new JsonArray();
                    if (!missing) {
                        if (blockTargets == null) targets.add(block(true));
                        else blockTargets.stream().filter(target -> !trackRemovedTargets
                                || !removedTargets.contains(target.get("x").getAsInt())).forEach(targets::add);
                    }
                    data.add("targets", targets);
                    data.addProperty("unknown", 12);
                    data.addProperty("truncated", true);
                }
                case "break_block" -> {
                    breakAttempts++;
                    var args = JsonParser.parseString(json).getAsJsonObject();
                    breakArgs.add(args);
                    assertTrue(args.get("clear_obstructions").getAsBoolean());
                    assertEquals("minecraft:dirt", JsonParser.parseString(json).getAsJsonObject()
                            .get("expected_block").getAsString());
                    if (mining != null) return mining;
                    if (dug > 0 && failureAfterFirstMining != null) return CompletableFuture.completedFuture(
                            new ToolOutcome(false, failureAfterFirstMining, data));
                    if (!breakFailures.isEmpty()) return CompletableFuture.completedFuture(
                            new ToolOutcome(false, breakFailures.removeFirst(), data));
                    dug++;
                    removedTargets.add(args.get("x").getAsInt());
                    if (stateXAfterFirstMining != null && dug == 1) stateX = stateXAfterFirstMining;
                    if (!noYield && dug > yieldAfter) { ground = true; pendingDrops++; }
                    data.addProperty("removed", true);
                    data.addProperty("collected_count", 0);
                    data.addProperty("remaining_count", 1);
                    data.addProperty("cleared_blocks", clearPerMining);
                    if (reportDrops) {
                        var drops = new JsonArray();
                        var drop = new JsonObject();
                        drop.addProperty("item", "minecraft:dirt");
                        drops.add(drop);
                        data.add("drops", drops);
                    }
                    if (pickupDuringMining) automaticPickup(true);
                }
                case "move_to" -> {
                    var args = JsonParser.parseString(json).getAsJsonObject();
                    assertTrue(args.get("exact").getAsBoolean(),
                            "a selected work/pickup stance must not finish in an adjacent cell");
                    moves.add(args);
                    moved++;
                    if (movement != null) return movement;
                    if (!moveFailures.isEmpty()) {
                        if (pickupOnFailedMove) automaticPickup(true);
                        if (leaveScopeOnFailedMove) stateX = 999;
                        if (dropStancesAfterFailedMove != null) dropStances = dropStancesAfterFailedMove;
                        return CompletableFuture.completedFuture(
                                new ToolOutcome(false, moveFailures.removeFirst(), data));
                    }
                    if (needConfirm) return CompletableFuture.completedFuture(new ToolOutcome(false, "NEED_CONFIRM:route", data));
                    if (moved <= missedPickupMoves) break;
                    touching = true;
                    automaticPickup(false);
                }
                case "wait" -> {
                    if (waiting != null) return waiting;
                    automaticPickup(true);
                }
                default -> fail(tool);
            }
            return CompletableFuture.completedFuture(new ToolOutcome(true, "receipt", data));
        }
        void automaticPickup(boolean waited) {
            if (!ground) return;
            pickupAttempts++;
            if (full || pickupStillDelayed && !waited) return;
            if (!stolen) count += Math.max(1, pendingDrops);
            pendingDrops = 0;
            ground = false;
            touching = false;
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
        assertEquals(1 + f.dug + f.moved, f.calls.stream().filter("status"::equals).count(),
                "each movement needs one fresh observation, not duplicate inventory polling");
        assertFalse(f.calls.contains("wait"), "successful pickup after walking needs no extra fixed delay");
        assertFalse(f.calls.contains("collect"), "walking triggers body pickup without a tool");
        assertTrue(result.data().get("goal_satisfied").getAsBoolean());
        assertEquals(64, result.data().get("actual_progress").getAsInt());
        assertEquals(0, f.cancelled);
    }

    @Test void clearableOccludedResourceRunsLocallyAndCountsOnlyInventoryAsGoalProgress() {
        var f = new Fixture(2, 0);
        var target = withStances(false);
        target.addProperty("can_clear_here", true);
        f.blockTargets = List.of(target);
        f.clearPerMining = 2;
        f.pickupDuringMining = true;
        var result = f.run();
        assertTrue(result.ok());
        assertEquals(2, result.data().get("mined_blocks").getAsInt());
        assertEquals(4, result.data().get("cleared_blocks").getAsInt());
        assertEquals(2, result.data().get("actual_progress").getAsInt());
        assertEquals(0, f.moved);
    }

    @Test void incidentalClearsConsumeTheSameSegmentMiningBudget() {
        var f = new Fixture(100, 0);
        f.clearPerMining = 3;
        f.pickupDuringMining = true;
        var result = f.run();
        assertFalse(result.ok());
        assertTrue(result.feedback().startsWith("BLOCK_LIMIT:"));
        assertEquals(32, f.dug);
        assertEquals(96, result.data().get("cleared_blocks").getAsInt());
        assertEquals(3, f.breakArgs.getLast().get("clear_limit").getAsInt());
    }

    private static List<JsonObject> nearbyBatch(int size) {
        var targets = new ArrayList<JsonObject>();
        for (int i = 0; i < size; i++) {
            var target = block(true);
            target.addProperty("x", i + 1);
            targets.add(target);
        }
        return targets;
    }

    @Test void sameStanceUsesEightTargetBatchesWithoutPerBlockScanningWhenPickupKeepsUp() {
        for (String scope : List.of("task", "subtask")) {
            var f = new Fixture(16, 0, scope);
            f.blockTargets = nearbyBatch(16);
            f.trackRemovedTargets = true;
            f.pickupDuringMining = true;
            var result = f.run();
            assertTrue(result.ok(), result.feedback());
            assertEquals(16, f.dug);
            assertEquals(2, f.calls.stream().filter("find_resource"::equals).count());
            assertEquals(2, f.calls.stream().filter("scan_area"::equals).count());
            assertEquals(14, result.data().get("batch_continuations").getAsInt());
            assertEquals(0, f.moved);
            assertEquals(16, f.calls.stream().filter("break_block"::equals).count());
        }
    }

    @Test void outstandingSmallQuantityEndsTheBatchBeforeChasingDropsAndDoesNotMineTheWholePage() {
        var f = new Fixture(2, 0);
        f.blockTargets = nearbyBatch(8);
        f.trackRemovedTargets = true;
        var result = f.run();
        assertTrue(result.ok(), result.feedback());
        assertEquals(2, f.dug);
        assertEquals(1, f.moved);
        assertEquals(1, f.calls.stream().filter("find_resource"::equals).count());
        int firstMining = f.calls.indexOf("break_block");
        assertEquals(List.of("break_block", "status", "break_block", "status", "scan_area"),
                f.calls.subList(firstMining, firstMining + 5));
    }

    @Test void batchDoesNotBypassNoProgressOrLiveToolFailures() {
        var noYield = new Fixture(64, 0);
        noYield.blockTargets = nearbyBatch(8);
        noYield.noYield = true;
        assertTrue(noYield.run().feedback().startsWith("NO_PROGRESS:"));
        assertEquals(3, noYield.dug);

        var changedTool = new Fixture(8, 0);
        changedTool.blockTargets = nearbyBatch(8);
        changedTool.pickupDuringMining = true;
        changedTool.failureAfterFirstMining = "WRONG_TOOL:hand changed";
        assertTrue(changedTool.run().feedback().startsWith("WRONG_TOOL:"));
        assertEquals(1, changedTool.dug);
        assertEquals(2, changedTool.breakAttempts);
    }

    @Test void actualDisplacementDiscardsTheOldStanceBatchAndQueriesFreshTargets() {
        var f = new Fixture(2, 0);
        f.blockTargets = nearbyBatch(4);
        f.trackRemovedTargets = true;
        f.pickupDuringMining = true;
        f.stateXAfterFirstMining = 1d;
        var result = f.run();
        assertTrue(result.ok(), result.feedback());
        assertEquals(2, f.calls.stream().filter("find_resource"::equals).count());
        assertEquals(0, result.data().get("batch_continuations").getAsInt());
    }

    private static JsonObject withStances(boolean here, int... xs) {
        var target = block(here);
        var points = new JsonArray();
        for (int x : xs) points.add(JsonParser.parseString("{\"x\":" + x + ",\"y\":90,\"z\":0}"));
        target.add("stand_positions", points);
        return target;
    }

    @Test void blockedArrivalTriesTheNextStanceWithoutAnInvalidMiningCall() {
        var f = new Fixture(1, 0);
        f.blockTargets = List.of(withStances(false, 1, 3));
        f.moveFailures.add("TARGET_BLOCKED:changed after scan");
        f.pickupDuringMining = true;
        var result = f.run();
        assertTrue(result.ok(), result.feedback());
        assertEquals(2, f.moved);
        assertEquals(1, f.breakAttempts);
        assertEquals(2, result.data().get("work_position_attempts").getAsInt());
        assertEquals(List.of(1, 3), f.moves.stream().map(p -> p.get("x").getAsInt()).toList());
        assertTrue(f.moves.stream().allMatch(p -> p.getAsJsonObject("work_target").get("x").getAsInt() == 2));
    }

    @Test void blockedDropApproachRecoversLocallyAndRechecksInventoryBetweenStances() {
        for (String failure : List.of("TARGET_BLOCKED:cover", "OUT_OF_REACH:changed",
                "PARTIAL:made progress", "NO_PATH:blocked")) {
            var f = new Fixture(1, 0);
            f.ground = true;
            f.dropStances = new int[]{1, 3};
            f.moveFailures.add(failure);
            var result = f.run();
            assertTrue(result.ok(), result.feedback());
            assertEquals(List.of(1, 3), f.moves.stream().map(p -> p.get("x").getAsInt()).toList());
            assertEquals(0, f.dug);
            int firstMove = f.calls.indexOf("move_to");
            assertEquals("status", f.calls.get(firstMove + 1));
            assertEquals("scan_area", f.calls.get(firstMove + 2));
            assertEquals("move_to", f.calls.get(firstMove + 3));
        }
    }

    @Test void failedPickupRouteUsesTheItemsNewStancesRatherThanItsOldObservation() {
        var f = new Fixture(1, 0);
        f.ground = true;
        f.dropStances = new int[]{1, 3};
        f.dropStancesAfterFailedMove = new int[]{5};
        f.moveFailures.add("PARTIAL:item moved");
        assertTrue(f.run().ok());
        assertEquals(List.of(1, 5), f.moves.stream().map(p -> p.get("x").getAsInt()).toList());
        assertTrue(f.moves.stream().allMatch(p -> p.has("max_digs")));
    }

    @Test void successfulRouteWithoutContactRescansAndTriesAnotherDistinctStance() {
        var f = new Fixture(1, 0);
        f.ground = true;
        f.dropStances = new int[]{1, 3};
        f.missedPickupMoves = 1;
        var result = f.run();
        assertTrue(result.ok(), result.feedback());
        assertEquals(List.of(1, 3), f.moves.stream().map(p -> p.get("x").getAsInt()).toList());
        assertEquals(0, f.dug, "a route receipt is not a pickup receipt");
    }

    @Test void persistentUntouchableDropExhaustsDistinctStancesOnceBeforeOtherWork() {
        var f = new Fixture(1, 0);
        f.ground = true;
        f.dropStances = new int[]{1, 3};
        f.missedPickupMoves = 10;
        f.pickupDuringMining = true;
        var result = f.run();
        assertTrue(result.ok(), result.feedback());
        assertEquals(List.of(1, 3), f.moves.stream().map(p -> p.get("x").getAsInt()).toList());
        assertEquals(1, result.data().get("skipped_items").getAsInt());
    }

    @Test void pickupRetriesNeverExceedSixDistinctStancesForOneEntity() {
        var f = new Fixture(1, 0);
        f.ground = true;
        f.dropStances = new int[]{1, 3, 4, 5, 6, 7};
        f.missedPickupMoves = 10;
        f.pickupDuringMining = true;
        var result = f.run();
        assertTrue(result.ok(), result.feedback());
        assertEquals(List.of(1, 3, 4, 5, 6, 7),
                f.moves.stream().map(p -> p.get("x").getAsInt()).toList());
        assertEquals(1, result.data().get("skipped_items").getAsInt());
    }

    @Test void observedResourceOutsideTheFixedTaskScopeIsExplainedWithoutMoreExploration() {
        var route = new ExplorationRoute(8, 32);
        route.observe(state(0));
        var fresh = state(0);
        fresh.addProperty("x", 31);
        var target = block(true);
        target.addProperty("x", 33);
        var calls = new ArrayList<String>();
        var gather = new BoundedGather(BoundedGather.parse(
                "{\"item\":\"dirt\",\"targets\":[\"dirt\"],\"count\":1}"), state(0), () -> 0,
                (name, json) -> {
                    calls.add(name);
                    var data = new JsonObject();
                    if (name.equals("status")) data = fresh;
                    else if (name.equals("scan_area")) data.add("item_targets", new JsonArray());
                    else if (name.equals("find_resource")) {
                        var targets = new JsonArray();
                        targets.add(target);
                        data.add("targets", targets);
                        data.addProperty("next_offset", -1);
                    } else fail("scope exclusions must not dispatch " + name);
                    return CompletableFuture.completedFuture(new ToolOutcome(true, "fresh", data));
                }, Runnable::run, () -> fail("no pending child"), text -> { }, null, route);
        var result = gather.start().join();
        assertTrue(result.feedback().startsWith("SCOPE_LIMIT:"), result.feedback());
        assertEquals("task_scope", result.data().getAsJsonArray("scope_exclusions").get(0)
                .getAsJsonObject().get("reason").getAsString());
        assertEquals(0, route.attempts());
        assertEquals(List.of("status", "scan_area", "find_resource"), calls);
    }

    @Test void touchingAnItemOutsideTheTaskBoundaryNeedsNoOutOfScopeMovement() {
        var route = new ExplorationRoute(8, 32);
        route.observe(state(0));
        var calls = new ArrayList<String>();
        boolean[] picked = {false};
        var gather = new BoundedGather(BoundedGather.parse(
                "{\"item\":\"dirt\",\"targets\":[\"dirt\"],\"count\":1}"), state(0), () -> 0,
                (name, json) -> {
                    calls.add(name);
                    var data = new JsonObject();
                    if (name.equals("status")) {
                        data = state(picked[0] ? 1 : 0);
                        data.addProperty("x", 31.9);
                    } else if (name.equals("scan_area")) {
                        var items = new JsonArray();
                        var item = new JsonObject();
                        item.addProperty("x", 32.2);
                        item.addProperty("y", 90);
                        item.addProperty("z", 0);
                        item.addProperty("item", "minecraft:dirt");
                        item.addProperty("target_uuid", "boundary-item");
                        item.addProperty("can_touch_here", true);
                        item.addProperty("pickup_allowed", true);
                        items.add(item);
                        data.add("item_targets", items);
                    } else if (name.equals("wait")) picked[0] = true;
                    else fail("passive contact must not dispatch " + name);
                    return CompletableFuture.completedFuture(new ToolOutcome(true, "fresh", data));
                }, Runnable::run, () -> fail("no pending child"), text -> { }, null, route);
        var result = gather.start().join();
        assertTrue(result.ok(), result.feedback());
        assertEquals(List.of("status", "scan_area", "wait", "status"), calls);
        assertEquals(0, route.attempts());
    }

    @Test void settlingMayPickUpTheGoalButOnlyTheNextInventoryConfirmsIt() {
        var f = new Fixture(1, 0);
        f.reportDrops = true;
        f.hiddenDropScans = 1;
        var result = f.run();
        assertTrue(result.ok(), result.feedback());
        assertEquals(1, f.dug);
        assertEquals(1, result.data().get("drop_settle_waits").getAsInt());
        assertEquals("status", f.calls.getLast());
        assertEquals(1, result.data().get("actual_progress").getAsInt());
    }

    @Test void knownMiningDropsGetOneSettlingWaitAndAFreshScanBeforeFurtherWork() {
        var f = new Fixture(1, 0);
        f.reportDrops = true;
        f.hiddenDropScans = 1;
        f.full = true;
        var result = f.run();
        assertFalse(result.ok(), "settling alone does not prove inventory progress");
        assertTrue(result.feedback().startsWith("PICKUP_BLOCKED:"), result.feedback());
        assertEquals(1, f.dug);
        assertEquals(1, result.data().get("drop_settle_waits").getAsInt());
        int firstWait = f.calls.indexOf("wait");
        assertEquals(List.of("wait", "status", "scan_area", "move_to"),
                f.calls.subList(firstWait, firstWait + 4));
        assertEquals(0, result.data().get("actual_progress").getAsInt());
    }

    @Test void disappearedKnownDropsDoNotCauseUnboundedWaitOrInventInventoryProgress() {
        var f = new Fixture(1, 0);
        f.reportDrops = true;
        f.noYield = true;
        var result = f.run();
        assertTrue(result.feedback().startsWith("NO_PROGRESS:"), result.feedback());
        assertEquals(3, f.dug);
        assertEquals(3, result.data().get("drop_settle_waits").getAsInt());
        assertEquals(0, f.count);
    }

    @Test void knownDropsAlreadyPickedUpDoNotAddAPerBlockSettlingDelay() {
        var f = new Fixture(4, 0);
        f.reportDrops = true;
        assertTrue(f.run().ok());
        assertFalse(f.calls.contains("wait"));
        assertEquals(4, f.dug);
    }

    @Test void cancellationAndTimeoutDuringSettlingNeverResumeAfterALateWaitReceipt() {
        for (boolean timeout : List.of(false, true)) {
            var f = new Fixture(1, 0);
            f.reportDrops = true;
            f.hiddenDropScans = 1;
            f.waiting = new CompletableFuture<>();
            var result = f.gather.start();
            f.drain();
            assertEquals("wait", f.calls.getLast());
            int calls = f.calls.size();
            if (timeout) {
                f.now += 600001;
                f.gather.tick();
            } else f.gather.stop("CANCELLED:owner");
            f.waiting.complete(new ToolOutcome(true, "late wait"));
            f.drain();
            assertTrue(result.join().feedback().startsWith(timeout ? "TIMEOUT:" : "CANCELLED:"));
            assertEquals(calls, f.calls.size());
            assertEquals(1, f.cancelled);
        }
    }

    @Test void failedMovementThatAlreadyPickedUpTheGoalDoesNotDispatchAnotherMoveOrMine() {
        var f = new Fixture(1, 0);
        f.ground = true;
        f.dropStances = new int[]{1, 3};
        f.pickupOnFailedMove = true;
        f.moveFailures.add("TARGET_BLOCKED:after movement");
        assertTrue(f.run().ok());
        assertEquals(1, f.moved);
        assertEquals(0, f.dug);
        assertEquals("status", f.calls.getLast());
    }

    @Test void recoveryDoesNotContinueAfterActualMovementLeavesTheCollectionScope() {
        var f = new Fixture(1, 0);
        f.ground = true;
        f.dropStances = new int[]{1, 3};
        f.leaveScopeOnFailedMove = true;
        f.moveFailures.add("PARTIAL:displaced");
        assertTrue(f.run().feedback().startsWith("SCOPE_CHANGED:"));
        assertEquals(1, f.moved);
        assertEquals(0, f.dug);
    }

    @Test void exhaustingUnreachableDropStancesStillAllowsOtherResourcesToMakeProgress() {
        var f = new Fixture(1, 0);
        f.ground = true;
        f.dropStances = new int[]{1, 3};
        f.moveFailures.addAll(List.of("TARGET_BLOCKED:first", "NO_PROGRESS:second"));
        f.pickupDuringMining = true;
        var result = f.run();
        assertTrue(result.ok(), result.feedback());
        assertEquals(2, f.moved);
        assertEquals(1, f.dug);
        assertEquals(1, result.data().get("skipped_items").getAsInt());
    }

    @Test void changedSightAtMiningRetriesOtherStancesOnceAndDeduplicatesCandidates() {
        var f = new Fixture(1, 0);
        f.blockTargets = List.of(withStances(true, 1, 1, 3));
        f.breakFailures.addAll(List.of("TARGET_BLOCKED:cover", "OUT_OF_REACH:displaced"));
        f.pickupDuringMining = true;
        assertTrue(f.run().ok());
        assertEquals(3, f.breakAttempts);
        assertEquals(List.of(1, 3), f.moves.stream().map(p -> p.get("x").getAsInt()).toList());
    }

    @Test void allStancesBlockedStopWithoutRevisitingTheSameTargetOrBreakingAnything() {
        var f = new Fixture(1, 0);
        f.blockTargets = List.of(withStances(false, 1, 3, 4, 5, 6, 7));
        for (int i = 0; i < 6; i++) f.moveFailures.add("NO_PATH:no route");
        var result = f.run();
        assertFalse(result.ok());
        assertTrue(result.feedback().startsWith("RESOURCE_BLOCKED:"));
        assertEquals(6, f.moved);
        assertEquals(0, f.breakAttempts);
        assertEquals(1, result.data().get("skipped_blocks").getAsInt());
    }

    @Test void aRealRemovalInvalidatesOldBlockedTargetsButFreshQueriesStillDriveWork() {
        var f = new Fixture(2, 0);
        var other = block(true);
        other.addProperty("x", 4);
        f.blockTargets = List.of(block(true), other);
        f.breakFailures.add("TARGET_BLOCKED:first target");
        f.moveFailures.add("NO_PATH:first target stance");
        f.pickupDuringMining = true;
        assertTrue(f.run().ok());
        assertEquals(3, f.breakAttempts);
        assertEquals(2, f.dug);
        assertEquals(3, f.calls.stream().filter("find_resource"::equals).count());
    }

    @Test void authorizationAndWrongToolFailuresYieldImmediatelyWithoutTryingAnotherStance() {
        var route = new Fixture(1, 0);
        route.blockTargets = List.of(withStances(false, 1, 3));
        route.moveFailures.add("NEED_CONFIRM:actual route");
        assertTrue(route.run().feedback().startsWith("NEED_CONFIRM:"));
        assertEquals(1, route.moved);
        assertEquals(0, route.breakAttempts);
        var tool = new Fixture(1, 0);
        tool.breakFailures.add("WRONG_TOOL:no harvest");
        assertTrue(tool.run().feedback().startsWith("WRONG_TOOL:"));
        assertEquals(1, tool.breakAttempts);
        assertEquals(0, tool.moved);
    }

    @Test void lostTargetDoesNotTrySixStaleStancesOrMineTheReplacement() {
        var f = new Fixture(1, 0);
        f.blockTargets = List.of(withStances(true, 1, 3));
        f.breakFailures.add("TARGET_LOST:replacement differs");
        assertFalse(f.run().ok());
        assertEquals(1, f.breakAttempts);
        assertEquals(0, f.moved);
        assertEquals(0, f.dug);
    }

    @Test void buriedCandidatesUseOneWorkSemanticRouteBeforeBeingRuledOut() {
        var f = new Fixture(1, 0);
        var buried = withStances(false);
        buried.addProperty("work_failure", "TARGET_BLOCKED:buried stone");
        f.blockTargets = List.of(buried);
        var result = f.run();
        assertTrue(result.ok(), result.feedback());
        assertEquals(1, f.moved);
        assertEquals("work", f.moves.getFirst().get("arrival").getAsString());
        assertEquals(2, f.moves.getFirst().get("x").getAsInt());
        assertEquals(1, f.breakAttempts);
    }

    @Test void anUnreachableBuriedCandidateIsNotRetriedOrMined() {
        var f = new Fixture(1, 0);
        f.blockTargets = List.of(withStances(false));
        f.moveFailures.add("NO_PATH:protected wall");
        var result = f.run();
        assertFalse(result.ok());
        assertTrue(result.feedback().contains("NO_PATH:protected wall"));
        assertEquals(1, f.moved);
        assertEquals(0, f.breakAttempts);
        int move = f.calls.indexOf("move_to");
        assertEquals("status", f.calls.get(move + 1));
    }

    @Test void cancellingRecoveryIgnoresLateMovementAndDoesNotStartMining() {
        var f = new Fixture(1, 0);
        f.blockTargets = List.of(withStances(false, 1, 3));
        f.movement = new CompletableFuture<>();
        var result = f.gather.start();
        f.drain();
        assertFalse(result.isDone());
        f.gather.stop("CANCELLED:owner");
        int calls = f.calls.size();
        f.movement.complete(new ToolOutcome(true, "late arrival"));
        f.drain();
        assertFalse(result.join().ok());
        assertEquals(calls, f.calls.size());
        assertEquals(0, f.breakAttempts);
    }

    @Test void recoveryTimeoutCancelsThePendingMoveAndDoesNotTryRemainingStances() {
        var f = new Fixture(1, 0);
        f.blockTargets = List.of(withStances(false, 1, 3));
        f.movement = new CompletableFuture<>();
        var result = f.gather.start();
        f.drain();
        f.now += 600001;
        f.gather.tick();
        assertTrue(result.join().feedback().startsWith("TIMEOUT:"));
        assertEquals(1, f.cancelled);
        f.movement.complete(new ToolOutcome(true, "late"));
        f.drain();
        assertEquals(1, f.moved);
        assertEquals(0, f.breakAttempts);
    }

    @Test void aRemainingPickupDelayGetsOneBoundedWaitAndRetainsTheGroundItemUntilPickup() {
        var f = new Fixture(1, 0);
        f.pickupStillDelayed = true;
        var result = f.run();
        assertTrue(result.ok(), result.feedback());
        assertEquals(2, f.pickupAttempts);
        assertEquals(1, f.calls.stream().filter("wait"::equals).count());
        assertEquals(1, f.count);
        assertFalse(f.ground);
        assertFalse(f.calls.contains("collect"));
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
        assertTrue(f.moved > 0);
        assertFalse(f.calls.contains("collect"));
    }

    @Test void routeConsentAndFullInventoryStopAndPreserveGroundItems() {
        for (boolean confirmation : List.of(true, false)) {
            var f = new Fixture(64, 0);
            f.needConfirm = confirmation;
            f.full = !confirmation;
            var result = f.run();
            assertFalse(result.ok());
            assertTrue(result.feedback().startsWith(confirmation ? "NEED_CONFIRM:" : "PICKUP_BLOCKED:"));
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

    @Test void pickupDuringMiningUsesFreshInventoryAndSkipsTheEntirePickupSequence() {
        var f = new Fixture(3, 5);
        f.pickupDuringMining = true;
        assertTrue(f.run().ok());
        assertEquals(8, f.count);
        assertEquals(3, f.dug);
        assertEquals(0, f.moved);
        assertFalse(f.calls.contains("wait"));
        assertFalse(f.calls.contains("collect"));
    }

    @Test void anAlreadyTouchableDropNeedsOnlyOneLocalWaitAndNoMiningOrCollect() {
        var f = new Fixture(1, 0);
        f.ground = true;
        f.touching = true;
        assertTrue(f.run().ok());
        assertEquals(0, f.dug);
        assertEquals(0, f.moved);
        assertEquals(1, f.calls.stream().filter("wait"::equals).count());
        assertFalse(f.calls.contains("collect"));
    }

    @Test void disappearanceWithoutInventoryIncreaseNeverCountsAsGoalProgress() {
        var f = new Fixture(1, 0);
        f.stolen = true;
        var result = f.run();
        assertFalse(result.ok());
        assertTrue(result.feedback().startsWith("NO_PROGRESS:"), result.feedback());
        assertEquals(0, result.data().get("actual_progress").getAsInt());
        assertEquals(3, f.dug);
    }

    @Test void materialSubtaskCountsOnlyWhatIsAddedAfterItsOwnFreshStart() {
        var f = new Fixture(2, 5, "subtask");
        f.count = 8;
        var result = f.run();
        assertTrue(result.ok(), result.feedback());
        assertEquals(10, f.count);
        assertEquals(2, f.dug);
        assertEquals(2, result.data().get("actual_progress").getAsInt());
        assertEquals("subtask", result.data().get("scope").getAsString());
        assertTrue(result.feedback().contains("本次采集开始基线=8"));
        assertTrue(result.feedback().contains("不代表主人的整项目标完成"));
    }

    @Test void defaultTaskScopeRetainsTheOriginalDirectiveBaselineAcrossGatherSegments() {
        var f = new Fixture(3, 5);
        f.count = 8;
        var result = f.run();
        assertTrue(result.ok());
        assertEquals(0, f.dug);
        assertEquals("task", result.data().get("scope").getAsString());
        assertTrue(result.feedback().contains("任务开始基线=5"));
    }

    @Test void subtaskCancelledBeforeFirstObservationDoesNotInventABaselineOrContinue() {
        var f = new Fixture(1, 5, "subtask");
        var result = f.gather.start();
        f.gather.stop("CANCELLED:before first state");
        f.drain();
        assertFalse(result.join().ok());
        assertEquals(0, result.join().data().get("actual_progress").getAsInt());
        assertTrue(result.join().feedback().contains("尚未取得"));
        assertTrue(f.calls.isEmpty());
    }

    @Test void invalidSubtaskScopesAreRejectedBeforeWorldActions() {
        for (String scope : List.of("\"step\"", "null", "1", "true", "[]", "{}")) {
            assertThrows(RuntimeException.class, () -> BoundedGather.parse(
                    "{\"item\":\"dirt\",\"targets\":[\"dirt\"],\"count\":1,\"scope\":" + scope + "}"));
        }
    }

    @Test void subtaskTotalUsesCurrentInventoryWithoutRequiringAdditionalItems() {
        var plan = BoundedGather.parse("{\"item\":\"dirt\",\"targets\":[\"dirt\"],\"count\":3,"
                + "\"mode\":\"total\",\"scope\":\"subtask\"}");
        var calls = new java.util.ArrayList<String>();
        var gather = new BoundedGather(plan, null, () -> 0L, (name, json) -> {
            calls.add(name);
            return CompletableFuture.completedFuture(new ToolOutcome(true, "fresh", state(5)));
        }, Runnable::run, () -> fail("no child should need cancellation"), text -> { });
        var result = gather.start().join();
        assertTrue(result.ok(), result.feedback());
        assertEquals(5, result.data().get("actual_progress").getAsInt());
        assertEquals(List.of("status"), calls);
    }
}
