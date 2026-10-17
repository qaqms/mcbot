package com.neko.mcbot.server;

import com.neko.mcbot.body.BodyControl;
import com.neko.mcbot.task.CompanionScheduler;
import com.neko.mcbot.task.TickTask;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class BodyMaintenanceTest {
    @BeforeAll static void bootstrap() { BlockActionFixtures.bootstrap(); }
    private static class Access implements BodyMaintenance.Access {
        final ConsumptionWorkTest.Access nativeUse = new ConsumptionWorkTest.Access();
        final ActionPermissions permissions = new ActionPermissions();
        final CompanionScheduler scheduler = new CompanionScheduler();
        final UUID owner = UUID.randomUUID(), id = UUID.randomUUID();
        final Object body = new Object();
        Object world = new Object();
        final BodyControl control = new BodyControl(previous -> { });
        ActionPermissions.BodyPermit permit;
        CompanionScheduler.ReflexLease lease;
        BodyMaintenance maintenance;
        boolean allowed = true, higher, valid = true, granted = true, armor, throwRelease;
        int acquisitions, releases, equips, consumes;
        Runnable duringAcquire = () -> { };
        Access() { nativeUse.finishAt = 1; }
        BodyMaintenance.Frame frame() { return new BodyMaintenance.Frame(world, allowed, higher, nativeUse.food); }
        BodyMaintenance create() { return maintenance = new BodyMaintenance(this); }
        @Override public BodyMaintenance.Candidate candidate(boolean food, Set<String> attempted) {
            if (food) {
                ItemStack stack = nativeUse.inventory.getItem(13);
                if (MaintenanceDriver.automaticFood(stack)) return new BodyMaintenance.Candidate(13, "consume", stack);
                return null;
            }
            return armor && !attempted.contains("head")
                    ? new BodyMaintenance.Candidate(12, "head", new ItemStack(Items.IRON_HELMET)) : null;
        }
        @Override public boolean acquire() {
            acquisitions++;
            if (!granted) return false;
            permit = permissions.bodyPermit(owner, id, "world", body);
            if (permit == null) return false;
            lease = scheduler.reflexLease(id);
            if (lease == null) return false;
            control.reflex(true);
            duringAcquire.run();
            return true;
        }
        @Override public boolean valid() { return valid && permit != null && permit.valid() && lease != null && lease.valid(); }
        @Override public TickTask consume(BodyMaintenance.Candidate candidate) {
            consumes++;
            return new ConsumptionWork(nativeUse, candidate.source(), candidate.expected());
        }
        @Override public ServerTool.Result equip(BodyMaintenance.Candidate candidate) {
            equips++;
            armor = false;
            return new ServerTool.Result(true, "equipped", null);
        }
        @Override public void release() {
            releases++;
            try { if (lease != null) lease.close(); }
            finally { lease = null; permit = null; control.reflex(false); }
            if (throwRelease) throw new IllegalStateException("cleanup");
        }
        void ticks(int count) { for (int i = 0; i < count; i++) maintenance.tick(frame()); }
    }
    @Test void continuousHungerUsesTheSameNativeWorkAndStopsAtActualFedCondition() {
        var a = new Access();
        var m = a.create();
        a.ticks(30);
        assertTrue(a.nativeUse.food >= 10);
        assertTrue(a.nativeUse.starts >= 2);
        assertTrue(m.observation().get("consumed_count").getAsInt() >= 1);
        assertEquals(1, a.acquisitions);
        assertTrue(m.active() || m.observation().get("state").getAsString().equals("local_done"));
        assertTrue(a.nativeUse.inventory.getSelectedItem().is(Items.IRON_PICKAXE)
                || a.nativeUse.inventory.getSelectedItem().is(Items.BREAD));
        assertFalse(m.observation().get("goal_satisfied").getAsBoolean());
    }
    @Test void readonlyAbsentOwnerUnknownThreatAndEnvironmentalPriorityDoNotAcquire() {
        for (int mode = 0; mode < 3; mode++) {
            var a = new Access();
            if (mode == 0) a.allowed = false;
            if (mode == 1) a.higher = true;
            if (mode == 2) a.permissions.begin(new ActionPermissions.Context(a.owner, a.id, "world", 1, true), a.body);
            a.create();
            a.ticks(100);
            assertEquals(0, a.nativeUse.starts);
            assertFalse(a.maintenance.active());
            assertEquals(0, a.equips);
        }
    }
    @Test void interruptionRestoresOriginalHandBeforeTheHigherPriorityActionAndNeverReeats() {
        var a = new Access();
        var m = a.create();
        a.ticks(2);
        assertTrue(a.nativeUse.using);
        m.preempt();
        assertFalse(a.nativeUse.using);
        assertTrue(a.nativeUse.inventory.getSelectedItem().is(Items.IRON_PICKAXE));
        assertEquals(0, a.nativeUse.ticks);
        m.newTask();
        a.ticks(300);
        assertEquals(1, a.nativeUse.starts);
        assertEquals(1, a.acquisitions);
    }
    @Test void noFoodOrNativeRefusalDoesNotRetryAfterATaskOrProgramChange() {
        for (boolean absent : new boolean[]{false, true}) {
            var a = new Access();
            if (absent) a.nativeUse.inventory.setItem(13, ItemStack.EMPTY);
            else a.nativeUse.accepted = false;
            var m = a.create();
            a.ticks(60);
            m.newTask();
            a.nativeUse.inventory.setItem(13, new ItemStack(Items.BREAD, 4));
            a.nativeUse.accepted = true;
            a.ticks(100);
            assertEquals(absent ? 0 : 1, a.nativeUse.starts);
            a.nativeUse.food = 14;
            a.ticks(20);
            a.nativeUse.food = 6;
            a.ticks(60);
            assertTrue(a.nativeUse.starts > (absent ? 0 : 1));
        }
    }
    @Test void cancellationLocksFoodAndArmorUntilNewTaskWithoutClearingTheFailedEpisode() {
        var a = new Access();
        var m = a.create();
        a.ticks(2);
        m.cancel();
        a.armor = true;
        a.nativeUse.food = 20;
        a.ticks(60);
        assertEquals(0, a.equips);
        m.newTask();
        a.ticks(21);
        assertEquals(1, a.equips);
    }
    @Test void borrowedOriginalLeaseLossNeverContinuesUsingIndependentIdleAuthority() {
        var a = new Access();
        var original = new CompletableFuture<ServerTool.Result>();
        a.scheduler.execute(a.id, java.util.List.of(), () -> original);
        var m = a.create();
        a.ticks(2);
        original.complete(new ServerTool.Result(false, "cancel", null));
        a.ticks(1);
        assertFalse(m.active());
        assertEquals("authority_lost", m.observation().get("state").getAsString());
        m.newTask();
        a.ticks(100);
        assertEquals(1, a.acquisitions);
        assertEquals(0, a.nativeUse.ticks);
    }
    @Test void replacedTaskOrWorldInvalidatesFrozenAuthorityBeforeTheNextNativeTick() {
        for (boolean world : new boolean[]{false, true}) {
            var a = new Access();
            a.permissions.begin(new ActionPermissions.Context(a.owner, a.id, "world", 1, false), a.body);
            var m = a.create();
            a.ticks(2);
            if (world) a.world = new Object();
            else a.permissions.begin(new ActionPermissions.Context(a.owner, a.id, "world", 1, false), a.body);
            a.ticks(1);
            assertFalse(m.active());
            assertFalse(a.nativeUse.using);
            assertEquals(0, a.nativeUse.ticks);
        }
    }
    @Test void failedArmorAcquisitionIsNotReissuedEveryPollOrAfterNewTask() {
        var a = new Access();
        a.nativeUse.food = 20;
        a.armor = true;
        a.granted = false;
        var m = a.create();
        a.ticks(100);
        m.newTask();
        a.ticks(100);
        assertEquals(1, a.acquisitions);
        m.equipmentBroken("head");
        a.ticks(21);
        assertEquals(2, a.acquisitions);
    }
    @Test void reentrantCancellationDuringAcquisitionDoesNotStartUseOrLeakAReflexLease() {
        var a = new Access();
        var m = a.create();
        a.duringAcquire = m::cancel;
        a.ticks(1);
        assertFalse(m.active());
        assertEquals(0, a.nativeUse.starts);
        assertEquals(BodyControl.Owner.IDLE, a.control.owner());
        var other = a.scheduler.reflexLease(a.id);
        assertNotNull(other);
        other.close();
    }
    @Test void lifetimeEpisodeLimitCannotBeResetByNewTasksOrHungerRecovery() {
        var a = new Access();
        var m = a.create();
        for (int episode = 0; episode < 66; episode++) {
            a.nativeUse.inventory.setItem(13, new ItemStack(Items.BREAD, 4));
            a.nativeUse.food = 5;
            a.ticks(35);
            a.nativeUse.food = 20;
            a.ticks(25);
            m.newTask();
        }
        assertTrue(a.acquisitions <= 64);
        assertEquals(a.acquisitions, m.observation().get("episodes").getAsInt());
    }
    @Test void slowNativeUseCannotExtendTheOriginalEpisodeDeadlineOrConsumptionQuota() {
        var a = new Access();
        a.nativeUse.finishAt = 100;
        a.nativeUse.inventory.setItem(13, new ItemStack(Items.BREAD, 10));
        a.nativeUse.food = 0;
        var m = a.create();
        a.ticks(300);
        assertFalse(m.active());
        assertTrue(m.observation().get("elapsed_ticks").getAsInt() <= 240);
        assertTrue(a.nativeUse.starts <= 4);
        m.newTask();
        a.ticks(300);
        assertEquals(1, a.acquisitions);
    }
}
