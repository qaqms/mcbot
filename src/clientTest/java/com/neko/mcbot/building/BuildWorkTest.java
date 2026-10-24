package com.neko.mcbot.building;

import com.google.gson.JsonParser;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.task.TickTask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BuildWorkTest {
    @BeforeAll static void bootstrap() { com.neko.mcbot.server.BlockActionFixtures.bootstrap(); }

    static final class World extends BuildSurveyTest.World implements BuildWork.Access {
        int actions, reservations, recorded;
        boolean valid = true, reserve = true, wrongState, throwAfter;
        Runnable afterAction = () -> { };
        Runnable beforeReserve = () -> { };
        @Override public com.google.gson.JsonObject surface(BlockPos pos) {
            fail("per-tick construction and final verification must not repeat advisory surface searches");
            return null;
        }
        @Override public BuildSurvey.Access survey() { return this; }
        @Override public ServerTool.Result valid() {
            return valid ? null : new ServerTool.Result(false, "DENIED:changed task or body", null);
        }
        @Override public boolean reserve(BuildTarget.Cell cell) { reservations++; beforeReserve.run(); return reserve; }
        @Override public void recorded(BuildTarget.Cell cell, ServerTool.Result result) { recorded++; }
        @Override public InteractionResult place(BlockPos pos, ItemStack stack, Direction face) {
            actions++;
            stack.shrink(1);
            blocks.put(pos, wrongState ? Blocks.DIRT.defaultBlockState() : proposed);
            afterAction.run();
            if (throwAfter) throw new IllegalStateException("native callback");
            return InteractionResult.SUCCESS;
        }
    }

    private static BuildTarget target(int count) {
        return BuildTarget.parse(JsonParser.parseString("""
                {"anchor":{"x":0,"y":90,"z":0},"fill":{"from":{"x":0,"y":0,"z":0},
                "to":{"x":%d,"y":0,"z":0},"block":"stone"}}
                """.formatted(count - 1)).getAsJsonObject());
    }
    private World world() {
        var w = new World();
        w.inventory.setItem(2, new ItemStack(Items.STONE, 32));
        return w;
    }
    private ServerTool.Result finish(BuildWork work) {
        for (int i = 0; i < 30; i++) {
            var p = work.tickSafe(null);
            if (p instanceof TickTask.Progress.Done done) return done.result();
        }
        throw new AssertionError("work did not stop");
    }

    @Test void oneNativeActionPerTickAndFinalWorldRecheckAreIndependentOfConsumption() {
        var w = world();
        var work = new BuildWork(target(3), w, 0, 12, () -> 0);
        assertInstanceOf(TickTask.Progress.Running.class, work.tickSafe(null));
        assertEquals(1, w.actions);
        assertFalse(work.observation().get("goal_satisfied").getAsBoolean());
        var result = finish(work);
        assertTrue(result.ok(), result.feedback());
        assertEquals(3, w.actions);
        assertEquals(3, result.data().get("consumed_count").getAsInt());
        assertTrue(result.data().get("goal_satisfied").getAsBoolean());
        assertTrue(result.data().get("final_verified").getAsBoolean());
        assertEquals(29, w.inventory.getItem(2).getCount());
        assertEquals(3, result.data().getAsJsonArray("placement_results").size());
    }

    @Test void finalExternalDamageIsLeftMissingWithoutRepairOrFalseCompletion() {
        var w = world();
        var work = new BuildWork(target(1), w, 0, 1, () -> 0);
        work.tickSafe(null);
        w.blocks.clear();
        var result = finish(work);
        assertTrue(result.ok(), "batch was completed, whole goal was not");
        assertFalse(result.data().get("goal_satisfied").getAsBoolean());
        assertEquals(1, result.data().getAsJsonObject("survey").get("remaining").getAsInt());
        assertEquals(1, w.actions);
    }

    @Test void anAlreadyAttemptedCellDestroyedBetweenTicksIsNeverAutomaticallyPlacedAgain() {
        var w = world();
        w.afterAction = w.blocks::clear;
        var result = finish(new BuildWork(target(1), w, 0, 12, () -> 0));
        assertFalse(result.ok());
        assertEquals(1, w.actions);
        assertEquals(1, result.data().get("consumed_count").getAsInt());
    }

    @Test void noWorkAndAlreadySatisfiedAreDifferentAndNeitherConsumesOrReserves() {
        var w = world();
        w.blocks.put(new BlockPos(0, 90, 0), Blocks.STONE.defaultBlockState());
        assertTrue(finish(new BuildWork(target(1), w, 0, 12, () -> 0)).data().get("goal_satisfied").getAsBoolean());
        w.blocks.put(new BlockPos(0, 90, 0), Blocks.DIRT.defaultBlockState());
        var blocked = finish(new BuildWork(target(1), w, 0, 12, () -> 0));
        assertFalse(blocked.ok());
        assertEquals(1, blocked.data().getAsJsonObject("survey").getAsJsonObject("counts").get("needs_clear").getAsInt());
        assertEquals(0, w.actions);
        assertEquals(0, w.reservations);
    }

    @Test void partialMaterialDepletionAndPageActionLimitNeverClaimTheWholeTarget() {
        var w = world();
        w.inventory.setItem(2, new ItemStack(Items.STONE, 2));
        var result = finish(new BuildWork(target(3), w, 0, 12, () -> 0));
        assertTrue(result.ok());
        assertEquals(2, w.actions);
        assertFalse(result.data().get("goal_satisfied").getAsBoolean());
        var many = world();
        var page = finish(new BuildWork(target(20), many, 0, 2, () -> 0));
        assertEquals(2, many.actions);
        assertEquals(16, page.data().get("next_offset").getAsInt());
        assertEquals(18, page.data().getAsJsonObject("survey").get("remaining").getAsInt());
    }

    @Test void supportIsReassessedAfterRealLowerWorkWithoutASeparateDependencyPlanner() {
        var w = world();
        w.prepare = (p, f) -> p.getX() == 0 || w.blocks.containsKey(p.west()) ? null
                : new ServerTool.Result(false, "TARGET_BLOCKED:support",
                JsonParser.parseString("{\"placement_failure\":\"unsupported_state\"}").getAsJsonObject());
        assertTrue(finish(new BuildWork(target(3), w, 0, 12, () -> 0)).data().get("goal_satisfied").getAsBoolean());
        assertEquals(3, w.actions);
    }

    @Test void cancellationAfterAnActionKeepsEffectsAndCannotExecuteOrVerifyAgain() {
        var w = world();
        var work = new BuildWork(target(3), w, 0, 12, () -> 0);
        work.tickSafe(null);
        work.onAbort();
        int queries = w.queries;
        var result = work.interruptedResult(new ServerTool.Result(false, "CANCELLED:owner stopped", null));
        finish(work);
        assertEquals(1, w.actions);
        assertEquals(queries, w.queries);
        assertEquals(1, result.data().get("consumed_count").getAsInt());
        assertFalse(result.data().get("final_verified").getAsBoolean());
        assertFalse(result.data().get("goal_satisfied").getAsBoolean());
    }

    @Test void changedTaskOrBodyAndRegionConflictStopBeforeAnyNextAction() {
        var w = world();
        w.afterAction = () -> w.valid = false;
        var result = finish(new BuildWork(target(3), w, 0, 12, () -> 0));
        assertFalse(result.ok());
        assertEquals(1, w.actions);
        assertEquals(1, result.data().get("placed_blocks").getAsInt());
        var occupied = world();
        occupied.reserve = false;
        assertTrue(finish(new BuildWork(target(1), occupied, 0, 12, () -> 0)).feedback().startsWith("BUSY:"));
        assertEquals(0, occupied.actions);
    }

    @Test void targetProtectionInventoryAndPermissionsAreRecheckedAfterAdvisorySelection() {
        for (String change : java.util.List.of("protection", "inventory", "permission", "unknown")) {
            var w = world();
            w.beforeReserve = () -> {
                switch (change) {
                    case "protection" -> w.protectedCells.add(new BlockPos(0, 90, 0));
                    case "inventory" -> w.inventory.clearContent();
                    case "permission" -> w.valid = false;
                    default -> w.unknown.add(new BlockPos(0, 90, 0));
                }
            };
            var result = finish(new BuildWork(target(1), w, 0, 12, () -> 0));
            assertFalse(result.ok(), change);
            assertEquals(0, w.actions, change);
        }
    }

    @Test void wrongNativeStateAndExceptionPreservePartialEffectAndNeverRetryAnotherFace() {
        for (boolean throwsNative : new boolean[]{false, true}) {
            var w = world();
            w.wrongState = !throwsNative;
            w.throwAfter = throwsNative;
            var result = finish(new BuildWork(target(3), w, 0, 12, () -> 0));
            assertFalse(result.ok());
            assertEquals(1, w.actions);
            assertEquals(1, result.data().get("consumed_count").getAsInt());
            assertTrue(result.data().get("changed").getAsBoolean());
            var effect = result.data().getAsJsonArray("placement_results").get(0).getAsJsonObject().getAsJsonObject("effect");
            assertEquals(1, effect.get("affected_count").getAsInt());
            assertEquals(throwsNative, effect.get("effect_unknown").getAsBoolean());
            assertFalse(result.data().get("goal_satisfied").getAsBoolean());
        }
    }

    @Test void protectedUnknownOutsideAndUnsupportedCellsNeverTurnIntoSkippedSuccess() {
        for (String reason : java.util.List.of("protected", "unknown", "outside", "far")) {
            var w = world();
            var pos = new BlockPos(0, 90, 0);
            switch (reason) {
                case "protected" -> w.protectedCells.add(pos);
                case "unknown" -> w.unknown.add(pos);
                case "outside" -> w.outside.add(pos);
                default -> w.far.add(pos);
            }
            var result = finish(new BuildWork(target(1), w, 0, 12, () -> 0));
            assertFalse(result.ok(), reason);
            assertFalse(result.data().get("goal_satisfied").getAsBoolean());
            assertEquals(0, w.actions);
        }
    }

    @Test void workTimeExhaustionStopsBeforeNativeActionAndDoesNotReopenAfterResume() {
        var w = world();
        var time = new AtomicLong();
        w.prepare = (p, f) -> { time.addAndGet(BuildSurvey.MAX_NANOS); return null; };
        var work = new BuildWork(target(3), w, 0, 12, time::get);
        var result = finish(work);
        assertFalse(result.ok());
        assertTrue(result.feedback().startsWith("ACTION_LIMIT:"));
        assertEquals(0, w.actions);
        assertNull(work.onResume(null));
        finish(work);
        assertEquals(0, w.actions);
    }

    @Test void worldChangesCannotBuyUnboundedAdvisoryPassesWithoutAnyActualAction() {
        var w = world();
        var pos = new BlockPos(0, 90, 0);
        // Change to satisfied only at reservation, and remove it at the next observation.
        w.beforeReserve = () -> w.blocks.put(pos, Blocks.STONE.defaultBlockState());
        w.prepare = (p, f) -> null;
        var work = new BuildWork(target(1), w, 0, 12, () -> 0);
        for (int i = 0; i < 12; i++) {
            w.blocks.clear();
            assertInstanceOf(TickTask.Progress.Running.class, work.tickSafe(null));
        }
        var result = finish(work);
        assertTrue(result.feedback().startsWith("ACTION_LIMIT:"));
        assertEquals(12, result.data().get("work_surveys").getAsInt());
        assertTrue(result.data().get("work_preflights").getAsInt() <= BuildWork.MAX_PREFLIGHTS);
        assertEquals(0, w.actions);
    }
}
