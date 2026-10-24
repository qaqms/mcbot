package com.neko.mcbot.path;

import com.neko.mcbot.server.StandingGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SprintNavigationTest {
    @org.junit.jupiter.api.BeforeAll static void registries() {
        com.neko.mcbot.server.BlockActionFixtures.bootstrap();
    }

    @Test void nativeFoodThresholdAndSprintModifierMatchTheMovementPolicy() throws Exception {
        var food = new net.minecraft.world.food.FoodData();
        food.setFoodLevel(6);
        assertFalse(food.hasEnoughFood());
        food.setFoodLevel(7);
        assertTrue(food.hasEnoughFood());
        var field = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("SPEED_MODIFIER_SPRINTING");
        field.setAccessible(true);
        var modifier = (net.minecraft.world.entity.ai.attributes.AttributeModifier) field.get(null);
        assertEquals(.3, modifier.amount(), 1e-6);
        assertEquals(net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                modifier.operation());
    }

    private static final class Terrain implements DigSampler, StandingGeometry.Access {
        BlockPos missing, unknown, wall;
        boolean thin;
        @Override public boolean known(BlockPos pos) { return !pos.equals(unknown); }
        @Override public List<AABB> boxes(BlockPos pos) {
            assertTrue(known(pos), "unknown cells are never read");
            if (pos.equals(missing)) return List.of();
            if (pos.equals(wall) || pos.getY() == -1)
                return List.of(thin && pos.getY() == -1
                        ? new AABB(.35, 0, .35, .65, 1, .65) : new AABB(0, 0, 0, 1, 1, 1));
            return List.of();
        }
        @Override public boolean inBounds(int x, int y, int z) {
            return known(new BlockPos(x, y, z)) && Math.abs(x) < 20 && Math.abs(z) < 20 && y >= -1 && y < 4;
        }
        @Override public boolean passable(int x, int y, int z) {
            return y != -1 && !new BlockPos(x, y, z).equals(wall);
        }
        @Override public boolean support(int x, int y, int z) {
            return y == -1 && !new BlockPos(x, y, z).equals(missing);
        }
        @Override public double digSeconds(int x, int y, int z) { return INFEASIBLE; }
        @Override public boolean placeable(int x, int y, int z) { return false; }
        @Override public double placeCost(int x, int y, int z) { return INFEASIBLE; }
        @Override public int maxPlaces() { return 0; }
        @Override public StandingGeometry geometry() { return new StandingGeometry(this, .6, 1.8, .6); }
    }

    private static List<DigAStar.Step> line(int dx, int dz) {
        var result = new ArrayList<DigAStar.Step>();
        for (int i = 0; i <= 6; i++) result.add(new DigAStar.Step(dx * i, 0, dz * i,
                List.of(), List.of(), i == 0 ? DigAStar.ACT_START : DigAStar.ACT_WALK, 0, false));
        return result;
    }

    @Test void clearStraightRoutesRunInBothDirectionsAndDiagonallyButReleaseBeforeTheEndpoint() {
        var terrain = new Terrain();
        for (int[] direction : List.of(new int[]{1, 0}, new int[]{-1, 0}, new int[]{0, 1},
                new int[]{0, -1}, new int[]{1, 1}, new int[]{-1, -1})) {
            var plan = line(direction[0], direction[1]);
            for (int i = 1; i <= 4; i++) assertTrue(PathTask.sprintable(plan, i, terrain));
            assertFalse(PathTask.sprintable(plan, 0, terrain));
            assertFalse(PathTask.sprintable(plan, 5, terrain));
            assertFalse(PathTask.sprintable(plan, 6, terrain));
        }
    }

    @Test void bendsEditsJumpsAndSlopesExitRunningWithTwoStepsOfLookahead() {
        var terrain = new Terrain();
        for (var changed : List.of(
                new DigAStar.Step(3, 0, 1, List.of(), List.of(), DigAStar.ACT_WALK, 0, false),
                new DigAStar.Step(3, 0, 0, List.of(12L), List.of(), DigAStar.ACT_WALK, 0, false),
                new DigAStar.Step(3, 0, 0, List.of(), List.of(12L), DigAStar.ACT_WALK, 0, false),
                new DigAStar.Step(3, 1, 0, List.of(), List.of(), DigAStar.ACT_WALK, 1, true),
                new DigAStar.Step(3, -1, 0, List.of(), List.of(), DigAStar.ACT_FALL, -1, false))) {
            var plan = line(1, 0);
            plan.set(3, changed);
            assertFalse(PathTask.sprintable(plan, 1, terrain), changed.toString());
        }
    }

    @Test void changedOrUnknownSupportAndNarrowFootingRejectRunningWithoutInventingEdits() {
        var terrain = new Terrain();
        var plan = line(1, 0);
        assertTrue(PathTask.sprintable(plan, 1, terrain));
        terrain.missing = new BlockPos(3, -1, 0);
        assertFalse(PathTask.sprintable(plan, 1, terrain));
        terrain.missing = null;
        terrain.unknown = new BlockPos(3, -1, 0);
        assertFalse(PathTask.sprintable(plan, 1, terrain));
        terrain.unknown = null;
        terrain.wall = new BlockPos(2, 1, 0);
        assertFalse(PathTask.sprintable(plan, 1, terrain));
        terrain.wall = null;
        terrain.thin = true;
        assertNotNull(terrain.geometry().standing(0, 0, 0, TerrainEdits.EMPTY));
        assertFalse(PathTask.sprintable(plan, 1, terrain), "standing on a narrow support is not a running lane");
    }

    @Test void elevatedSpeedOrMomentumMustFitTheValidatedBrakingWindow() {
        assertTrue(PathTask.sprintSpeedFits(.1, false, 0, 0));
        assertTrue(PathTask.sprintSpeedFits(.13, true, .16, 0));
        assertFalse(PathTask.sprintSpeedFits(.5, false, 0, 0));
        assertFalse(PathTask.sprintSpeedFits(.1, false, .6, 0));
        assertFalse(PathTask.sprintSpeedFits(Double.NaN, false, 0, 0));
        assertFalse(PathTask.sprintSpeedFits(.1, false, Double.POSITIVE_INFINITY, 0));
    }

    @Test void lateralMomentumMustRemainInsideTheCheckedCorridorAndBrakingCannotRequestSprint() {
        var plan = line(1, 0);
        var from = plan.getFirst();
        var to = plan.get(1);
        assertTrue(PathTask.runningAligned(new PathSteering.Body(.7, 0, .5, .15, 0, true), 1, 0, from, to));
        assertFalse(PathTask.runningAligned(new PathSteering.Body(.7, 0, .62, .15, 0, true), 1, 0, from, to));
        assertFalse(PathTask.runningAligned(new PathSteering.Body(.7, 0, .5, .15, .1, true), 1, 0, from, to));
        assertFalse(PathTask.runningAligned(new PathSteering.Body(.7, 0, .5, .15, 0, true), -.5, 0, from, to));
        assertFalse(PathTask.runningAligned(new PathSteering.Body(.7, .5, .5, .15, 0, true), 1, 0, from, to));
    }
}
