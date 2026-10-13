package com.neko.mcbot.server;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkPositionsTest {
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
}
