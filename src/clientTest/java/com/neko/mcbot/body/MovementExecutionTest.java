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

        Body() { position = new Vec3(.5, 0, .5); velocity = new Vec3(0, -.08, 0); }
        @Override public MovementInput.Frame takeInput() { return input.take(); }
        @Override public boolean grounded() { return grounded; }
        @Override public void jump() {
            velocity = new Vec3(velocity.x, .42, velocity.z);
            jumps++;
        }
        @Override public void travel(Vec3 direction) {
            lastInput = direction;
            double acceleration = grounded ? .1 : .02;
            velocity = velocity.add(direction.x * acceleration, 0, direction.z * acceleration);
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
}
