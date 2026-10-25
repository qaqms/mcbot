package com.neko.mcbot.task;

import com.neko.mcbot.body.BodyControl;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.FollowFixtures;
import com.neko.mcbot.server.FollowWork;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.server.ThreatFacts;
import com.neko.mcbot.server.AttackFixtures;
import com.neko.mcbot.server.EntityAttack;
import com.neko.mcbot.server.tools.AttackTool;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class FollowSchedulerTest {
    @BeforeAll static void registries() { com.neko.mcbot.server.BlockActionFixtures.bootstrap(); }
    private final CompanionScheduler scheduler = new CompanionScheduler();
    private final UUID id = UUID.randomUUID();
    private final BodyControl control = new BodyControl(previous -> {
        if (previous == BodyControl.Owner.WORK) scheduler.preempt(id);
    });
    private final FollowFixtures.Access access = new FollowFixtures.Access();
    private final AtomicBoolean valid = new AtomicBoolean(true);
    private final CompanionScheduler.Bodies present = bodies(true, true);

    private static CompanionScheduler.Bodies bodies(boolean same, boolean dimension) {
        return new CompanionScheduler.Bodies() {
            @Override public CompanionPlayer get(UUID id) { return null; }
            @Override public boolean same(CompanionPlayer old, CompanionPlayer fresh) { return same; }
            @Override public boolean sameDimension(CompanionPlayer body, net.minecraft.server.level.ServerLevel level) {
                return dimension;
            }
        };
    }
    private CompletableFuture<ServerTool.Result> start(boolean owner, int ticks) {
        var task = access.work(owner, ticks);
        return start(task, ticks);
    }
    private CompletableFuture<ServerTool.Result> start(TickTask task, int ticks) {
        return scheduler.execute(id, List.of(), () -> scheduler.controlled(id, control, valid::get, () -> {
            var future = new CompletableFuture<ServerTool.Result>();
            assertTrue(scheduler.submit(id, null, task, future, ticks));
            return future;
        }));
    }
    private void tick(int count) { for (int i = 0; i < count; i++) scheduler.tick(present); }
    private String phase() { return scheduler.observation(id).getAsJsonObject("progress").get("phase").getAsString(); }

    @Test void arrivalSleepsWithoutSettlingOrExecutionAndJitterDoesNotWake() {
        var future = start(true, 20);
        tick(1);
        assertEquals("sleeping", phase());
        assertEquals(BodyControl.Owner.IDLE, control.owner());
        assertTrue(scheduler.busy(id));
        assertFalse(future.isDone());
        assertEquals(0, scheduler.observation(id).get("execution_ticks").getAsInt());
        assertFalse(control.claim(new Object()), "dormancy keeps original reservation");
        access.position = access.body.add(4.99, 0, 0);
        tick(4);
        assertEquals("sleeping", phase());
        assertEquals(0, access.plans);
        access.position = access.body.add(5.01, 0, 0);
        tick(3);
        assertEquals("following", phase());
        assertEquals(1, access.plans);
        access.landOnAdvance = true;
        tick(1);
        tick(1);
        assertEquals("sleeping", phase());
        assertEquals(BodyControl.Owner.IDLE, control.owner());
        assertFalse(future.isDone());
        tick(10);
        assertTrue(future.join().ok());
        assertTrue(future.join().data().get("behavior_ended").getAsBoolean());
        assertFalse(future.join().data().get("goal_satisfied").getAsBoolean());
        assertFalse(scheduler.busy(id));
    }

    @Test void ownerAbsenceParksMovementAndSameUuidMayReturnWithinOriginalDeadline() {
        access.position = access.body.add(8, 0, 0);
        var future = start(true, 14);
        tick(3);
        assertEquals(1, access.plans);
        access.present = false;
        tick(2);
        assertEquals("waiting_owner", phase());
        assertEquals(1, access.aborts);
        assertEquals(BodyControl.Owner.IDLE, control.owner());
        access.present = true;
        tick(1);
        assertEquals("following", phase());
        assertEquals(2, access.plans);
        assertEquals(1, access.original.replans());
        tick(8);
        assertTrue(future.join().ok());
        assertEquals(14, future.join().data().getAsJsonObject("follow").get("elapsed_ticks").getAsInt());
        assertFalse(future.join().data().getAsJsonObject("follow").get("in_band").getAsBoolean());
    }

    @Test void absentOwnerExpiryAndExplicitTargetLossAreDifferentFromArrivalOrSuccess() {
        access.present = false;
        var owner = start(true, 3);
        tick(2);
        assertFalse(owner.isDone());
        tick(1);
        assertFalse(owner.join().ok());
        assertTrue(owner.join().feedback().startsWith("TARGET_LOST:"));
        assertEquals(3, owner.join().data().getAsJsonObject("follow").get("owner_absent_ticks").getAsInt());
        var explicit = start(false, 30);
        tick(1);
        assertTrue(explicit.join().feedback().startsWith("TARGET_LOST:"));
        assertEquals(0, access.plans);
    }

    @Test void namedTargetDisappearanceWhileSleepingSettlesImmediatelyWithoutAReplacement() {
        var future = start(false, 30);
        tick(1);
        access.present = false;
        tick(1);
        assertTrue(future.join().feedback().startsWith("TARGET_LOST:"));
        access.present = true;
        tick(5);
        assertEquals(0, access.plans);
        assertFalse(scheduler.busy(id));
    }

    @Test void identityScopeUnknownAndPermissionFailuresStopBeforeAnotherRoute() {
        for (String cause : List.of("identity", "scope", "unknown", "permission", "body", "dimension")) {
            var future = start(false, 30);
            tick(1);
            switch (cause) {
                case "identity" -> access.replacement = UUID.randomUUID();
                case "scope" -> access.targetFailure = new ServerTool.Result(false, "SCOPE_LIMIT:fixed", null);
                case "unknown" -> access.targetFailure = new ServerTool.Result(false, "TARGET_LOST:unknown", null);
                case "permission" -> valid.set(false);
            }
            scheduler.tick(bodies(!cause.equals("body"), !cause.equals("dimension")));
            assertFalse(future.join().ok(), cause);
            assertEquals(0, access.plans, cause);
            assertEquals(BodyControl.Owner.IDLE, control.owner(), cause);
            access.replacement = null;
            access.targetFailure = null;
            valid.set(true);
        }
    }

    @Test void failedRouteIsTerminalInsteadOfSpinningOrRefilling() {
        access.position = access.body.add(8, 0, 0);
        access.progress = new TickTask.Progress.Done(new ServerTool.Result(false, "NO_PATH:dry gap", null));
        var future = start(true, 50);
        tick(3);
        assertTrue(future.join().feedback().startsWith("NO_PATH:"));
        tick(20);
        assertEquals(1, access.plans);
        assertEquals(1, access.advances);
        assertEquals(1, access.aborts);
    }

    @Test void wakesAndReflexResumptionKeepDiagnosticsWithoutATwoReplanQuota() {
        access.position = access.body.add(8, 0, 0);
        var future = start(true, 100);
        tick(3);
        for (int i = 0; i < 2; i++) {
            control.reflex(true);
            tick(2);
            control.reflex(false);
            tick(1);
        }
        assertEquals(3, access.plans);
        assertEquals(2, access.original.replans());
        control.reflex(true);
        tick(1);
        control.reflex(false);
        tick(1);
        assertFalse(future.isDone());
        assertEquals(4, access.plans);
        assertEquals(3, access.original.replans());
        scheduler.cancel(id, "CANCELLED:owner");
        assertTrue(future.join().feedback().startsWith("CANCELLED:"));
        assertFalse(scheduler.busy(id));
    }

    @Test void sleepingTaskCanWakeUnderReflexWithoutTakingInputOrBuyingTime() {
        var future = start(true, 6);
        tick(1);
        control.reflex(true);
        access.position = access.body.add(8, 0, 0);
        tick(3);
        assertEquals(BodyControl.Owner.REFLEX, control.owner());
        assertEquals("following", phase());
        assertEquals(0, access.advances);
        assertEquals(0, scheduler.observation(id).get("execution_ticks").getAsInt());
        control.reflex(false);
        tick(1);
        assertEquals(1, scheduler.observation(id).get("execution_ticks").getAsInt());
        tick(1);
        assertTrue(future.join().ok());
        assertEquals(6, future.join().data().getAsJsonObject("follow").get("elapsed_ticks").getAsInt());
    }

    @Test void cancelDormantOrPreemptedNeverRevivesAndCannotReleaseAnotherToken() {
        for (boolean reflex : new boolean[]{false, true}) {
            access.position = access.body.add(1, 0, 0);
            var future = start(true, 20);
            tick(1);
            if (reflex) control.reflex(true);
            assertTrue(scheduler.cancel(id, "owner"));
            assertTrue(future.join().feedback().startsWith("CANCELLED:"));
            assertFalse(future.join().data().get("automatic_resume").getAsBoolean());
            control.reflex(false);
            Object next = new Object();
            assertTrue(control.claim(next));
            access.position = access.body.add(8, 0, 0);
            tick(4);
            assertTrue(control.owns(next));
            assertEquals(0, access.plans);
            control.release(next);
        }
    }

    @Test void movementBoundaryExceptionReturnsOriginalFactsAndCleansOnce() {
        access.position = access.body.add(8, 0, 0);
        access.throwsAdvance = true;
        var future = start(true, 40);
        tick(3);
        assertTrue(future.join().feedback().startsWith("INTERNAL:"));
        assertTrue(future.join().data().get("behavior_ended").getAsBoolean());
        assertEquals(1, access.aborts);
        assertFalse(scheduler.busy(id));
        assertEquals(BodyControl.Owner.IDLE, control.owner());
    }

    @Test void cancellationBetweenTicksReportsFreshDistanceRatherThanLastSleepingObservation() {
        var future = start(true, 20);
        tick(1);
        access.position = access.body.add(12, 0, 0);
        scheduler.cancel(id, "owner");
        var facts = future.join().data().getAsJsonObject("follow");
        assertTrue(facts.get("in_band_known").getAsBoolean());
        assertFalse(facts.get("in_band").getAsBoolean());
        assertEquals(0, access.plans);
    }

    @Test void dormancyCleanupFailureCannotLeaveAReservedTokenOrTickARemovedSlot() {
        var task = new TickTask() {
            @Override public boolean wantsBody() { return false; }
            @Override public Progress tick(CompanionPlayer c) { fail("dormant task executed"); return running(); }
            @Override public void onSuspend() { throw new IllegalStateException("controlled suspend failure"); }
        };
        var future = start(task, 5);
        tick(1);
        assertTrue(future.join().feedback().startsWith("INTERNAL:"));
        assertEquals(BodyControl.Owner.IDLE, control.owner());
        assertFalse(scheduler.busy(id));
        assertTrue(control.claim(new Object()));
    }

    @Test void genericDormancyUsesOriginalTimeoutAndFreshResumeInsteadOfTaskSpecificScheduling() {
        var sleeping = new AtomicBoolean(true);
        var executions = new java.util.concurrent.atomic.AtomicInteger();
        var polls = new java.util.concurrent.atomic.AtomicInteger();
        var resumes = new java.util.concurrent.atomic.AtomicInteger();
        var task = new TickTask() {
            @Override public Progress poll(CompanionPlayer c, int elapsed) { polls.incrementAndGet(); return running(); }
            @Override public boolean wantsBody() { return !sleeping.get(); }
            @Override public Progress tick(CompanionPlayer c) { executions.incrementAndGet(); return running(); }
            @Override public ServerTool.Result onResume(CompanionPlayer c) { resumes.incrementAndGet(); return null; }
        };
        var future = start(task, 5);
        tick(3);
        assertEquals(3, polls.get());
        assertEquals(0, executions.get());
        sleeping.set(false);
        tick(1);
        assertEquals(1, resumes.get());
        assertEquals(1, executions.get());
        sleeping.set(true);
        tick(1);
        assertTrue(future.join().feedback().startsWith("TIMEOUT:"));
        assertEquals(5, polls.get());
        assertEquals(BodyControl.Owner.IDLE, control.owner());
    }

    private ThreatFacts.Threat threat(AttackFixtures.Access entity, boolean targeting, double x) {
        return new ThreatFacts.Threat(entity, entity.id, entity.uuid, new Vec3(x, 0, .5),
                new AABB(x - 1, 0, -.5, x + 1, 2, 1.5), 0, true, targeting, false);
    }
    private void realCombat() {
        access.combat = enemy -> new AttackTool.Task(new EntityAttack((AttackFixtures.Access) enemy.identity(), 0));
    }
    private com.google.gson.JsonObject protection(CompletableFuture<ServerTool.Result> future) {
        return future.join().data().getAsJsonObject("follow").getAsJsonObject("protection");
    }

    @Test void sleepingEscortSeesNewOwnerThreatAndUsesRealAttackThenReturnsToFollowing() {
        realCombat();
        var future = start(access.protection(30), 30);
        tick(2);
        assertEquals("sleeping", phase());
        assertEquals(0, access.fights);
        var zombie = new AttackFixtures.Access();
        zombie.effect = () -> { zombie.health = 0; zombie.alive = false; access.threats = List.of(); };
        access.threats = List.of(threat(zombie, true, 5));
        tick(1);
        assertEquals(1, zombie.strikes);
        assertEquals(1, access.fights);
        assertFalse(future.isDone());
        tick(1);
        assertEquals("sleeping", phase());
        access.position = access.body.add(8, 0, 0);
        tick(3);
        assertEquals("following", phase());
        assertEquals(1, access.plans);
        scheduler.cancel(id, "owner");
        assertEquals(1, protection(future).get("strikes").getAsInt());
        assertTrue(protection(future).getAsJsonObject("last_combat").get("target_dead").getAsBoolean());
        assertFalse(future.join().data().get("goal_satisfied").getAsBoolean());
    }

    @Test void ordinaryFollowDoesNotAcquireProtectionOrTurnHostilesIntoAnAttackOrder() {
        realCombat();
        var zombie = new AttackFixtures.Access();
        access.threats = List.of(threat(zombie, true, 4));
        var future = start(true, 4);
        tick(4);
        assertTrue(future.join().ok());
        assertEquals(0, access.scans);
        assertEquals(0, zombie.strikes);
        assertEquals(0, access.fights);
    }

    @Test void ownerAttackerTakesPriorityAndSelectedIdentityDoesNotThrashAsDistancesChange() {
        realCombat();
        var attacker = new AttackFixtures.Access();
        var passer = new AttackFixtures.Access();
        passer.id = 48;
        attacker.ready = false;
        access.threats = List.of(threat(passer, false, 2), threat(attacker, true, 6));
        var future = start(access.protection(20), 20);
        tick(1);
        assertTrue(scheduler.meleeActive(id));
        assertEquals(1, access.fights);
        access.threats = List.of(threat(passer, true, 1), threat(attacker, true, 7));
        tick(3);
        assertEquals(1, access.fights);
        assertEquals(0, passer.reservations);
        assertTrue(attacker.reservations > 0);
        scheduler.cancel(id, "owner");
        assertEquals(attacker.uuid.toString(),
                protection(future).getAsJsonObject("last_combat").get("target_uuid").getAsString());
    }

    @Test void solvingOneEnemyRescansAndStartsTheNextWithoutSettlingTheFollowCall() {
        realCombat();
        var first = new AttackFixtures.Access();
        var second = new AttackFixtures.Access();
        second.id = 48;
        first.effect = () -> {
            first.alive = false; first.health = 0;
            access.threats = List.of(threat(second, false, 4));
        };
        second.effect = () -> { second.alive = false; second.health = 0; access.threats = List.of(); };
        access.threats = List.of(threat(first, true, 3), threat(second, false, 4));
        var future = start(access.protection(8), 8);
        tick(3);
        assertEquals(1, first.strikes);
        assertEquals(1, second.strikes);
        assertEquals("sleeping", phase());
        assertFalse(future.isDone());
        tick(5);
        assertTrue(future.join().ok());
        assertEquals(2, protection(future).get("engagements").getAsInt());
        assertEquals(2, protection(future).get("strikes").getAsInt());
        assertEquals(2, protection(future).get("observed_deaths").getAsInt());
        assertEquals(2, protection(future).getAsJsonArray("combat_history").size());
        assertFalse(protection(future).get("local_clear").getAsBoolean(),
                "expiry must not turn an older observation into a current safety assertion");
        assertEquals(7, protection(future).get("observed_elapsed_ticks").getAsInt());
    }

    @Test void walkingOwnerAwayFromAFightStopsPursuitAndResumesFollowingWithTheSameAccount() {
        realCombat();
        var zombie = new AttackFixtures.Access();
        zombie.ready = false;
        access.threats = List.of(threat(zombie, true, 4));
        var future = start(access.protection(30), 30);
        tick(2);
        var budget = access.original;
        access.position = access.body.add(16, 0, 0);
        access.threats = List.of();
        tick(3);
        assertEquals("following", phase());
        assertTrue(zombie.stops > 0);
        assertEquals(1, access.plans);
        assertSame(budget, access.original);
        assertFalse(scheduler.meleeActive(id));
        scheduler.cancel(id, "owner");
        assertEquals(1, protection(future).get("disengagements").getAsInt());
    }

    @Test void reflexPreemptionPreservesCombatAndOriginalDeadlineAndCancellationReportsPartialDamage() {
        realCombat();
        var zombie = new AttackFixtures.Access();
        access.threats = List.of(threat(zombie, true, 4));
        var future = start(access.protection(6), 6);
        tick(1);
        assertEquals(1, zombie.strikes);
        control.reflex(true);
        tick(3);
        assertEquals(1, zombie.strikes);
        assertEquals(BodyControl.Owner.REFLEX, control.owner());
        control.reflex(false);
        tick(1);
        assertEquals(2, zombie.strikes);
        assertEquals(1, access.fights);
        tick(1);
        assertTrue(future.join().ok());
        assertEquals(2, protection(future).get("strikes").getAsInt());
        assertEquals(6, future.join().data().getAsJsonObject("follow").get("elapsed_ticks").getAsInt());
        tick(3);
        assertEquals(2, zombie.strikes);
    }

    @Test void failedCombatIsAnHonestTerminalWithPartialEvidenceAndNoAutomaticReplay() {
        for (String failure : List.of("UNSAFE_SWEEP:", "NO_PATH:", "DENIED:", "WRONG_TOOL:")) {
            realCombat();
            access.original = null;
            var zombie = new AttackFixtures.Access();
            zombie.guard = new ServerTool.Result(false, failure + "controlled boundary", null);
            access.threats = List.of(threat(zombie, true, 3));
            int fights = access.fights;
            var future = start(access.protection(20), 20);
            tick(1);
            assertFalse(future.join().ok());
            assertTrue(future.join().feedback().startsWith(failure), future.join().feedback());
            tick(2);
            assertEquals(fights + 1, access.fights);
            assertEquals(0, zombie.strikes);
            assertFalse(future.join().data().get("goal_satisfied").getAsBoolean());
        }
    }

    @Test void incompleteThreatScanAndFusingExplosionDoNotBecomeClaimsOfSafety() {
        realCombat();
        access.threatsComplete = false;
        var unknown = start(access.protection(20), 20);
        tick(1);
        assertTrue(unknown.join().feedback().startsWith("UNKNOWN:"));
        assertFalse(protection(unknown).get("local_clear").getAsBoolean());
        access.threatsComplete = true;
        access.threats = List.of(new ThreatFacts.Threat(new Object(), 99, UUID.randomUUID(),
                access.position, new AABB(0, 0, 0, 1, 2, 1), 6, true));
        var blast = start(access.protection(20), 20);
        tick(1);
        assertTrue(blast.join().feedback().startsWith("UNSAFE_THREAT:"));
        assertEquals(0, access.fights);
    }

    @Test void ownerAbsenceAndCancelStopLiveCombatWithoutRestoringOrAttackingAReplacement() {
        realCombat();
        var zombie = new AttackFixtures.Access();
        zombie.ready = false;
        access.threats = List.of(threat(zombie, true, 3));
        var future = start(access.protection(20), 20);
        tick(1);
        access.present = false;
        tick(1);
        assertEquals("waiting_owner", phase());
        assertTrue(zombie.stops > 0);
        scheduler.cancel(id, "owner");
        access.present = true;
        tick(3);
        assertEquals(1, access.fights);
        assertTrue(future.join().feedback().startsWith("CANCELLED:"));
        assertFalse(future.join().data().get("automatic_resume").getAsBoolean());
    }

    @Test void movingOwnerCanKeepUsefulRouteOrRetargetInPlaceWithoutRepeatedAborts() {
        access.position = access.body.add(8, 0, 0);
        access.usefulRoute = true;
        var future = start(true, 100);
        tick(3);
        access.position = access.position.add(3, 0, 0);
        tick(15);
        assertEquals(1, access.plans);
        assertEquals(0, access.retargets);
        access.usefulRoute = false;
        access.keepRoute = true;
        tick(1);
        assertEquals(1, access.retargets);
        for (int i = 0; i < 3; i++) {
            access.position = access.position.add(3, 0, 0);
            tick(10);
        }
        assertEquals(1, access.plans);
        assertEquals(4, access.retargets);
        assertEquals(0, access.aborts);
        scheduler.cancel(id, "owner");
        assertTrue(future.isDone());
    }

    @Test void cancellingAnInterruptedFightKeepsActualDamageOnceAndNeverStrikesAfterCancellation() {
        realCombat();
        var zombie = new AttackFixtures.Access();
        access.threats = List.of(threat(zombie, true, 3));
        var future = start(access.protection(30), 30);
        tick(2);
        control.reflex(true);
        scheduler.cancel(id, "owner");
        assertTrue(future.join().feedback().startsWith("CANCELLED:"));
        assertEquals(2, protection(future).get("strikes").getAsInt());
        assertEquals(6, protection(future).get("observed_health_loss").getAsFloat());
        assertEquals(1, protection(future).getAsJsonArray("combat_history").size());
        control.reflex(false);
        tick(4);
        assertEquals(2, zombie.strikes);
    }
}
