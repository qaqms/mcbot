package com.neko.mcbot.server;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ExplorationPointsTest {
    @org.junit.jupiter.api.BeforeAll static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }
    @Test void loadedLandingSamplesIncludeLowerTerrainAndStayBounded() {
        var checks = new AtomicInteger();
        var points = ExplorationPoints.candidates(new BlockPos(0, 80, 0), pos -> {
            checks.incrementAndGet();
            return pos.getY() == 75 && pos.getX() >= 0;
        });
        assertEquals(5, points.size());
        assertTrue(checks.get() <= ExplorationPoints.MAX_CHECKS);
        for (var entry : points) assertEquals(75, entry.getAsJsonObject().get("y").getAsInt());
    }
    @Test void noLoadedSafeGroundDoesNotInventCoordinates() {
        var checks = new AtomicInteger();
        assertTrue(ExplorationPoints.candidates(BlockPos.ZERO, pos -> {
            checks.incrementAndGet(); return false;
        }).isEmpty());
        assertEquals(200, checks.get());
    }

    @Test void flatGroundPrefersLongerHopsAndFallsBackToNearbyGroundWithinTheSameProbeBudget() {
        var far = ExplorationPoints.candidates(new BlockPos(0, 80, 0), pos -> pos.getY() == 80);
        assertEquals(8, far.size());
        assertEquals(16, far.get(0).getAsJsonObject().get("x").getAsInt());
        var checks = new AtomicInteger();
        var near = ExplorationPoints.candidates(new BlockPos(0, 80, 0), pos -> {
            checks.incrementAndGet();
            return Math.abs(pos.getX()) <= 8 && Math.abs(pos.getZ()) <= 8 && pos.getY() == 80;
        });
        assertEquals(8, near.size());
        assertEquals(8, near.get(0).getAsJsonObject().get("x").getAsInt());
        assertTrue(checks.get() <= ExplorationPoints.MAX_CHECKS);
    }

    @Test void smallSearchRadiusRequestsOverlappingViewsInsteadOfSixteenBlockGaps() {
        var points = ExplorationPoints.candidates(new BlockPos(0,80,0), pos -> pos.getY()==80, 8);
        assertEquals(8, points.get(0).getAsJsonObject().get("x").getAsInt());
        for (var value : points) {
            var p=value.getAsJsonObject();
            assertTrue(p.get("x").getAsInt()*p.get("x").getAsInt()
                    +p.get("z").getAsInt()*p.get("z").getAsInt() <= 81);
        }
    }
}
