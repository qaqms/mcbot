package com.neko.mcbot.server;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ClearingMiningTest {
    private static final BlockPos TARGET = new BlockPos(2, 0, 0);
    private static final BlockPos COVER = TARGET.above();
    @BeforeAll static void bootstrap() { BlockActionFixtures.bootstrap(); }

    private static final class Fixture implements ClearingMining.Access {
        final ActionPermissions permissions = new ActionPermissions();
        final ActionPermissions.Context context;
        final Map<BlockPos, BlockState> cells = new HashMap<>();
        final List<BlockPos> removed = new ArrayList<>();
        boolean safe = true, hand = true, reserved = true, reported = true, correctTarget = true;
        float gain = 1;
        Fixture(boolean autonomous) {
            context = new ActionPermissions.Context(UUID.randomUUID(), UUID.randomUUID(), "world", 1,
                    false, autonomous);
            permissions.begin(context);
            cells.put(TARGET, Blocks.STONE.defaultBlockState());
            cells.put(COVER, Blocks.LEAF_LITTER.defaultBlockState());
        }
        ClearingMining sequence(ActionPermissions.Scope approved, int limit) {
            return new ClearingMining(this, TARGET, "minecraft:stone", permissions, context, approved, limit);
        }
        @Override public BlockAim.MiningPlan plan() {
            return new BlockAim.MiningPlan(new BlockAim.Aim(Vec3.atCenterOf(TARGET), null),
                    cells.containsKey(COVER) ? List.of(COVER) : List.of());
        }
        @Override public BlockState state(BlockPos pos) { return cells.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        @Override public boolean safe(BlockPos pos) { return safe; }
        @Override public boolean reserve(Map<String, ActionPermissions.Change> changes) { return reserved; }
        @Override public boolean sameHand() { return hand; }
        @Override public ServerTool.Result targetGuard() {
            var nativeAction = new BlockActionFixtures.Mining();
            nativeAction.state = state(TARGET);
            nativeAction.correct = correctTarget;
            nativeAction.sightFailure = new ServerTool.Result(false, "TARGET_BLOCKED:covered target", null);
            return new BlockMining(nativeAction).preflight(5.5);
        }
        @Override public BlockMining mining(BlockPos pos) {
            var nativeAction = new BlockActionFixtures.Mining();
            nativeAction.state = state(pos);
            nativeAction.gain = gain;
            nativeAction.reported = reported;
            nativeAction.action = () -> {
                cells.remove(pos);
                removed.add(pos);
                nativeAction.state = Blocks.AIR.defaultBlockState();
            };
            return new BlockMining(nativeAction);
        }
    }

    @Test void autonomousSequenceClearsThenMinesWithSeparateActualCounters() {
        var f = new Fixture(true);
        var sequence = f.sequence(null, 3);
        assertNull(sequence.prepare());
        assertNull(sequence.tick());
        var result = sequence.tick();
        assertTrue(result.ok());
        assertEquals(List.of(COVER, TARGET), f.removed);
        assertTrue(result.data().get("removed").getAsBoolean());
        assertEquals(1, result.data().get("cleared_blocks").getAsInt());
        assertEquals(1, result.data().getAsJsonArray("clearing_results").size());
    }

    @Test void confirmModeUsesTheExistingOneUseConcreteScope() {
        var f = new Fixture(false);
        var proposal = f.sequence(null, 3).prepare();
        assertTrue(proposal.feedback().startsWith("NEED_CONFIRM:"));
        assertTrue(f.removed.isEmpty());
        String id = proposal.data().get("authorization_id").getAsString();
        assertTrue(f.permissions.approve(f.context, id));
        var scope = f.permissions.take(f.context, id, TARGET.asLong());
        assertNotNull(scope);
        assertEquals(2, scope.changes().size());
        assertNull(f.permissions.take(f.context, id, TARGET.asLong()));
        var sequence = f.sequence(scope, 3);
        assertNull(sequence.prepare());
        assertNull(sequence.tick());
        assertTrue(sequence.tick().ok());
    }

    @Test void changedApprovedStateRequiresNewConsentBeforeAnyRemoval() {
        var f = new Fixture(false);
        String id = f.sequence(null, 3).prepare().data().get("authorization_id").getAsString();
        assertTrue(f.permissions.approve(f.context, id));
        var approved = f.permissions.take(f.context, id, TARGET.asLong());
        f.cells.put(COVER, Blocks.DIRT.defaultBlockState());
        var result = f.sequence(approved, 3).prepare();
        assertTrue(result.feedback().startsWith("NEED_CONFIRM:"));
        assertTrue(f.removed.isEmpty());
    }

    @Test void replacementOrNewBlockerStopsAndPreservesCompletedClearing() {
        var f = new Fixture(true);
        var sequence = f.sequence(null, 3);
        assertNull(sequence.prepare());
        assertNull(sequence.tick());
        f.cells.put(COVER, Blocks.DIRT.defaultBlockState());
        var result = sequence.tick();
        assertFalse(result.ok());
        assertEquals(1, result.data().get("cleared_blocks").getAsInt());
        assertEquals(List.of(COVER), f.removed);
        f = new Fixture(true);
        sequence = f.sequence(null, 3);
        assertNull(sequence.prepare());
        f.cells.put(TARGET, Blocks.DIAMOND_BLOCK.defaultBlockState());
        assertTrue(sequence.tick().feedback().startsWith("TARGET_LOST:"));
        assertTrue(f.removed.isEmpty());
    }

    @Test void remainingBudgetAndRegionConflictNeverStartMining() {
        var f = new Fixture(true);
        assertTrue(f.sequence(null, 0).prepare().feedback().startsWith("BLOCK_LIMIT:"));
        assertTrue(f.removed.isEmpty());
        f.reserved = false;
        assertTrue(f.sequence(null, 3).prepare().feedback().startsWith("BUSY:"));
        assertTrue(f.removed.isEmpty());
    }

    @Test void liveSafetyHandAndRevokedPermissionStopBeforeAnEffect() {
        for (int mode = 0; mode < 3; mode++) {
            var f = new Fixture(true);
            var sequence = f.sequence(null, 3);
            assertNull(sequence.prepare());
            if (mode == 0) f.safe = false;
            if (mode == 1) f.hand = false;
            if (mode == 2) f.permissions.end(f.context.owner(), 1);
            assertFalse(sequence.tick().ok());
            assertTrue(f.removed.isEmpty());
        }
    }

    @Test void cancellationAndNativeFailureKeepActualClearsButNeverClaimTargetRemoval() {
        var f = new Fixture(true);
        var sequence = f.sequence(null, 3);
        assertNull(sequence.tick());
        var result = sequence.interrupted("CANCELLED:owner");
        assertEquals(1, result.data().get("cleared_blocks").getAsInt());
        assertFalse(result.data().get("removed").getAsBoolean());
        assertFalse(sequence.tick().ok());
        assertEquals(List.of(COVER), f.removed);
        f = new Fixture(true);
        f.reported = false;
        result = f.sequence(null, 3).tick();
        assertFalse(result.ok());
        assertEquals(1, result.data().get("cleared_blocks").getAsInt());
        assertFalse(result.data().get("removed").getAsBoolean());
    }

    @Test void cancellationDuringTheReceiptDelayPreservesActualTargetRemoval() {
        var f = new Fixture(true);
        var sequence = f.sequence(null, 3);
        assertNull(sequence.tick());
        assertTrue(sequence.tick().ok());
        var cancelled = sequence.interrupted("CANCELLED:receipt delay");
        assertFalse(cancelled.ok());
        assertTrue(cancelled.data().get("removed").getAsBoolean());
        assertEquals(1, cancelled.data().get("cleared_blocks").getAsInt());
        assertEquals(List.of(COVER, TARGET), f.removed);
    }

    @Test void wrongTargetToolFailsBeforeRemovingAnyCover() {
        var f = new Fixture(true);
        f.correctTarget = false;
        var result = f.sequence(null, 3).prepare();
        assertTrue(result.feedback().startsWith("WRONG_TOOL:"));
        assertTrue(f.removed.isEmpty());
        assertTrue(f.cells.containsKey(COVER));
    }
}
