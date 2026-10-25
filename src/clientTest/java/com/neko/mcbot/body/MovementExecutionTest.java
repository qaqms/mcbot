package com.neko.mcbot.body;

import com.neko.mcbot.path.PathSteering;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MovementExecutionTest {
    @Test void dismantlingAColumnUsesGravityAndWaitsForTheMeasuredLandingBeforeAnotherLayer() {
        var body=new Body();
        body.position=new Vec3(.5,2,.5);
        body.obstacles=List.of(Shapes.box(-4,-1,-4,8,0,8),Shapes.box(0,0,0,1,2,1));
        for (int landing : new int[]{1,0}) {
            body.obstacles=landing==1
                    ? List.of(body.obstacles.getFirst(),Shapes.box(0,0,0,1,1,1))
                    : List.of(body.obstacles.getFirst());
            var route=new PathSteering(0,landing,0,false);
            boolean airborne=false;
            PathSteering.Command command=null;
            for (int tick=0;tick<100;tick++) {
                command=body.drive(route);
                assertFalse(command.jump());
                assertFalse(command.stuck());
                if (!body.grounded) { airborne=true; assertFalse(command.arrived()); }
                if (command.arrived()) break;
            }
            assertNotNull(command);
            assertTrue(airborne);
            assertTrue(command.arrived());
            assertTrue(body.grounded);
            assertEquals(landing,body.position.y,1e-9);
            assertEquals(.5,body.position.x,1e-9);
        }
        assertEquals(0,body.jumps);
    }

    @Test void descentAndLandingCorrectionsBrakeWithoutTurningBackToTheNodeCenter() {
        var route = new PathSteering(1, 0, 0, false);
        var before = new PathSteering.Body(.5, 1, .5, .12, 0, false);
        assertEquals(new PathSteering.Facing(1, 0), route.facing(before));
        var overshot = new PathSteering.Body(1.57, .4, .5, .07, 0, false);
        assertTrue(route.tick(overshot).x() < 0, "real braking still opposes forward velocity");
        assertNull(route.facing(overshot), "tiny backwards correction must keep the previous heading");
        assertNull(route.facing(new PathSteering.Body(1.5, 0, .5, 0, 0, true)));
        assertEquals(new PathSteering.Facing(-1, 0),
                route.facing(new PathSteering.Body(2.5, 0, .5, 0, 0, true)),
                "a genuinely displaced body may still need to turn back");
        var through = new PathSteering(1, 0, 0, false);
        through.tick(before, true);
        var fallingPastCenter = new PathSteering.Body(1.88, .4, .5, .08, 0, false);
        assertTrue(through.tick(fallingPastCenter, true).x() > 0);
        assertNull(through.facing(fallingPastCenter), "continuous descent must not turn toward the passed center");
        assertFalse(through.tick(fallingPastCenter, true).arrived(), "passing in air is not landing");
    }

    @Test void fractionalLandingRequiresItsActualHeightAndDoesNotJumpWhenTheGeometrySaysStep() {
        var route = new PathSteering(1, .5, 0, false);
        var first = route.tick(new PathSteering.Body(.5, 0, .5, 0, 0, true));
        assertFalse(first.jump());
        assertFalse(first.arrived());
        assertFalse(route.settled(new PathSteering.Body(1.5, 0, .5, 0, 0, true)));
        assertFalse(route.settled(new PathSteering.Body(1.5, .5, .5, 0, 0, false)));
        assertTrue(route.settled(new PathSteering.Body(1.5, .5, .5, 0, 0, true)));
        var body = new Body();
        body.position = new Vec3(.5, .5, .5);
        body.obstacles = List.of(Shapes.box(-4, -1, -4, 8, .5, 8));
        PathSteering.Command command = null;
        for (int tick = 0; tick < 100; tick++) {
            command = body.drive(route);
            if (command.arrived() || command.stuck()) break;
        }
        assertNotNull(command);
        assertTrue(command.arrived());
        assertEquals(.5, body.position.y);
        assertEquals(0, body.jumps);
    }
    /** Controlled acceleration/gravity, with actual vanilla voxel collision. Not native travel. */
    private static class Body extends CompanionPhysicsTest.Body {
        final MovementInput input = new MovementInput();
        List<VoxelShape> obstacles = List.of(Shapes.box(-4, -1, -4, 8, 0, 8));
        boolean grounded = true;
        int jumps;
        Vec3 lastInput;
        float yaw;
        boolean sprinting;

        Body() { position = new Vec3(.5, 0, .5); velocity = new Vec3(0, -.08, 0); }
        @Override public MovementInput.Frame takeInput() { return input.take(); }
        @Override public boolean grounded() { return grounded; }
        @Override public float yaw() { return yaw; }
        @Override public boolean maySprint() { return true; }
        @Override public void sprint(boolean enabled) { sprinting = enabled; }
        @Override public void jump() {
            velocity = new Vec3(velocity.x, .42, velocity.z);
            jumps++;
        }
        @Override public void travel(Vec3 direction) {
            lastInput = direction;
            double acceleration = (grounded ? .1 : .02) * (sprinting ? 1.3 : 1);
            double angle = Math.toRadians(yaw);
            double worldX = direction.x * Math.cos(angle) - direction.z * Math.sin(angle);
            double worldZ = direction.x * Math.sin(angle) + direction.z * Math.cos(angle);
            velocity = velocity.add(worldX * acceleration, 0, worldZ * acceleration);
            double dy = Shapes.collide(Direction.Axis.Y, bounds(), obstacles, velocity.y);
            var shifted = bounds().move(0, dy, 0);
            double dx = Shapes.collide(Direction.Axis.X, shifted, obstacles, velocity.x);
            shifted = shifted.move(dx, 0, 0);
            double dz = Shapes.collide(Direction.Axis.Z, shifted, obstacles, velocity.z);
            grounded = dy != velocity.y && velocity.y <= 0;
            position = position.add(dx, dy, dz);
            velocity = new Vec3(dx * (grounded ? .546 : .91), (dy - .08) * .98,
                    dz * (grounded ? .546 : .91));
        }
        PathSteering.Command drive(PathSteering route) {
            var command = route.tick(new PathSteering.Body(position.x, position.y, position.z,
                    velocity.x, velocity.z, grounded));
            input.renew(command.x(), command.z(), command.jump());
            CompanionPhysics.tick(this);
            return command;
        }
    }

    @Test void flatMovementTakesMultiplePhysicalStepsAndSettlesAtTheLanding() {
        var body = new Body();
        var route = new PathSteering(1, 0, 0);
        var first = body.drive(route);
        assertFalse(first.arrived());
        assertTrue(body.position.x > .5 && body.position.x < .8);
        PathSteering.Command command = first;
        for (int i = 0; i < 100 && !command.arrived(); i++) command = body.drive(route);
        assertTrue(command.arrived());
        assertEquals(1.5, body.position.x, .16);
        assertEquals(0, body.position.y, 1e-9);
        assertEquals(0, body.jumps);
    }

    @Test void edgeSupportedBodyWalksToAValidOriginWithoutFallingJumpingOrTeleporting() {
        for (int direction : new int[]{-1, 1}) {
            var body = new Body();
            double x = direction > 0 ? .98 : .02;
            int cell = direction > 0 ? 1 : -1;
            body.position = new Vec3(x, 0, .5);
            body.obstacles = List.of(Shapes.box(cell, -1, -2, cell + 1, 0, 2));
            var geometry = new com.neko.mcbot.server.StandingGeometry(
                    new com.neko.mcbot.server.StandingGeometry.Access() {
                        @Override public boolean known(net.minecraft.core.BlockPos pos) { return true; }
                        @Override public List<net.minecraft.world.phys.AABB> boxes(net.minecraft.core.BlockPos pos) {
                            return pos.getX() == cell && pos.getY() == -1 && pos.getZ() >= -2 && pos.getZ() < 2
                                    ? List.of(new net.minecraft.world.phys.AABB(0, 0, 0, 1, 1, 1)) : List.of();
                        }
                    }, .6, 1.8, .6);
            var origin = geometry.departure(x, 0, .5, p -> true);
            assertNotNull(origin);
            var route = new PathSteering(origin.getX(), 0, origin.getZ(), false);
            PathSteering.Command command = null;
            int ticks = 0;
            for (; ticks < 100; ticks++) {
                assertTrue(geometry.canAlign(body.position.x, body.position.y, body.position.z, origin));
                command = body.drive(route);
                assertFalse(command.stuck());
                assertEquals(0, body.position.y, 1e-9);
                if (command.arrived()) break;
            }
            assertNotNull(command);
            assertTrue(command.arrived());
            assertTrue(ticks > 1);
            assertEquals(cell + .5, body.position.x, .16);
            assertEquals(cell, (int) Math.floor(body.position.x));
            assertEquals(0, body.jumps);
        }
    }

    @Test void aFullBlockAscentUsesAJumpArcAndMustLandBeforeSuccess() {
        var body = new Body();
        body.obstacles = List.of(body.obstacles.getFirst(), Shapes.box(1, 0, 0, 3, 1, 1));
        var route = new PathSteering(1, 1, 0);
        var command = body.drive(route);
        assertFalse(command.arrived());
        assertTrue(body.position.y > 0 && body.position.y < 1);
        boolean aboveLanding = false;
        for (int i = 0; i < 110 && !command.arrived(); i++) {
            command = body.drive(route);
            aboveLanding |= body.position.y > 1.05;
        }
        assertTrue(aboveLanding);
        assertTrue(command.arrived());
        assertEquals(1, body.position.y, 1e-9);
        assertTrue(body.grounded);
    }

    @Test void descentContinuesInAirAndDoesNotDeclareArrivalAtTheHorizontalTarget() {
        var body = new Body();
        body.position = new Vec3(.5, 1, .5);
        body.obstacles = List.of(body.obstacles.getFirst(), Shapes.box(0, 0, 0, 1, 1, 1));
        var route = new PathSteering(1, 0, 0);
        var command = body.drive(route);
        boolean airborne = false;
        for (int i = 0; i < 110 && !command.arrived(); i++) {
            command = body.drive(route);
            if (!body.grounded) {
                airborne = true;
                assertFalse(command.arrived());
            }
        }
        assertTrue(airborne);
        assertTrue(command.arrived());
        assertEquals(0, body.position.y, 1e-9);
        assertEquals(0, body.jumps);
    }

    @Test void aTwoOrThreeBlockLedgeWalksOffInEveryCardinalDirectionAndSettlesBeforeSuccess() {
        for (int drop : new int[]{2,3}) for (int[] heading : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
            var body = new Body();
            body.position = new Vec3(.5, drop, .5);
            body.obstacles = List.of(body.obstacles.getFirst(), Shapes.box(0, 0, 0, 1, drop, 1));
            var route = new PathSteering(heading[0], 0, heading[1], false);
            boolean airborne = false;
            PathSteering.Command command = null;
            for (int tick = 0; tick < 120; tick++) {
                command = body.drive(route);
                assertFalse(command.stuck());
                if (!body.grounded) {
                    airborne = true;
                    assertFalse(command.arrived());
                }
                if (command.arrived()) break;
            }
            assertNotNull(command);
            assertTrue(airborne);
            assertTrue(command.arrived());
            assertEquals(heading[0] + .5, body.position.x, .16);
            assertEquals(heading[1] + .5, body.position.z, .16);
            assertEquals(0, body.position.y, 1e-9);
            assertEquals(0, body.jumps);
        }
    }

    @Test void aWallCannotBeCrossedAndStalledMovementYieldsABoundedFailure() {
        var body = new Body();
        body.obstacles = List.of(body.obstacles.getFirst(), Shapes.box(1, 0, -2, 2, 3, 2));
        var route = new PathSteering(1, 0, 0);
        PathSteering.Command command = null;
        for (int i = 0; i < 125; i++) {
            command = body.drive(route);
            assertFalse(command.arrived());
            assertTrue(body.position.x <= .700001);
            if (command.stuck()) break;
        }
        assertNotNull(command);
        assertTrue(command.stuck());
    }

    @Test void controlsExpireWithoutRenewalAndCannotSurviveAnUnloadedTick() {
        var body = new Body();
        body.input.renew(1, 0, false);
        CompanionPhysics.tick(body);
        assertEquals(1, body.lastInput.x);
        CompanionPhysics.tick(body);
        assertEquals(Vec3.ZERO, body.lastInput);
        body.loaded = false;
        body.input.renew(1, 0, true);
        CompanionPhysics.tick(body);
        body.loaded = true;
        CompanionPhysics.tick(body);
        assertEquals(Vec3.ZERO, body.lastInput);
        assertEquals(0, body.jumps);
    }

    @Test void inputIsNormalizedAndWorldDirectionSurvivesTheCurrentFacing() {
        var input = new MovementInput();
        input.renew(3, 4, true);
        var frame = input.take();
        assertEquals(1, Math.hypot(frame.x(), frame.z()), 1e-9);
        var local = frame.relativeTo(90);
        assertEquals(.8, local.x, 1e-9);
        assertEquals(-.6, local.z, 1e-9);
        assertTrue(frame.jump());
        assertEquals(MovementInput.Frame.IDLE, input.take());
        assertThrows(IllegalArgumentException.class, () -> input.renew(Double.NaN, 0, false));
    }

    @Test void sprintInputIsNormalizedAndExpiresWithTheRestOfTheFrame() {
        var input = new MovementInput();
        input.renew(3, 4, false, true);
        var frame = input.take();
        assertEquals(1, Math.hypot(frame.x(), frame.z()), 1e-9);
        assertTrue(frame.sprint());
        assertEquals(MovementInput.Frame.IDLE, input.take());
        input.renew(0, 1, false, true);
        input.clear();
        assertFalse(input.take().sprint());
        input.renew(0, 1, false);
        assertFalse(input.take().sprint(), "old walk and water-reflex callers stay walking");
    }

    @Test void runningStraightIsFasterAndTheLastTwoWalkingStepsStillSettleAtTheEndpoint() {
        int walkingTicks = flatTrip(false);
        int runningTicks = flatTrip(true);
        assertTrue(runningTicks < walkingTicks, "walking=" + walkingTicks + " running=" + runningTicks);
    }

    private int flatTrip(boolean allowSprint) {
        var body = new Body();
        body.yaw = -90;
        body.obstacles = List.of(Shapes.box(-4, -1, -4, 20, 0, 4));
        int node = 1, sprintTicks = 0;
        var route = new PathSteering(node, 0, 0, false);
        for (int tick = 0; tick < 240; tick++) {
            boolean through = node < 12;
            var command = route.tick(new PathSteering.Body(body.position.x, body.position.y, body.position.z,
                    body.velocity.x, body.velocity.z, body.grounded), through);
            assertFalse(command.stuck());
            if (command.arrived()) {
                if (!through) {
                    assertEquals(12.5, body.position.x, .16);
                    assertTrue(Math.abs(body.velocity.x) <= .04);
                    assertEquals(0, body.position.y, 1e-9);
                    assertEquals(0, body.jumps);
                    assertFalse(body.sprinting);
                    assertEquals(allowSprint, sprintTicks > 0);
                    return tick;
                }
                route = new PathSteering(++node, 0, 0, false);
                body.input.renew(1, 0, false, allowSprint && node <= 10);
            } else body.input.renew(command.x(), command.z(), command.jump(), allowSprint && node <= 10);
            CompanionPhysics.tick(body);
            if (body.sprinting) sprintTicks++;
        }
        fail("flat trip did not arrive");
        return -1;
    }

    @Test void beingInTheTargetCellDoesNotReplaceStandingStillAtItsActualLanding() {
        var route = new PathSteering(0, 0, 0);
        assertFalse(route.settled(new PathSteering.Body(.1, 0, .1, 0, 0, true)));
        assertFalse(route.settled(new PathSteering.Body(.5, 0, .5, .2, 0, true)));
        assertFalse(route.settled(new PathSteering.Body(.5, .5, .5, 0, 0, false)));
        assertTrue(route.settled(new PathSteering.Body(.5, 0, .5, 0, 0, true)));
    }

    @Test void diagonalAscentAlsoSettlesWithLimitedAirControl() {
        var body = new Body();
        body.obstacles = List.of(body.obstacles.getFirst(), Shapes.box(1, 0, 1, 3, 1, 3));
        var route = new PathSteering(1, 1, 1);
        PathSteering.Command command = null;
        for (int i = 0; i < 120; i++) {
            command = body.drive(route);
            if (command.arrived() || command.stuck()) break;
        }
        assertNotNull(command);
        assertTrue(command.arrived());
        assertEquals(1, body.position.y, 1e-9);
    }

    @Test void straightIntermediateLandingsKeepMomentumButTheEndpointMustStop() {
        var body = new Body();
        int node = 1;
        var route = new PathSteering(node, 0, 0);
        boolean passedWithMomentum = false;
        boolean arrived = false;
        for (int tick = 0; tick < 120; tick++) {
            boolean through = node < 3;
            var command = route.tick(new PathSteering.Body(body.position.x, body.position.y, body.position.z,
                    body.velocity.x, body.velocity.z, body.grounded), through);
            if (command.arrived()) {
                if (!through) { arrived = true; break; }
                passedWithMomentum |= body.velocity.x > .04;
                route = new PathSteering(++node, 0, 0);
                body.input.renew(1, 0, false);
            } else body.input.renew(command.x(), command.z(), command.jump());
            CompanionPhysics.tick(body);
        }
        assertTrue(passedWithMomentum);
        assertTrue(arrived);
        assertEquals(3.5, body.position.x, .16);
        assertTrue(Math.abs(body.velocity.x) <= .04);
    }

    @Test void descendingStairsRetainMotionAtIntermediateLandingsAndSettleAtTheFinalOne() {
        var body = new Body();
        body.position = new Vec3(.5, 2, .5);
        body.obstacles = List.of(Shapes.box(-4, -1, -4, 1, 2, 4),
                Shapes.box(1, -1, -4, 2, 1, 4), Shapes.box(2, -1, -4, 8, 0, 4));
        int node = 1;
        var route = new PathSteering(1, 1, 0, false);
        boolean arrived = false, momentum = false;
        var landings = new java.util.ArrayList<String>();
        for (int tick = 0; tick < 180; tick++) {
            var command = route.tick(new PathSteering.Body(body.position.x, body.position.y, body.position.z,
                    body.velocity.x, body.velocity.z, body.grounded), node < 3);
            assertFalse(command.stuck(), "node=" + node + " position=" + body.position + " velocity=" + body.velocity);
            if (command.arrived()) {
                if (node == 3) { arrived = true; break; }
                landings.add("node=" + node + " position=" + body.position + " velocity=" + body.velocity);
                momentum |= body.velocity.x > .04;
                route = new PathSteering(++node, 0, 0, false);
                body.input.renew(1, 0, false);
            } else body.input.renew(command.x(), command.z(), command.jump());
            CompanionPhysics.tick(body);
        }
        assertTrue(arrived);
        assertTrue(momentum, landings.toString());
        assertEquals(3.5, body.position.x, .16);
        assertEquals(0, body.position.y, 1e-9);
        assertEquals(0, body.jumps);
    }
}
