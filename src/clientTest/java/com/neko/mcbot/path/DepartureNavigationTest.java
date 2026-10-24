package com.neko.mcbot.path;

import com.neko.mcbot.server.StandingGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DepartureNavigationTest {
    private static final class Ledge implements DigSampler, StandingGeometry.Access {
        @Override public boolean known(BlockPos pos) { return true; }
        @Override public List<AABB> boxes(BlockPos pos) {
            return support(pos.getX(), pos.getY(), pos.getZ())
                    ? List.of(new AABB(0, 0, 0, 1, 1, 1)) : List.of();
        }
        @Override public boolean inBounds(int x, int y, int z) {
            return x >= 60 && x <= 68 && y >= 62 && y <= 70 && z >= 140 && z <= 146;
        }
        @Override public boolean passable(int x, int y, int z) { return !support(x, y, z); }
        @Override public boolean support(int x, int y, int z) { return x >= 63 && x <= 66 && y == 64 && z == 143; }
        @Override public double digSeconds(int x, int y, int z) { return INFEASIBLE; }
        @Override public boolean placeable(int x, int y, int z) { return false; }
        @Override public double placeCost(int x, int y, int z) { return INFEASIBLE; }
        @Override public int maxPlaces() { return 0; }
        @Override public StandingGeometry geometry() { return new StandingGeometry(this, .6, 1.8, .6); }
    }

    @Test void edgeSupportedDepartureRecoversBothWalkOnlyAndOrdinaryNavigationWithoutEdits() {
        var terrain = new Ledge();
        var oldStart = new BlockPos(62, 65, 143);
        var target = new BlockPos(66, 65, 143);
        for (boolean walkOnly : new boolean[]{true, false}) {
            var before = PathTask.searchFor(terrain, oldStart, target, true, walkOnly);
            while (!before.advance(300)) { }
            assertNotNull(before.failure());
            assertEquals(1, before.expanded());
            assertTrue(before.rejections().containsKey("DEPARTURE_FOOTING"));
            var actualOrigin = terrain.geometry().departure(62.98, 65, 143.5, p -> true);
            assertEquals(new BlockPos(63, 65, 143), actualOrigin);
            var after = PathTask.searchFor(terrain, actualOrigin, target, true, walkOnly);
            while (!after.advance(300)) { }
            assertNull(after.failure(), after.failure());
            var live = PathTask.liveSteps(after.path(), 65, terrain);
            assertTrue(PathTask.startsAt(live, actualOrigin));
            assertTrue(PathTask.standingStart(live.getFirst(), terrain));
            assertTrue(PathTask.editsFeasible(live, terrain));
            assertTrue(live.stream().allMatch(step -> step.dig().isEmpty() && step.place().isEmpty()));
        }
    }

    @Test void cancellationReleasesAlignmentWithoutResettingTheOriginalNavigationAccount() throws Exception {
        var budget = new NavigationBudget();
        var task = PathTask.approach(null, new BlockPos(66, 65, 143), null, null, null,
                PathSpec.DEFAULT, NavigationGoal.position(66, 65, 143, 0), budget);
        for (String stop : List.of("onSuspend", "onAbort", "onFinish")) {
            var destination = PathTask.class.getDeclaredField("departure");
            destination.setAccessible(true);
            destination.set(task, new BlockPos(63, 65, 143));
            var steering = PathTask.class.getDeclaredField("departureSteering");
            steering.setAccessible(true);
            steering.set(task, new PathSteering(63, 65, 143, false));
            PathTask.class.getMethod(stop).invoke(task);
            assertNull(destination.get(task));
            assertNull(steering.get(task));
            var shared = PathTask.class.getDeclaredField("sharedBudget");
            shared.setAccessible(true);
            assertSame(budget, shared.get(task));
            assertEquals(0, budget.nodes());
            assertEquals(0, budget.replans());
        }
    }
}
