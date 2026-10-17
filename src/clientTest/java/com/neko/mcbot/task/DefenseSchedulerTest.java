package com.neko.mcbot.task;

import com.neko.mcbot.body.BodyControl;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.ServerTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class DefenseSchedulerTest {
    private final CompanionScheduler scheduler = new CompanionScheduler();
    private final UUID body = UUID.randomUUID(), foe = UUID.randomUUID();
    private final ResourceLocks.Region region = new ResourceLocks.Region("world", 0, 0, 0, 3, 3, 3);
    private final ResourceLocks.Target target = new ResourceLocks.Target("world", foe);
    private final BodyControl control = new BodyControl(previous -> {
        if (previous == BodyControl.Owner.WORK) scheduler.preempt(body);
    });
    private final AtomicBoolean valid = new AtomicBoolean(true);
    private ServerTool.Result environmentFailure;
    private final CompanionScheduler.Bodies bodies = new CompanionScheduler.Bodies() {
        @Override public CompanionPlayer get(UUID id) { return null; }
        @Override public boolean same(CompanionPlayer old, CompanionPlayer fresh) { return true; }
        @Override public ServerTool.Result environmentFailure(CompanionPlayer ignored) {
            return environmentFailure;
        }
    };

    private final class Work extends TickTask {
        int ticks, suspended, resumed;
        @Override public Progress tick(CompanionPlayer ignored) { ticks++; return running(); }
        @Override public void onSuspend() { suspended++; }
        @Override public ServerTool.Result onResume(CompanionPlayer ignored) {
            resumed++;
            return valid.get() ? null : new ServerTool.Result(false, "DENIED:changed", null);
        }
    }
    private CompletableFuture<ServerTool.Result> start(Work task, int cap) {
        return scheduler.execute(body, List.of(region), () -> scheduler.controlled(body, control, valid::get, () -> {
            var future = new CompletableFuture<ServerTool.Result>();
            assertTrue(scheduler.submit(body, null, task, future, cap));
            return future;
        }));
    }

    @Test void reflexBorrowingPreservesWorkAndOtherBodiesCannotStealItsReservations() {
        var work = new Work();
        var receipt = start(work, 40);
        scheduler.tick(bodies);
        var lease = scheduler.reflexLease(body);
        assertNotNull(lease);
        control.reflex(true);
        assertTrue(lease.reserve(target, region));
        scheduler.tick(bodies);
        assertEquals(1, work.ticks);
        assertEquals(1, work.suspended);
        lease.close();
        assertFalse(lease.reserve(target, region));
        var other = scheduler.reflexLease(UUID.randomUUID());
        assertNotNull(other);
        assertFalse(other.reserve(target, region), "closing the reflex cannot release suspended work reservations");
        other.close();
        control.reflex(false);
        scheduler.tick(bodies);
        assertEquals(1, work.resumed);
        assertEquals(2, work.ticks);
        assertEquals(3, scheduler.observation(body).get("elapsed_ticks").getAsInt());
        scheduler.cancel(body, "owner");
        assertTrue(receipt.join().feedback().startsWith("CANCELLED:"));
    }

    @Test void suspensionAndCancellationDoNotBuyNewWorkTimeOrResumeRevokedIdentity() {
        var work = new Work();
        var receipt = start(work, 3);
        scheduler.tick(bodies);
        control.reflex(true);
        scheduler.tick(bodies);
        valid.set(false);
        scheduler.tick(bodies);
        assertTrue(receipt.join().feedback().startsWith("DENIED:"));
        control.reflex(false);
        scheduler.tick(bodies);
        assertEquals(1, work.ticks);
        assertEquals(0, work.resumed);
        assertFalse(scheduler.busy(body));
    }

    @Test void idleReflexOwnsAnExclusiveLeaseAndReleasesItWhenStopped() {
        var lease = scheduler.reflexLease(body);
        assertNotNull(lease);
        assertNull(scheduler.reflexLease(body));
        assertTrue(lease.reserve(target, region));
        var other = scheduler.reflexLease(UUID.randomUUID());
        assertNotNull(other);
        assertFalse(other.reserve(target, region));
        lease.close();
        lease.close();
        assertTrue(other.reserve(target, region));
        other.close();
        assertNotNull(scheduler.reflexLease(body));
    }

    @Test void endingOriginalWorkInvalidatesBorrowedAuthorityBeforeAnotherReflexEffect() {
        var work = new Work();
        var receipt = start(work, 2);
        var lease = scheduler.reflexLease(body);
        assertTrue(lease.valid());
        control.reflex(true);
        scheduler.tick(bodies);
        scheduler.tick(bodies);
        assertTrue(receipt.join().feedback().startsWith("TIMEOUT:"));
        assertFalse(lease.valid());
        assertFalse(lease.reserve(target, region));
        lease.close();
        control.reflex(false);
    }

    @Test void cancelAllIncludesIdleReflexAndReleasesTheOriginalEntityLease() {
        var lease = scheduler.reflexLease(body);
        assertTrue(lease.reserve(target, region));
        scheduler.cancelAll("shutdown");
        assertFalse(lease.valid());
        assertFalse(scheduler.cancel(body, "already stopped"));
        var other = scheduler.reflexLease(UUID.randomUUID());
        assertNotNull(other);
        assertTrue(other.reserve(target, region));
        other.close();
    }

    @Test void exhaustedEnvironmentRecoveryStopsOriginalWorkBeforeResumeOrAnotherAction() {
        var work = new Work();
        var receipt = start(work, 40);
        scheduler.tick(bodies);
        var lease = scheduler.reflexLease(body);
        control.reflex(true);
        scheduler.tick(bodies);
        lease.close();
        control.reflex(false);
        environmentFailure = new ServerTool.Result(false, "ENVIRONMENT_UNSAFE:original rescue limit", null);
        scheduler.tick(bodies);
        assertTrue(receipt.join().feedback().startsWith("ENVIRONMENT_UNSAFE:"));
        assertEquals(1, work.ticks);
        assertEquals(0, work.resumed);
        assertFalse(scheduler.busy(body));
        assertEquals(BodyControl.Owner.IDLE, control.owner());
    }

    @Test void environmentPauseCannotExtendDeadlineAndBorrowedAuthorityDiesWithTheOriginalWork() {
        var work = new Work();
        var receipt = start(work, 3);
        var lease = scheduler.reflexLease(body);
        control.reflex(true);
        scheduler.tick(bodies);
        scheduler.tick(bodies);
        scheduler.tick(bodies);
        assertTrue(receipt.join().feedback().startsWith("TIMEOUT:"));
        assertFalse(lease.valid());
        assertEquals(0, work.ticks);
        control.reflex(false);
        scheduler.tick(bodies);
        assertEquals(0, work.resumed);
        lease.close();
    }
}
