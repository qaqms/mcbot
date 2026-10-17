package com.neko.mcbot.body;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EnvironmentRecoveryTest {
    private static class Body implements EnvironmentRecovery.Access {
        boolean allowed = true, valid = true, submerged = true, acquire = true;
        Object world = new Object();
        int air = 180, maxAir = 300, acquisitions, releases, reads;
        String hazard = "";
        Vec3 position = Vec3.ZERO;
        @Override public boolean allowed() { return allowed; }
        @Override public boolean submerged() { return submerged; }
        @Override public Object worldIdentity() { return world; }
        @Override public Vec3 position() { return position; }
        @Override public int air() { return air; }
        @Override public int maxAir() { return maxAir; }
        @Override public String hazard() { return hazard; }
        @Override public boolean acquire() { acquisitions++; return acquire; }
        @Override public boolean valid() { return valid; }
        @Override public void release() { releases++; }
        @Override public WaterRecovery.Medium eyesAt(Vec3 feet) {
            reads++;
            return feet.y >= 3 ? WaterRecovery.Medium.AIR : WaterRecovery.Medium.WATER;
        }
        @Override public boolean passage(Vec3 from, Vec3 to) { reads++; return true; }
    }

    private static void clear(EnvironmentRecovery recovery, Body body) {
        body.submerged = false;
        body.hazard = "";
        body.air = body.maxAir;
        for (int i = 0; i < 20; i++) recovery.tick(body);
    }

    @Test void idleBodyDoesNotInventAnEpisodeOrAnObservedAirMinimum() {
        var recovery = new EnvironmentRecovery();
        var body = new Body();
        body.submerged = false;
        body.air = body.maxAir;
        assertNull(recovery.tick(body));
        assertFalse(recovery.observation().get("episode_known").getAsBoolean());
        assertFalse(recovery.observation().has("lowest_air_supply"));
    }

    @Test void dryWorkPolicySurfacesEvenWithFullAirWithoutInventingDamage() {
        var body = new Body();
        body.air = body.maxAir;
        var recovery = new EnvironmentRecovery();
        assertTrue(recovery.tick(body).jump());
        assertTrue(recovery.active());
        assertEquals(300, body.air);
        assertEquals(1, body.acquisitions);
        assertFalse(recovery.observation().get("goal_satisfied").getAsBoolean());
    }

    @Test void dangerAppearingDuringNativeTravelAcquiresOriginalAuthorityBeforeWorkCanSettle() {
        var waterBody = new Body();
        waterBody.submerged = false;
        waterBody.air = waterBody.maxAir;
        var recovery = new EnvironmentRecovery();
        var physics = new CompanionPhysicsTest.Body() {
            @Override public MovementInput.Frame recoveryInput() { return recovery.tick(waterBody); }
            @Override public void travel(Vec3 input) {
                super.travel(input);
                waterBody.submerged = true;
            }
            @Override public void afterMovement() {
                assertNotNull(recorded);
                assertFalse(recovery.holdsWork());
                recovery.tick(waterBody);
            }
        };
        CompanionPhysics.tick(physics);
        assertEquals(1, physics.steps, "post-movement discovery must not travel a second time");
        assertEquals(1, waterBody.acquisitions);
        assertTrue(recovery.active());
        assertEquals(1, recovery.observation().get("elapsed_ticks").getAsInt());
    }

    @Test void headClearanceNeedsNativeAirRefillAndTwentyStableTicksBeforeReturningWork() {
        var body = new Body();
        var recovery = new EnvironmentRecovery();
        recovery.tick(body);
        body.submerged = false;
        body.air = 30;
        for (int i = 0; i < 25; i++) {
            assertEquals(MovementInput.Frame.IDLE, recovery.tick(body));
            assertTrue(recovery.active());
        }
        body.air = body.maxAir;
        for (int i = 0; i < 19; i++) assertNotNull(recovery.tick(body));
        assertNull(recovery.tick(body));
        assertFalse(recovery.active());
        assertFalse(recovery.holdsWork());
        assertEquals("local_clear", recovery.state());
        assertEquals(1, body.releases);
        assertEquals(30, recovery.observation().get("lowest_air_supply").getAsInt());
    }

    @Test void waterReentryDuringRefillKeepsOriginalEpisodeOriginAndSearchAccount() {
        var body = new Body();
        var recovery = new EnvironmentRecovery();
        recovery.tick(body);
        int probes = recovery.observation().getAsJsonObject("water").get("search_probes").getAsInt();
        body.submerged = false;
        recovery.tick(body);
        body.submerged = true;
        recovery.tick(body);
        assertEquals(1, body.acquisitions);
        assertEquals(1, recovery.observation().get("episodes").getAsInt());
        assertEquals(probes, recovery.observation().getAsJsonObject("water").get("search_probes").getAsInt());
        body.position = new Vec3(7, 0, 0);
        assertEquals(MovementInput.Frame.IDLE, recovery.tick(body));
        assertEquals("scope_limit", recovery.state());
        assertFalse(recovery.active());
        assertEquals(1, body.releases);
        for (int i = 0; i < 50; i++) assertNull(recovery.tick(body));
        assertEquals(1, body.acquisitions);
    }

    @Test void nonWaterHazardsStopInputWithoutPlanningAnUnverifiedEscape() {
        for (String danger : new String[]{"lava", "on_fire", "suffocating", "powder_snow", "below_world", "unknown"}) {
            var body = new Body();
            body.submerged = false;
            body.hazard = danger;
            var recovery = new EnvironmentRecovery();
            assertEquals(MovementInput.Frame.IDLE, recovery.tick(body));
            assertEquals("environment_stop", recovery.state());
            assertEquals(danger, recovery.observation().get("last_reason").getAsString());
            assertEquals(0, body.reads);
            clear(recovery, body);
            assertEquals("local_clear", recovery.state());
        }
    }

    @Test void deadlineIncludesTimeSpentRefillingAndNeverRestartsInSameDanger() {
        var body = new Body();
        var recovery = new EnvironmentRecovery();
        recovery.tick(body);
        body.submerged = false;
        body.air = 10;
        for (int i = 1; i < 600; i++) recovery.tick(body);
        assertEquals("limit", recovery.state());
        assertFalse(recovery.active());
        assertTrue(recovery.holdsWork());
        recovery.newTask();
        body.submerged = true;
        for (int i = 0; i < 20; i++) assertNull(recovery.tick(body));
        assertEquals(1, body.acquisitions);
        assertEquals(600, recovery.observation().get("elapsed_ticks").getAsInt());
    }

    @Test void revokedBorrowedLeaseOrPermissionDoesNotAcquireIdleAuthorityInstead() {
        for (boolean revokePermission : new boolean[]{false, true}) {
            var body = new Body();
            var recovery = new EnvironmentRecovery();
            recovery.tick(body);
            if (revokePermission) body.allowed = false;
            else body.valid = false;
            assertNull(recovery.tick(body));
            assertEquals("authority_lost", recovery.state());
            body.allowed = body.valid = true;
            recovery.newTask();
            assertNull(recovery.tick(body));
            assertEquals(1, body.acquisitions);
            assertEquals(1, body.releases);
        }
    }

    @Test void readOnlyAndAbsentOwnerSuppressSearchAndInput() {
        var body = new Body();
        body.allowed = false;
        var recovery = new EnvironmentRecovery();
        for (int i = 0; i < 20; i++) assertNull(recovery.tick(body));
        assertEquals("suppressed", recovery.state());
        assertEquals(0, body.acquisitions);
        assertEquals(0, body.reads);
    }

    @Test void cancelBlocksAutomaticRescueEvenAfterPhysicalRecoveryUntilNewTask() {
        var body = new Body();
        var recovery = new EnvironmentRecovery();
        recovery.tick(body);
        recovery.cancel();
        recovery.newTask();
        assertNull(recovery.tick(body), "new task cannot replenish the same interrupted episode");
        recovery.cancel();
        clear(recovery, body);
        body.submerged = true;
        assertNull(recovery.tick(body));
        recovery.newTask();
        assertNotNull(recovery.tick(body));
        assertEquals(2, body.acquisitions);
        assertEquals(1, body.releases);
    }

    @Test void taskEndWorldChangeAndUnknownWorldReleaseWithoutReplayingOldRoute() {
        for (String change : new String[]{"task", "world", "unknown"}) {
            var body = new Body();
            var recovery = new EnvironmentRecovery();
            recovery.tick(body);
            switch (change) {
                case "task" -> recovery.stopCurrent();
                case "world" -> body.world = new Object();
                default -> recovery.unavailable();
            }
            assertNull(recovery.tick(body));
            assertFalse(recovery.active());
            assertEquals(1, body.releases);
            assertEquals(1, body.acquisitions);
        }
    }

    @Test void acquisitionCleanupCanCancelReentrantlyWithoutAnySwimmingOrLeakedLease() {
        var recovery = new EnvironmentRecovery();
        var body = new Body() {
            @Override public boolean acquire() {
                acquisitions++;
                recovery.cancel();
                return true;
            }
        };
        assertNull(recovery.tick(body));
        assertFalse(recovery.active());
        assertEquals(2, body.releases, "release is idempotent even if acquisition completes after cancellation");
        assertEquals("cancelled", recovery.state());
        assertEquals(0, body.reads);
        assertNull(recovery.tick(body));
    }

    @Test void unavailableAuthorityDoesNotRetryEveryTickOrConsumeSearch() {
        var body = new Body();
        body.acquire = false;
        var recovery = new EnvironmentRecovery();
        for (int i = 0; i < 40; i++) assertNull(recovery.tick(body));
        assertEquals(1, body.acquisitions);
        assertEquals(1, body.releases);
        assertEquals(0, body.reads);
    }

    @Test void releaseFailureCannotKeepOldRescueActiveOrReplayItsInput() {
        var recovery = new EnvironmentRecovery();
        var body = new Body() {
            @Override public void release() {
                super.release();
                throw new IllegalStateException("native cleanup failure");
            }
        };
        recovery.tick(body);
        assertThrows(IllegalStateException.class, recovery::cancel);
        assertFalse(recovery.active());
        assertTrue(recovery.observation().get("automatic_resume_blocked").getAsBoolean());
        assertNull(recovery.tick(body));
        recovery.cancel();
        assertEquals(1, body.releases);
    }

    @Test void distinctWaterEpisodesHaveABodyLifetimeCapThatNewTasksCannotClear() {
        var body = new Body();
        var recovery = new EnvironmentRecovery();
        for (int i = 0; i < 64; i++) {
            body.submerged = true;
            assertNotNull(recovery.tick(body));
            clear(recovery, body);
            recovery.newTask();
        }
        body.submerged = true;
        assertNull(recovery.tick(body));
        assertEquals("episode_limit", recovery.state());
        assertEquals(64, body.acquisitions);
        assertEquals(64, body.releases);
    }

    @Test void commonSafetyPredicateRejectsEveryDangerAndActiveRecoveryButAllowsFreshDryWork() {
        assertNull(EnvironmentRecovery.workFailure("", false, false));
        assertNotNull(EnvironmentRecovery.workFailure("", true, false));
        assertNotNull(EnvironmentRecovery.workFailure("", false, true));
        for (int flag = 0; flag < 5; flag++) {
            String hazard = EnvironmentRecovery.hazard(flag == 0, flag == 1, flag == 2, flag == 3, flag == 4);
            var failure = EnvironmentRecovery.workFailure(hazard, false, false);
            assertNotNull(failure);
            assertTrue(failure.feedback().startsWith("ENVIRONMENT_UNSAFE:"));
            assertFalse(failure.data().get("environment_work_safe").getAsBoolean());
        }
    }
}
