package com.neko.mcbot.body;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompanionPhysicsTest {
    /** Movement speed is supplied; native voxel collision runs without a world or player. */
    static class Body implements CompanionPhysics.Access {
        boolean active = true;
        boolean loaded = true;
        AABB checkedRegion;
        Vec3 position = new Vec3(.5, 2, .5);
        Vec3 velocity = new Vec3(0, -.2, 0);
        VoxelShape floor = Shapes.box(0, 1, 0, 1, 2, 1);
        final List<String> calls = new ArrayList<>();
        Vec3 settled;
        Vec3 recorded;
        int steps;

        @Override public boolean active() { return active; }
        @Override public AABB bounds() {
            return new AABB(position.x - .3, position.y, position.z - .3,
                    position.x + .3, position.y + 1.8, position.z + .3);
        }
        @Override public Vec3 velocity() { return velocity; }
        @Override public boolean loaded(AABB region) {
            checkedRegion = region;
            return loaded;
        }
        @Override public Vec3 position() { return position; }
        @Override public void updateFluids() { calls.add("fluids"); }
        @Override public void travel(Vec3 input) {
            assertEquals(Vec3.ZERO, input, "passive gravity must not invent walking input");
            calls.add("travel");
            steps++;
            double dy = Shapes.collide(Direction.Axis.Y, bounds(), List.of(floor), velocity.y);
            position = position.add(velocity.x, dy, velocity.z);
        }
        @Override public void applyBlockEffects() { calls.add("blocks"); }
        @Override public void checkFall(Vec3 movement) {
            calls.add("fall");
            settled = movement;
        }
        @Override public void recordMovement(Vec3 movement) {
            calls.add("record");
            recorded = movement;
        }
    }

    @Test void removingSupportStartsPassiveDescentAndNativeShapesStopAtLowerFloor() {
        Body body = new Body();
        CompanionPhysics.tick(body);
        assertEquals(2, body.position.y, 1e-9);
        body.floor = Shapes.box(0, 0, 0, 1, 1, 1);
        CompanionPhysics.tick(body);
        assertEquals(1.8, body.position.y, 1e-9);
        assertEquals(-.2, body.settled.y, 1e-9);
        for (int tick = 0; tick < 12; tick++) CompanionPhysics.tick(body);
        assertEquals(1, body.position.y, 1e-9);
        assertEquals(0, body.settled.y, 1e-9);
        assertEquals(14, body.steps);
    }

    @Test void collisionSettlesOnTheActualSlabSurfaceInsteadOfAnIntegerBlockHeight() {
        Body body = new Body();
        body.floor = Shapes.box(0, 0, 0, 1, .5, 1);
        for (int tick = 0; tick < 20; tick++) CompanionPhysics.tick(body);
        assertEquals(.5, body.position.y, 1e-9);
    }

    @Test void noFloorAllowsContinuedDescentInsteadOfSnappingToASyntheticGround() {
        Body body = new Body();
        body.floor = Shapes.empty();
        for (int tick = 0; tick < 30; tick++) CompanionPhysics.tick(body);
        assertEquals(-4, body.position.y, 1e-9);
    }

    @Test void everyStepRefreshesFluidsThenTravelsAndSettlesExactlyTheActualMovement() {
        Body body = new Body();
        body.floor = Shapes.empty();
        body.velocity = new Vec3(.06, -.25, -.04);
        CompanionPhysics.tick(body);
        assertEquals(List.of("fluids", "travel", "blocks", "fall", "record"), body.calls);
        assertEquals(body.position.subtract(new Vec3(.5, 2, .5)), body.settled);
        assertEquals(body.settled, body.recorded);
        assertEquals(1, body.steps);
    }

    @Test void unknownSweptCellsDoNotRunNativeWorldOperationsUntilLoaded() {
        Body body = new Body();
        body.loaded = false;
        body.velocity = new Vec3(2, -4, -1);
        CompanionPhysics.tick(body);
        assertEquals(body.bounds().expandTowards(body.velocity).inflate(1), body.checkedRegion);
        assertTrue(body.calls.isEmpty());
        assertEquals(new Vec3(.5, 2, .5), body.position);
        body.loaded = true;
        CompanionPhysics.tick(body);
        assertEquals(1, body.steps);
    }

    @Test void anInactiveBodySkipsPassiveTravelAndWorldReads() {
        Body body = new Body();
        body.active = false;
        CompanionPhysics.tick(body);
        assertNull(body.checkedRegion);
        assertTrue(body.calls.isEmpty());
    }

    @Test void movementFailuresPropagateWithoutReportingACompletedStep() {
        Body body = new Body() {
            @Override public void travel(Vec3 input) { throw new IllegalStateException("fixture"); }
        };
        assertThrows(IllegalStateException.class, () -> CompanionPhysics.tick(body));
        assertNull(body.settled);
        assertNull(body.recorded);
    }

    @Test void waterReflexOverridesWorkInputAndUsesNativeLiquidJumpBeforeTravel() {
        var body = new Body() {
            @Override public MovementInput.Frame takeInput() { return new MovementInput.Frame(1, 0, false); }
            @Override public MovementInput.Frame recoveryInput() { return new MovementInput.Frame(0, 0, true); }
            @Override public boolean inWater() { return true; }
            @Override public boolean grounded() { return true; }
            @Override public void jump() { fail("water movement must not use a ground jump"); }
            @Override public void swimUp() { calls.add("swim"); }
            @Override public void refreshEyes() { calls.add("eyes"); }
        };
        CompanionPhysics.tick(body);
        assertEquals(List.of("fluids", "swim", "travel", "blocks", "eyes", "fall", "record"), body.calls);
    }

    @Test void nativeEnvironmentalDeathPreventsTravelRecoveryAndSettlementInTheSameTick() {
        var body = new Body() {
            @Override public void updateFluids() { calls.add("environment"); active = false; }
            @Override public MovementInput.Frame recoveryInput() { fail("dead body cannot acquire input"); return null; }
            @Override public void unavailable() { calls.add("release"); }
        };
        CompanionPhysics.tick(body);
        assertEquals(List.of("environment", "release"), body.calls);
        assertEquals(0, body.steps);
        assertNull(body.recorded);
    }

    @Test void nativeWorldTransitionPreventsOldWorldInputAndFallAccounting() {
        var body = new Body() {
            Object world = new Object();
            @Override public Object worldIdentity() { return world; }
            @Override public void updateFluids() { world = new Object(); }
            @Override public void unavailable() { calls.add("release"); }
        };
        CompanionPhysics.tick(body);
        assertEquals(List.of("release"), body.calls);
        assertEquals(0, body.steps);
    }

    @Test void unloadedOrInactivePhysicsReleasesRescueBeforeAnyNativeWorldRead() {
        for (boolean inactive : new boolean[]{false, true}) {
            var body = new Body() {
                @Override public void unavailable() { calls.add("release"); }
            };
            body.active = !inactive;
            body.loaded = false;
            CompanionPhysics.tick(body);
            assertEquals(List.of("release"), body.calls);
            assertEquals(0, body.steps);
        }
    }
}
