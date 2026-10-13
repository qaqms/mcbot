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
}
