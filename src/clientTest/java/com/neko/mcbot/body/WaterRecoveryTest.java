package com.neko.mcbot.body;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WaterRecoveryTest {
    private static class Water implements WaterRecovery.Access {
        boolean allowed = true, submerged = true;
        Vec3 position = Vec3.ZERO;
        int reads;
        @Override public boolean allowed() { return allowed; }
        @Override public boolean submerged() { return submerged; }
        @Override public Vec3 position() { return position; }
        @Override public WaterRecovery.Medium eyesAt(Vec3 feet) {
            reads++;
            return feet.y >= 3 ? WaterRecovery.Medium.AIR : WaterRecovery.Medium.WATER;
        }
        @Override public boolean passage(Vec3 from, Vec3 to) { reads++; return true; }
    }

    @Test void openWaterRisesWithoutAnyHorizontalInputAndClearsWhenDry() {
        var body = new Water();
        var recovery = new WaterRecovery();
        var input = recovery.tick(body);
        assertTrue(input.jump());
        assertEquals(0, input.x());
        assertEquals(0, input.z());
        body.submerged = false;
        assertNull(recovery.tick(body));
        assertEquals("idle", recovery.state());
    }

    @Test void ceilingUsesConnectedWaypointsRatherThanAChordThroughTheWall() {
        var body = new Water() {
            @Override public boolean passage(Vec3 from, Vec3 to) {
                reads++;
                // The opening is to the east; rising is blocked in the initial column.
                return !(to.y > from.y && to.x < 1);
            }
            @Override public WaterRecovery.Medium eyesAt(Vec3 feet) {
                reads++;
                return feet.x >= 1 && feet.y >= 1 ? WaterRecovery.Medium.AIR : WaterRecovery.Medium.WATER;
            }
        };
        var recovery = new WaterRecovery();
        var east = recovery.tick(body);
        assertTrue(east.x() > 0);
        assertEquals(0, east.z());
        body.position = new Vec3(1, 0, 0);
        var up = recovery.tick(body);
        assertEquals(0, up.x(), "must reach the connected column before rising");
        assertTrue(up.jump());
        assertTrue(body.reads < 258);
    }

    @Test void blockedOrUnknownPassagesProduceNoMovementAndRetargetOnlyOnCooldown() {
        var body = new Water() {
            @Override public boolean passage(Vec3 from, Vec3 to) { reads++; return false; }
        };
        var recovery = new WaterRecovery();
        assertEquals(MovementInput.Frame.IDLE, recovery.tick(body));
        int searched = body.reads;
        for (int i = 0; i < 18; i++) assertEquals(MovementInput.Frame.IDLE, recovery.tick(body));
        assertEquals(searched + 18, body.reads, "only the short upward probe repeats between searches");
        assertEquals("trapped_or_unknown", recovery.state());
    }

    @Test void readOnlyAndStaleBodyGatesPreventPlanningOrSwimmingAndDiscardOldRoutes() {
        var body = new Water();
        var recovery = new WaterRecovery();
        assertNotNull(recovery.tick(body));
        body.allowed = false;
        body.reads = 0;
        assertNull(recovery.tick(body));
        assertEquals(0, body.reads);
        assertEquals("suppressed", recovery.state());
        body.allowed = true;
        assertTrue(recovery.tick(body).jump());
    }

    @Test void deepWaterSearchAndEpisodeHaveFiniteBudgets() {
        var body = new Water() {
            @Override public WaterRecovery.Medium eyesAt(Vec3 feet) {
                reads++;
                return WaterRecovery.Medium.WATER;
            }
        };
        var recovery = new WaterRecovery();
        assertTrue(recovery.tick(body).jump());
        assertEquals("rising", recovery.state());
        assertTrue(body.reads <= 255, "128 examined positions bound the search");
        for (int i = 1; i < 600; i++) recovery.tick(body);
        assertEquals(MovementInput.Frame.IDLE, recovery.tick(body));
        assertEquals("limit", recovery.state());
        int reads = body.reads;
        for (int i = 0; i < 100; i++) recovery.tick(body);
        assertEquals(reads, body.reads);
        body.submerged = false;
        recovery.tick(body);
        body.submerged = true;
        assertTrue(recovery.tick(body).jump(), "a new episode starts only after actual head clearance");
    }

    @Test void teleportAndWorldChangesDiscardCachedWaypointsBeforeProbingTheirSweep() {
        var body = new Water() {
            Object dimension = new Object();
            @Override public Object worldIdentity() { return dimension; }
            @Override public boolean passage(Vec3 from, Vec3 to) {
                assertTrue(from.distanceToSqr(to) <= 4, "never probe a distant old waypoint");
                reads++;
                return true;
            }
        };
        var recovery = new WaterRecovery();
        assertTrue(recovery.tick(body).jump());
        body.position = new Vec3(100000, 0, 100000);
        assertTrue(recovery.tick(body).jump());
        for (int i = 0; i < 601; i++) recovery.tick(body);
        assertEquals("limit", recovery.state());
        body.dimension = new Object();
        assertTrue(recovery.tick(body).jump(), "world replacement must not inherit an exhausted episode");
        assertNotEquals("limit", recovery.state());
    }

    @Test void aBlockedEdgeDoesNotHideAnAirCellReachableFromAnotherConnectedSide() {
        var body = new Water() {
            @Override public boolean passage(Vec3 from, Vec3 to) {
                reads++;
                if (to.equals(new Vec3(1, 1, 0))) return from.equals(new Vec3(1, 0, 0));
                return to.equals(new Vec3(0, 1, 0)) || to.equals(new Vec3(1, 0, 0)) || to.equals(Vec3.ZERO);
            }
            @Override public WaterRecovery.Medium eyesAt(Vec3 feet) {
                reads++;
                return feet.equals(new Vec3(1, 1, 0)) ? WaterRecovery.Medium.AIR : WaterRecovery.Medium.WATER;
            }
        };
        var recovery = new WaterRecovery();
        var input = recovery.tick(body);
        assertTrue(input.x() > 0, "an earlier failed north-to-air edge must not hide the valid east-to-air edge");
        assertEquals("seeking_air", recovery.state());
    }
}
