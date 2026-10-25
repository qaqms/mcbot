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
        final List<BlockPos> covers = new ArrayList<>(List.of(COVER));
        boolean safe = true, hand = true, reserved = true, reported = true, correctTarget = true;
        float gain = 1;
        boolean emitDrops;
        boolean selectTools;
        java.util.function.Function<BlockPos,ServerTool.Result> siteGuard = p -> null;
        final Map<BlockPos, BlockActionFixtures.Mining> actions = new HashMap<>();
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
                    covers.stream().filter(cells::containsKey).toList());
        }
        @Override public BlockState state(BlockPos pos) { return cells.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        @Override public boolean safe(BlockPos pos) { return safe; }
        @Override public boolean reserve(Map<String, ActionPermissions.Change> changes) { return reserved; }
        @Override public boolean sameHand() { return hand; }
        @Override public void selectedTool() { hand = true; }
        @Override public ServerTool.Result targetGuard() {
            var refused = siteGuard.apply(TARGET);
            if (refused != null) return refused;
            var nativeAction = new BlockActionFixtures.Mining();
            nativeAction.state = state(TARGET);
            nativeAction.correct = correctTarget;
            nativeAction.sightFailure = new ServerTool.Result(false, "TARGET_BLOCKED:covered target", null);
            return new BlockMining(nativeAction).preflight(5.5);
        }
        @Override public BlockMining mining(BlockPos pos) {
            var nativeAction = new BlockActionFixtures.Mining() {
                @Override public ServerTool.Result guard(double reach) {
                    var refused = siteGuard.apply(pos);
                    return refused == null ? super.guard(reach) : refused;
                }
                private com.google.gson.JsonObject selection;
                @Override public ServerTool.Result selectTool(BlockState state) {
                    if (!selectTools) return null;
                    hand = false;
                    selection = new com.google.gson.JsonObject();
                    selection.addProperty("changed", true);
                    selection.add("held", com.neko.mcbot.server.tools.InventoryTool.stackData(held()));
                    return null;
                }
                @Override public com.google.gson.JsonObject toolSelection() { return selection; }
            };
            nativeAction.state = state(pos);
            nativeAction.gain = gain;
            nativeAction.reported = reported;
            nativeAction.dropRole = pos.equals(TARGET) ? "target" : "clearing";
            nativeAction.dropSource = pos;
            actions.put(pos, nativeAction);
            nativeAction.action = () -> {
                cells.remove(pos);
                removed.add(pos);
                nativeAction.state = Blocks.AIR.defaultBlockState();
                if (emitDrops) nativeAction.drops.add(new BlockActionFixtures.Drop(
                        new net.minecraft.world.item.ItemStack(pos.equals(TARGET)
                                ? net.minecraft.world.item.Items.COBBLESTONE : net.minecraft.world.item.Items.LEAF_LITTER)));
            };
            return new BlockMining(nativeAction);
        }
    }

    @Test void flowerBedAndFoundationClearingShareTheOriginalSiteGuardsAndKeepPartialEffectsOnCancel() {
        for (int scenario = 0; scenario < 3; scenario++) {
            var f = new Fixture(true);
            f.cells.put(TARGET, Blocks.DIRT.defaultBlockState());
            f.cells.put(COVER, Blocks.WILDFLOWERS.defaultBlockState());
            var target = com.neko.mcbot.building.BuildTarget.parse(com.google.gson.JsonParser.parseString("""
                    {"anchor":{"x":2,"y":0,"z":0},"cells":[
                    {"x":0,"y":0,"z":0,"block":"oak_planks"},{"x":0,"y":1,"z":0,"block":"air"}]}
                    """).getAsJsonObject());
            var access = new com.neko.mcbot.building.BuildSite.Access() {
                @Override public BlockState state(BlockPos p) { return f.state(p); }
                @Override public boolean corridor(BlockPos p) { return true; }
                @Override public boolean facility(BlockPos p) { return WorksiteProtection.facility(state(p),state(p).hasBlockEntity()); }
                @Override public boolean occupied(BlockPos p) { return false; }
                @Override public boolean material(com.neko.mcbot.building.BuildTarget.Cell c) { return true; }
                @Override public Object chunk(BlockPos p) { return f; }
                @Override public com.neko.mcbot.building.CellWitness watch(BlockPos p) { return null; }
            };
            var site = new com.neko.mcbot.building.BuildSite();
            site.bind(f, f);
            assertNull(site.begin(target,List.of(),new BlockPos(-1,0,0),access));
            f.siteGuard = p -> site.miningGuard(p,target.revision(),false,access);
            if (scenario == 2) f.cells.put(TARGET.east(),Blocks.TORCH.defaultBlockState());
            var work = new ClearingMining(f,TARGET,"minecraft:dirt",f.permissions,f.context,null,
                    BlockAim.DEFAULT_CLEAR_LIMIT);
            if (scenario == 2) {
                assertFalse(work.prepare().ok());
                assertTrue(f.removed.isEmpty(), "a real attachment cannot be waived as soft cover");
                continue;
            }
            assertNull(work.prepare());
            assertNull(work.tick());
            var result = scenario == 1 ? work.interrupted("CANCELLED:owner") : work.tick();
            assertEquals(1,result.data().get("cleared_blocks").getAsInt());
            assertEquals(scenario == 0,result.data().get("removed").getAsBoolean());
            assertEquals(scenario == 0 ? List.of(COVER,TARGET) : List.of(COVER),f.removed);
            assertEquals(target.revision(),site.target().revision());
        }
    }

    @Test void plannedToolSelectionContinuesAcrossTicksButExternalHandChangesStillStopClearing() {
        for (boolean external : new boolean[]{false, true}) {
            var f = new Fixture(true);
            f.selectTools = true;
            f.gain = .25f;
            var work = f.sequence(null, 3);
            assertNull(work.tick());
            assertTrue(f.hand, "the sequence must accept its own validated selection");
            if (external) {
                f.hand = false;
                assertTrue(work.tick().feedback().startsWith("WRONG_TOOL:"));
                assertTrue(f.removed.isEmpty());
            } else {
                ServerTool.Result result = null;
                for (int tick = 0; tick < 8 && result == null; tick++) result = work.tick();
                assertNotNull(result);
                assertTrue(result.ok(), result.feedback());
                assertEquals(List.of(COVER, TARGET), f.removed);
                assertTrue(result.data().has("tool_selection"));
                assertTrue(result.data().getAsJsonArray("clearing_results").get(0).getAsJsonObject()
                        .getAsJsonObject("result").has("tool_selection"));
            }
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

    @Test void concretePlanCanClearMoreThanThreeCellsWhileExplicitLimitsAndNewBlockersStillStop() {
        var f = new Fixture(true);
        for (int y = 2; y <= 5; y++) {
            var pos = TARGET.above(y);
            f.covers.add(pos);
            f.cells.put(pos, Blocks.DIRT.defaultBlockState());
        }
        assertTrue(f.sequence(null, 3).prepare().feedback().startsWith("BLOCK_LIMIT:"));
        assertTrue(f.removed.isEmpty());
        var sequence = f.sequence(null, BlockAim.DEFAULT_CLEAR_LIMIT);
        assertNull(sequence.prepare());
        for (int i = 0; i < 5; i++) assertNull(sequence.tick());
        var result = sequence.tick();
        assertTrue(result.ok(), result.feedback());
        assertEquals(5, result.data().get("cleared_blocks").getAsInt());
        assertEquals(6, f.removed.size());
    }

    @Test void clearingAndTargetOriginsStaySeparateAndCancellationRefreshesCompletedClearing() {
        for (boolean cancelled : new boolean[]{true, false}) {
            var f = new Fixture(true);
            f.emitDrops = true;
            var sequence = f.sequence(null, 3);
            assertNull(sequence.prepare());
            assertNull(sequence.tick());
            var clearing = f.actions.get(COVER);
            var id = clearing.drops.getFirst().id;
            clearing.ledger.unavailable(id, "unloaded");
            ServerTool.Result result;
            if (cancelled) result = sequence.interrupted("CANCELLED:owner");
            else result = sequence.refreshDrops(sequence.tick());
            var clear = result.data().getAsJsonArray("clearing_results").get(0).getAsJsonObject()
                    .getAsJsonObject("result").getAsJsonObject("drop_accounting");
            assertEquals("clearing", clear.get("role").getAsString());
            assertEquals(1, clear.get("unresolved_count").getAsInt());
            assertEquals(1, result.data().getAsJsonObject("clearing_drop_summary")
                    .get("unresolved_drop_items").getAsInt());
            if (cancelled) {
                assertFalse(result.ok());
                assertEquals(List.of(COVER), f.removed);
                assertFalse(result.data().get("removed").getAsBoolean());
            } else {
                assertTrue(result.ok());
                assertEquals("target", result.data().getAsJsonObject("drop_accounting").get("role").getAsString());
                assertEquals("minecraft:cobblestone", result.data().getAsJsonArray("drop_spawns").get(0)
                        .getAsJsonObject().get("item").getAsString());
            }
        }
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
