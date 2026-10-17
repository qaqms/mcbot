package com.neko.mcbot.server;

import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.path.NavigationBudget;
import com.neko.mcbot.server.tools.AttackTool;
import com.neko.mcbot.task.TickTask;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BodyDefenseTest {
    @BeforeAll static void registries() { BlockActionFixtures.bootstrap(); }

    private static class Access implements BodyDefense.Access {
        final Object world = new Object(), identity = new Object();
        final AttackFixtures.Access nativeAttack = new AttackFixtures.Access();
        boolean allowed = true, valid = true, water, melee, armed = true, complete = true, granted = true;
        boolean sourceAllowed = true, explosive;
        float health = 20;
        int attacks, retreats, claims, releases, advances, resumes, aborts, havens;
        NavigationBudget original;
        Vec3 position = new Vec3(.5, 0, .5), foe = new Vec3(1.5, 0, .5);
        Object source = identity;
        BlockPos destination = new BlockPos(-3, 0, 0);
        TickTask.Progress retreatResult = TickTask.running();
        ServerTool.Result resumeFailure;
        List<ThreatFacts.Threat> explicit;
        ThreatFacts.Threat threat() {
            return new ThreatFacts.Threat(source, nativeAttack.id, nativeAttack.uuid, foe,
                    new AABB(0, 0, 0, 3, 2, 2), explosive ? 6 : 0, sourceAllowed);
        }
        BodyDefense.Frame frame() {
            return frame(world);
        }
        BodyDefense.Frame frame(Object freshWorld) {
            return new BodyDefense.Frame(freshWorld, allowed, water, melee, position,
                    new AABB(position.x - .3, 0, position.z - .3, position.x + .3, 1.8, position.z + .3),
                    health, 20, armed, new ThreatFacts.Snapshot(explicit == null ? List.of(threat()) : explicit, complete));
        }
        @Override public boolean acquire(BlockPos anchor) { claims++; original = null; return granted; }
        @Override public boolean valid() { return valid; }
        private void budget(NavigationBudget budget) {
            if (original == null) original = budget;
            else assertSame(original, budget, "mode switches and resumptions cannot create a fresh search account");
        }
        @Override public TickTask attack(ThreatFacts.Threat threat, int maxHits, boolean approach, NavigationBudget budget) {
            attacks++;
            budget(budget);
            nativeAttack.approach = approach;
            return new AttackTool.Task(new EntityAttack(nativeAttack, maxHits));
        }
        @Override public BlockPos haven(BodyDefense.Frame frame, BlockPos anchor) { havens++; return destination; }
        @Override public TickTask retreat(BlockPos haven, NavigationBudget budget) {
            assertEquals(destination, haven);
            retreats++;
            budget(budget);
            return new TickTask() {
                @Override public Progress tick(CompanionPlayer body) { return retreatResult; }
                @Override public void onAbort() { aborts++; }
                @Override public ServerTool.Result onResume(CompanionPlayer body) { resumes++; return resumeFailure; }
            };
        }
        @Override public TickTask.Progress advance(TickTask task) { advances++; return task.tickSafe(null); }
        @Override public ServerTool.Result resume(TickTask task) { return task.onResume(null); }
        @Override public void release() { releases++; }
        @Override public void stopInput() { }
    }

    @Test void nearbyWithoutCredibleEvidenceDoesNotCreateDanger() {
        assertFalse(ThreatFacts.credible(false, false, false));
        assertTrue(ThreatFacts.credible(true, false, false));
        assertTrue(ThreatFacts.credible(false, true, false));
        assertTrue(ThreatFacts.credible(false, false, true));
        var a = new Access();
        a.explicit = List.of();
        var defense = new BodyDefense(a);
        defense.tick(a.frame());
        assertEquals(0, a.claims);
    }

    @Test void readonlyUnknownAndWaterPriorityNeverStartAnAction() {
        for (int mode = 0; mode < 3; mode++) {
            var a = new Access();
            if (mode == 0) a.allowed = false;
            if (mode == 1) a.complete = false;
            if (mode == 2) a.water = true;
            var defense = new BodyDefense(a);
            defense.tick(a.frame());
            assertEquals(0, a.claims);
            assertEquals(0, a.nativeAttack.strikes);
        }
    }

    @Test void nativeMeleeIsSharedAndFailedIdentityCannotRestartOrChangeTarget() {
        var a = new Access();
        a.nativeAttack.effect = () -> a.nativeAttack.health--;
        var defense = new BodyDefense(a);
        for (int i = 0; i < 500; i++) defense.tick(a.frame());
        assertEquals(10, a.nativeAttack.strikes);
        assertEquals(1, a.attacks);
        assertEquals(1, a.claims);
        assertFalse(defense.active());
        assertEquals(10, defense.observation().get("strikes").getAsInt());
        assertEquals(1, a.releases);
    }

    @Test void existingExplicitMeleeKeepsControlUnlessRiskRequiresRetreat() {
        var a = new Access();
        a.melee = true;
        var defense = new BodyDefense(a);
        defense.tick(a.frame());
        assertEquals(0, a.claims);
        a.health = 5;
        defense.tick(a.frame());
        assertEquals(1, a.retreats);
        assertEquals(0, a.attacks);
    }

    @Test void actualRecentRangedAttackerTriggersRetreatWithoutInventingAReachableStrike() {
        var a = new Access();
        var distant = new ThreatFacts.Threat(a.identity, 47, a.nativeAttack.uuid, new Vec3(9, 0, 0),
                new AABB(8, 0, -1, 10, 2, 1), 0, true, true, true);
        a.explicit = List.of(distant);
        var defense = new BodyDefense(a);
        defense.tick(a.frame());
        assertEquals(1, a.retreats);
        assertEquals(0, a.attacks);
        defense.cancel();
    }

    @Test void arrivedRetreatNeverReissuesOldHavenWhenFreshObservationStillFindsDanger() {
        var a = new Access();
        a.health = 4;
        a.retreatResult = new TickTask.Progress.Done(new ServerTool.Result(true, "at haven", null));
        var defense = new BodyDefense(a);
        defense.tick(a.frame());
        assertTrue(defense.active());
        defense.tick(a.frame());
        assertFalse(defense.active());
        assertEquals(1, a.retreats);
        assertEquals("local_blocked", defense.observation().get("state").getAsString());
    }

    @Test void protectedNamedNeutralAndExplosiveSourcesOnlyRetreat() {
        for (boolean explosive : new boolean[]{false, true}) {
            var a = new Access();
            a.sourceAllowed = false;
            a.explosive = explosive;
            a.retreatResult = new TickTask.Progress.Done(new ServerTool.Result(false, "NO_PATH:fixture", null));
            var defense = new BodyDefense(a);
            for (int i = 0; i < 30; i++) defense.tick(a.frame());
            assertEquals(1, a.retreats);
            assertEquals(0, a.attacks);
            assertEquals(0, a.nativeAttack.strikes);
            assertFalse(defense.active());
        }
    }

    @Test void unsafeNeighborExplosionPreventsCorneredAttackAndHardRouteDenialCannotFallBack() {
        var a = new Access();
        var blast = new ThreatFacts.Threat(new Object(), 99, UUID.randomUUID(), Vec3.ZERO,
                new AABB(0, 0, 0, 1, 2, 1), 6, false);
        a.explicit = List.of(a.threat(), blast);
        a.health = 4;
        a.destination = null;
        var defense = new BodyDefense(a);
        defense.tick(a.frame());
        assertFalse(defense.active());
        assertEquals(0, a.attacks);
        a = new Access();
        a.health = 4;
        a.retreatResult = new TickTask.Progress.Done(new ServerTool.Result(false, "DENIED:permission changed", null));
        defense = new BodyDefense(a);
        defense.tick(a.frame());
        defense.tick(a.frame());
        assertFalse(defense.active());
        assertEquals(0, a.attacks);
    }

    @Test void selectedRetreatDestinationStaysStableDespiteThreatMotion() {
        var a = new Access();
        a.health = 4;
        var defense = new BodyDefense(a);
        for (int i = 0; i < 50; i++) {
            a.foe = a.foe.add(.1, 0, .1);
            defense.tick(a.frame());
        }
        assertEquals(1, a.havens);
        assertEquals(1, a.retreats);
        assertEquals(0, a.attacks);
        defense.cancel();
    }

    @Test void corneredFallbackUsesRemainingNativeStrikeAllowanceWithoutMovement() {
        var a = new Access();
        a.health = 3;
        a.destination = null;
        var defense = new BodyDefense(a);
        defense.tick(a.frame());
        assertEquals(0, a.attacks);
        defense.tick(a.frame());
        assertEquals(1, a.attacks);
        assertFalse(a.nativeAttack.approach);
        assertEquals(1, a.nativeAttack.strikes);
        defense.cancel();
    }

    @Test void changingRiskTransfersTheSameBudgetAndPreservesEarlierNativeEffects() {
        var a = new Access();
        var defense = new BodyDefense(a);
        defense.tick(a.frame());
        assertEquals(1, a.nativeAttack.strikes);
        a.health = 4;
        defense.tick(a.frame());
        assertEquals(1, a.attacks);
        assertEquals(1, a.retreats);
        assertEquals(1, defense.observation().get("strikes").getAsInt());
        defense.cancel();
        assertEquals(1, defense.observation().getAsJsonObject("last_result").get("episode_strikes").getAsInt());
    }

    @Test void waterSuspensionCountsOriginalDeadlineAndCannotEmitAnotherStrike() {
        var a = new Access();
        a.nativeAttack.ready = false;
        var defense = new BodyDefense(a);
        defense.tick(a.frame());
        a.water = true;
        for (int i = 1; i < BodyDefense.CAP_TICKS; i++) defense.tick(a.frame());
        assertFalse(defense.active());
        assertEquals(1, a.advances);
        assertEquals("timeout", defense.observation().get("state").getAsString());
        assertEquals(400, defense.observation().get("elapsed_ticks").getAsInt());
        a.water = false;
        defense.tick(a.frame());
        assertEquals(1, a.claims);
    }

    @Test void resumeRechecksOriginalActionAndDoesNotResetElapsedOrBudget() {
        var a = new Access();
        a.health = 4;
        var defense = new BodyDefense(a);
        defense.tick(a.frame());
        a.water = true;
        defense.tick(a.frame());
        a.water = false;
        defense.tick(a.frame());
        assertEquals(1, a.resumes);
        assertEquals(3, defense.observation().get("elapsed_ticks").getAsInt());
        assertEquals(1, a.retreats);
        a.water = true;
        defense.tick(a.frame());
        a.resumeFailure = new ServerTool.Result(false, "SCOPE_LIMIT:changed", null);
        a.water = false;
        defense.tick(a.frame());
        assertFalse(defense.active());
        assertEquals(2, a.advances);
    }

    @Test void cancelLatchesAndNewTaskCannotBuyTheOldSourceAnotherAttempt() {
        var a = new Access();
        var defense = new BodyDefense(a);
        defense.tick(a.frame());
        defense.cancel();
        a.source = new Object();
        for (int i = 0; i < 30; i++) defense.tick(a.frame());
        assertEquals(1, a.claims);
        defense.newTask();
        defense.tick(a.frame());
        assertEquals(2, a.claims);
        defense.cancel();
        a.source = a.identity;
        defense.newTask();
        defense.tick(a.frame());
        assertEquals(2, a.claims);
    }

    @Test void bodyWorldAuthorityAndUnknownChangesStopBeforeDependentEffects() {
        for (int change = 0; change < 4; change++) {
            var a = new Access();
            var defense = new BodyDefense(a);
            defense.tick(a.frame());
            if (change == 0) a.allowed = false;
            if (change == 1) a.valid = false;
            if (change == 2) a.complete = false;
            defense.tick(change == 3 ? a.frame(new Object()) : a.frame());
            assertFalse(defense.active());
            assertEquals(1, a.nativeAttack.strikes);
            assertEquals(1, a.releases);
        }
    }

    @Test void reusedIdAndUuidWithDifferentObjectNeverReplacesCommittedSource() {
        var a = new Access();
        var defense = new BodyDefense(a);
        defense.tick(a.frame());
        a.source = new Object();
        defense.tick(a.frame());
        assertFalse(defense.active());
        assertEquals(1, a.nativeAttack.strikes);
        assertEquals("target_lost", defense.observation().get("state").getAsString());
    }

    @Test void departureNeedsContinuousFreshCalmAndDoesNotClaimPursuersGone() {
        var a = new Access();
        var defense = new BodyDefense(a);
        defense.tick(a.frame());
        a.explicit = List.of();
        for (int i = 1; i < BodyDefense.CALM_TICKS; i++) {
            defense.tick(a.frame());
            assertTrue(defense.active());
        }
        defense.tick(a.frame());
        assertFalse(defense.active());
        assertEquals("local_clear", defense.observation().get("state").getAsString());
        assertEquals(1, a.nativeAttack.strikes);
    }

    @Test void sourceLedgerIsBoundedAndNeverEvictsAnOldFailure() {
        var a = new Access();
        a.nativeAttack.effect = () -> {};
        var defense = new BodyDefense(a);
        for (int i = 0; i < BodyDefense.SOURCE_LIMIT + 2; i++) {
            a.source = new Object();
            defense.tick(a.frame());
        }
        assertEquals(64, a.claims);
        assertEquals(64, a.attacks);
        assertEquals("source_limit", defense.observation().get("state").getAsString());
    }
}
