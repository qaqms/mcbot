package com.neko.mcbot.server;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkPositionsTest {
    @BeforeAll static void registriesOnly() { BlockActionFixtures.bootstrap(); }
    @Test void onlyObservedStandableCandidatesAreReturnedNearestFirstWithACap() {
        var target = new BlockPos(1, 90, 0);
        var points = WorkPositions.candidates(target, new WorkPositions.Access() {
            @Override public boolean standable(BlockPos pos) { return pos.getY() == 90 && !pos.equals(target); }
            @Override public double distanceSquared(BlockPos pos) {
                return pos.getX() * pos.getX() + pos.getZ() * pos.getZ();
            }
        });
        assertEquals(6, points.size());
        assertEquals(new BlockPos(0, 90, 0), points.getFirst());
        assertFalse(points.contains(target));
        assertTrue(points.stream().allMatch(p -> p.getY() == 90));
    }

    @Test void miningRejectsTheNearestBlockedStanceUsingTheActualActionRayCheck() {
        var grid = new BlockAimTest.Grid();
        grid.wall();
        var blocked = new BlockPos(1, 0, -1);
        var exposed = new BlockPos(3, 0, 0);
        var originalEye = grid.eye();
        var access = new WorkPositions.Access() {
            @Override public boolean standable(BlockPos pos) { return pos.equals(blocked) || pos.equals(exposed); }
            @Override public double distanceSquared(BlockPos pos) { return pos.equals(blocked) ? 0 : 1; }
            @Override public boolean workable(BlockPos pos) {
                return BlockAim.mining(BlockAim.fromEye(grid,
                        new Vec3(pos.getX() + .5, pos.getY() + 1.62, pos.getZ() + .5)),
                        BlockAimTest.TARGET, 4.5).ok();
            }
        };
        assertEquals(java.util.List.of(exposed), WorkPositions.miningCandidates(BlockAimTest.TARGET, access));
        assertEquals(originalEye, grid.eye(), "planning must not move the real eyes");
        assertTrue(WorkPositions.candidates(BlockAimTest.TARGET, access).contains(blocked),
                "generic standing candidates retain their separate collision-only contract");
    }

    @Test void elevatedSurfaceStancesAreIncludedAndOnlyWorkableCandidatesConsumeTheResultCap() {
        var elevated = new BlockPos(0, 92, 0);
        var points = WorkPositions.miningCandidates(new BlockPos(0, 90, 0), new WorkPositions.Access() {
            @Override public boolean standable(BlockPos pos) { return pos.getY() >= 90; }
            @Override public double distanceSquared(BlockPos pos) { return pos.distSqr(new BlockPos(0, 90, 0)); }
            @Override public boolean workable(BlockPos pos) { return pos.equals(elevated); }
        });
        assertEquals(java.util.List.of(elevated), points);
    }

    @Test void allBlockedStancesHaveABoundedNumberOfVisibilityEvaluations() {
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        var points = WorkPositions.miningCandidates(BlockPos.ZERO, new WorkPositions.Access() {
            @Override public boolean standable(BlockPos pos) { return true; }
            @Override public double distanceSquared(BlockPos pos) { return 0; }
            @Override public boolean workable(BlockPos pos) { checks.incrementAndGet(); return false; }
        });
        assertTrue(points.isEmpty());
        assertEquals(WorkPositions.MAX_MINING_CHECKS, checks.get());
    }

    @Test void pickupCandidatesMustActuallyIntersectTheNativeTouchBoxBeforeTakingResultSlots() {
        var item = new net.minecraft.world.phys.AABB(2.8, 90, .3, 3.05, 90.25, .55);
        var points = WorkPositions.pickupCandidates(new BlockPos(2, 90, 0), new WorkPositions.Access() {
            @Override public boolean standable(BlockPos pos) { return pos.getY() == 90; }
            @Override public double distanceSquared(BlockPos pos) { return pos.getX(); }
            @Override public boolean workable(BlockPos pos) {
                var body = new net.minecraft.world.phys.AABB(pos.getX() + .2, pos.getY(), pos.getZ() + .2,
                        pos.getX() + .8, pos.getY() + 1.8, pos.getZ() + .8);
                return GroundItems.touches(body, item);
            }
        });
        assertFalse(points.isEmpty());
        assertTrue(points.stream().allMatch(p -> p.getX() >= 2),
                "the nearest safe column can still be too far away to pick the item up");
    }
}
