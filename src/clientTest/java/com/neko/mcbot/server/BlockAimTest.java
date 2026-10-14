package com.neko.mcbot.server;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BlockAimTest {
    static final BlockPos TARGET = new BlockPos(2, 1, 0);
    @BeforeAll static void registriesOnly() { BlockActionFixtures.bootstrap(); }

    /** Vanilla shapes and clip run against a small block map; no world or player is created. */
    static final class Grid implements BlockGetter, BlockAim.Access {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final Set<BlockPos> unknown = new HashSet<>();
        Vec3 eye = new Vec3(.5, 1.62, .5);
        List<AABB> forcedOutline;
        int traces;
        int outlines;
        int reads;

        Grid() { blocks.put(TARGET, Blocks.STONE.defaultBlockState()); }
        @Override public Vec3 eye() { return eye; }
        @Override public boolean loaded(BlockPos pos) { return !unknown.contains(pos); }
        @Override public List<AABB> outline(BlockPos pos) {
            outlines++;
            return forcedOutline == null
                    ? getBlockState(pos).getShape(this, pos, CollisionContext.empty()).toAabbs() : forcedOutline;
        }
        @Override public BlockHitResult trace(Vec3 from, Vec3 to) {
            traces++;
            return clip(new ClipContext(from, to, ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, CollisionContext.empty()));
        }
        @Override public BlockHitResult traceIgnoring(Vec3 from, Vec3 to, Set<BlockPos> ignored) {
            traces++;
            return BlockAim.clipIgnoring(this, from, to, CollisionContext.empty(), ignored);
        }
        @Override public BlockState getBlockState(BlockPos pos) {
            assertTrue(loaded(pos), "unknown blocks must never be read");
            reads++;
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public int getHeight() { return 384; }
        @Override public int getMinY() { return -64; }

        void wall() {
            for (int y = 0; y <= 3; y++) for (int z = -1; z <= 1; z++) {
                blocks.put(new BlockPos(1, y, z), Blocks.STONE.defaultBlockState());
            }
        }
    }

    @Test void clearTargetHitsNativeOutlineFromBodyEyes() {
        Grid grid = new Grid();
        var aim = BlockAim.mining(grid, TARGET, 4.5);
        assertTrue(aim.ok());
        assertEquals(TARGET, grid.trace(grid.eye, aim.point()).getBlockPos());
        assertEquals(new Vec3(2.5, 1.5, .5), aim.point());
    }

    @Test void aWallCannotBeMinedThroughEvenWhenTheTargetIsClose() {
        Grid grid = new Grid();
        grid.wall();
        var aim = BlockAim.mining(grid, TARGET, 4.5);
        assertFalse(aim.ok());
        assertTrue(aim.failure().startsWith("TARGET_BLOCKED:"));
        assertTrue(grid.traces <= 27 * 16);
        grid.blocks.remove(new BlockPos(1, 1, 0));
        assertTrue(BlockAim.mining(grid, TARGET, 4.5).ok());
    }

    @Test void slabOutlineIsUsedInsteadOfAFullBlockCenter() {
        Grid grid = new Grid();
        grid.blocks.put(TARGET, Blocks.OAK_SLAB.defaultBlockState());
        var aim = BlockAim.mining(grid, TARGET, 4.5);
        assertTrue(aim.ok());
        assertEquals(1.25, aim.point().y);
        assertEquals(TARGET, grid.trace(grid.eye, aim.point()).getBlockPos());
    }

    @Test void partialCoverCanStillExposeAnotherPointOnTheTarget() {
        Grid grid = new Grid();
        grid.blocks.put(new BlockPos(1, 1, 0), Blocks.OAK_SLAB.defaultBlockState()
                .setValue(net.minecraft.world.level.block.SlabBlock.TYPE, SlabType.TOP));
        var aim = BlockAim.mining(grid, TARGET, 4.5);
        assertTrue(aim.ok());
        assertTrue(grid.traces > 1);
        assertEquals(TARGET, grid.trace(grid.eye, aim.point()).getBlockPos());
    }

    @Test void reachIsMeasuredToTheHitSurfaceAndNotFeetOrCenter() {
        Grid grid = new Grid();
        BlockPos far = new BlockPos(5, 1, 0);
        grid.blocks.clear();
        grid.blocks.put(far, Blocks.STONE.defaultBlockState());
        assertTrue(BlockAim.mining(grid, far, 4.55).ok());
        assertTrue(BlockAim.mining(grid, far, 4).failure().startsWith("OUT_OF_REACH:"));
    }

    @Test void unloadedTargetAndIntermediateCellsNeverReachNativeClip() {
        for (BlockPos unknown : List.of(TARGET, new BlockPos(1, 1, 0))) {
            Grid grid = new Grid();
            grid.unknown.add(unknown);
            assertTrue(BlockAim.mining(grid, TARGET, 4.5).failure().startsWith("TARGET_LOST:"));
            assertEquals(0, grid.traces);
        }
    }

    @Test void aMissObjectMustNotCountAsAHitOnItsReportedBlockPosition() {
        Grid grid = new Grid();
        grid.blocks.clear();
        grid.forcedOutline = List.of(new AABB(0, 0, 0, 1, 1, 1));
        assertTrue(BlockAim.mining(grid, TARGET, 4.5).failure().startsWith("TARGET_BLOCKED:"));
        assertTrue(grid.traces > 0);
    }

    @Test void theActualLookVectorMustHitTheTargetAfterTurning() {
        Grid grid = new Grid();
        Vec3 east = grid.eye.add(Vec3.directionFromRotation(0, -90).scale(3));
        Vec3 west = grid.eye.add(Vec3.directionFromRotation(0, 90).scale(3));
        assertTrue(BlockAim.miningRay(grid, TARGET, east, 4.5).ok());
        assertTrue(BlockAim.miningRay(grid, TARGET, west, 4.5).failure().startsWith("TARGET_BLOCKED:"));
        grid.wall();
        assertTrue(BlockAim.miningRay(grid, TARGET, east, 4.5).failure().startsWith("TARGET_BLOCKED:"));
    }

    @Test void airAndInvalidReachCannotProduceAMiningAim() {
        Grid grid = new Grid();
        grid.blocks.clear();
        assertFalse(BlockAim.mining(grid, TARGET, 4.5).ok());
        assertEquals(0, grid.traces);
        grid = new Grid();
        for (double reach : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, 7}) {
            assertTrue(BlockAim.mining(grid, TARGET, reach).failure().startsWith("OUT_OF_REACH:"));
        }
        assertEquals(0, grid.outlines);
    }

    @Test void placementAllowsAClearEmptyCellButRejectsAWallInFrontOfIt() {
        Grid grid = new Grid();
        grid.blocks.clear();
        Vec3 point = Vec3.atCenterOf(TARGET).add(0, .5, 0);
        assertTrue(BlockAim.placement(grid, TARGET, point, 4.5).ok());
        grid.wall();
        assertTrue(BlockAim.placement(grid, TARGET, point, 4.5).failure().startsWith("TARGET_BLOCKED:"));
    }

    @Test void placementCannotReadUnknownRayCellsOrExceedEyeReach() {
        Grid grid = new Grid();
        Vec3 point = Vec3.atCenterOf(TARGET);
        grid.unknown.add(new BlockPos(1, 1, 0));
        assertTrue(BlockAim.placement(grid, TARGET, point, 4.5).failure().startsWith("TARGET_LOST:"));
        assertEquals(0, grid.traces);
        assertTrue(BlockAim.placement(grid, TARGET, point, 1).failure().startsWith("OUT_OF_REACH:"));
        assertEquals(0, grid.traces);
    }

    @Test void descendingClearanceOrderOpensSightBeforeMiningTheLowerCells() {
        Grid grid = new Grid();
        grid.blocks.clear();
        grid.eye = new Vec3(87.5, 67.62, -11.5);
        var cells = com.neko.mcbot.path.DigAStar.clearanceCells(66, 88, 65, -12);
        for (long cell : cells) {
            grid.blocks.put(pathPos(cell), Blocks.DIRT.defaultBlockState());
        }
        grid.blocks.put(new BlockPos(87, 65, -12), Blocks.DIRT.defaultBlockState());
        BlockPos foot = new BlockPos(88, 65, -12);
        assertTrue(BlockAim.mining(grid, foot, 4.5).failure().startsWith("TARGET_BLOCKED:"),
                "the old bottom-first order is occluded by its own planned column");
        for (long cell : cells) {
            BlockPos pos = pathPos(cell);
            assertTrue(BlockAim.mining(grid, pos, 4.5).ok(), "each planned removal must be visible: " + pos);
            grid.blocks.remove(pos);
        }
        assertEquals(Set.of(new BlockPos(87, 65, -12)), grid.blocks.keySet(),
                "departure support stays intact");
    }

    @Test void aBlockOutsideTheRouteIsNeverSilentlyIncludedInClearance() {
        Grid grid = new Grid();
        grid.blocks.clear();
        grid.eye = new Vec3(.5, 4.62, .5);
        var cells = com.neko.mcbot.path.DigAStar.clearanceCells(3, 2, 2, 0);
        for (long cell : cells) grid.blocks.put(pathPos(cell), Blocks.STONE.defaultBlockState());
        for (int y = 0; y <= 6; y++) {
            for (int z = -1; z <= 1; z++) {
                grid.blocks.put(new BlockPos(1, y, z), Blocks.STONE.defaultBlockState());
            }
        }
        assertTrue(BlockAim.mining(grid, pathPos(cells.getFirst()), 4.5).failure()
                .startsWith("TARGET_BLOCKED:"));
        assertEquals(3, cells.size());
        assertTrue(cells.stream().noneMatch(cell -> pathPos(cell).getX() == 1));
    }

    private static BlockPos pathPos(long cell) {
        return new BlockPos(com.neko.mcbot.path.DigAStar.unpackX(cell),
                com.neko.mcbot.path.DigAStar.unpackY(cell), com.neko.mcbot.path.DigAStar.unpackZ(cell));
    }

    @Test void clearingPlanUsesNativeShapeAndOrdersTheActualOccluders() {
        Grid grid = new Grid();
        grid.eye = new Vec3(.5, 1.62, .5);
        grid.wall();
        var plan = BlockAim.miningPlan(grid, TARGET, 4.5, pos -> true);
        assertTrue(plan.ok());
        assertEquals(List.of(new BlockPos(1, 1, 0)), plan.blockers());
        assertTrue(grid.traces <= BlockAim.MAX_PLAN_TRACES);
        assertTrue(grid.blocks.containsKey(plan.blockers().getFirst()), "query never edits the world");
        grid.blocks.remove(plan.blockers().getFirst());
        assertTrue(BlockAim.mining(grid, TARGET, 4.5).ok());
    }

    @Test void leafLitterAboveTheTargetCanBeClearedWithoutClickingThroughIt() {
        Grid grid = new Grid();
        grid.blocks.clear();
        BlockPos target = new BlockPos(0, 0, 0);
        grid.eye = new Vec3(.5, 2.62, .5);
        grid.blocks.put(target, Blocks.GRASS_BLOCK.defaultBlockState());
        grid.blocks.put(target.above(), Blocks.LEAF_LITTER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.SegmentableBlock.AMOUNT, 4));
        assertFalse(BlockAim.mining(grid, target, 4.5).ok());
        var plan = BlockAim.miningPlan(grid, target, 4.5, pos -> pos.equals(target.above()));
        assertTrue(plan.ok());
        assertEquals(List.of(target.above()), plan.blockers());
        assertFalse(BlockAim.miningPlan(grid, target, 4.5, pos -> false).ok());
    }

    @Test void clearingPlanRejectsTooManyBlockersUnknownCellsAndDeniedChanges() {
        Grid grid = new Grid();
        grid.blocks.clear();
        BlockPos target = new BlockPos(0, 0, 0);
        grid.eye = new Vec3(.5, 5.62, .5);
        for (int y = 0; y <= 4; y++) grid.blocks.put(target.above(y), Blocks.STONE.defaultBlockState());
        assertFalse(BlockAim.miningPlan(grid, target, 6.5, pos -> true).ok());
        grid = new Grid();
        grid.wall();
        assertFalse(BlockAim.miningPlan(grid, TARGET, 4.5, pos -> false).ok());
        grid.unknown.add(new BlockPos(1, 1, 0));
        grid.traces = 0;
        assertFalse(BlockAim.miningPlan(grid, TARGET, 4.5, pos -> true).ok());
        assertEquals(0, grid.traces);
    }

    @Test void workQueriesShareATraceBudgetAndReportUnknownWhenItRunsOut() {
        Grid grid = new Grid();
        grid.wall();
        var budget = new BlockAim.TraceBudget(3);
        assertTrue(BlockAim.miningPlan(grid, TARGET, 4.5, pos -> false, budget).aim().failure()
                .startsWith("WORK_BUDGET:"));
        assertEquals(3, grid.traces);
        assertEquals(3, budget.used());
        assertFalse(BlockAim.miningPlan(grid, TARGET, 4.5, pos -> true, budget).ok());
        assertEquals(3, grid.traces, "the next candidate cannot start another ray after the shared cap");
    }
}
