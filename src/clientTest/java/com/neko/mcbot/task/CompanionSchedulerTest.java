package com.neko.mcbot.task;

import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.ServerTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CompanionSchedulerTest {
    private final CompanionScheduler scheduler = new CompanionScheduler();
    private final UUID body = UUID.randomUUID();
    private final ResourceLocks.Region area = new ResourceLocks.Region("world", 0, 0, 0, 2, 2, 2);
    private final CompanionScheduler.Bodies present = bodies(true);

    private static CompanionScheduler.Bodies bodies(boolean present) {
        return new CompanionScheduler.Bodies() {
            @Override public CompanionPlayer get(UUID id) { return null; }
            @Override public boolean same(CompanionPlayer expected, CompanionPlayer fresh) { return present; }
        };
    }

    private static final class Task extends TickTask {
        int ticks, cleanups, suspends, resumes;
        boolean resumeAllowed = true;
        boolean done, throwsTick, throwsCleanup;
        Activity activity;
        @Override public Activity activity() { return activity; }
        @Override public Progress tick(CompanionPlayer companion) {
            ticks++;
            if (throwsTick) throw new IllegalStateException("offline");
            return done ? new Progress.Done(new ServerTool.Result(true, "done", null)) : running();
        }
        @Override public void onAbort() {
            cleanups++;
            if (throwsCleanup) throw new IllegalStateException("offline cleanup");
        }
        @Override public void onSuspend() { suspends++; }
        @Override public ServerTool.Result onResume(CompanionPlayer companion) {
            resumes++;
            return resumeAllowed ? null : new ServerTool.Result(false, "TARGET_LOST:changed", null);
        }
    }

    private CompletableFuture<ServerTool.Result> start(Task task, int cap) {
        var future = new CompletableFuture<ServerTool.Result>();
        var receipt = scheduler.execute(body, List.of(area), () -> {
            assertTrue(scheduler.submit(body, null, task, future, cap));
            return future;
        });
        assertFalse(receipt.isDone());
        return receipt;
    }

    @Test void activityTransitionsAreCoalescedAndOldSlotsCannotReportAfterCancellationOrReplacement() {
        var task = new Task();
        task.activity = TickTask.Activity.SEARCH;
        var first = start(task, 100);
        var progress = new java.util.ArrayList<TickTask.Activity>();
        scheduler.watch(body, progress::add);
        assertEquals(List.of(TickTask.Activity.SEARCH), progress);
        task.activity = TickTask.Activity.DIG;
        for (int i = 0; i < 4; i++) scheduler.tick(present);
        assertEquals(1, progress.size());
        scheduler.tick(present);
        assertEquals(List.of(TickTask.Activity.SEARCH, TickTask.Activity.DIG), progress);
        for (int i = 0; i < 10; i++) scheduler.tick(present);
        assertEquals(2, progress.size(), "unchanged phases must not spam the wire or history");
        task.activity = TickTask.Activity.MOVE;
        scheduler.tick(present);
        assertEquals(TickTask.Activity.MOVE, progress.getLast());
        scheduler.cancel(body, "owner");
        assertTrue(first.isDone());
        var next = new Task();
        next.activity = TickTask.Activity.SEARCH;
        var second = start(next, 100);
        for (int i = 0; i < 10; i++) scheduler.tick(present);
        assertEquals(3, progress.size());
        next.done = true;
        scheduler.tick(present);
        assertTrue(second.join().ok());
    }

    @Test void displayObserverFailureCannotStopThePhysicalTaskOrChangeItsResult() {
        var task = new Task();
        task.activity = TickTask.Activity.SEARCH;
        var result = start(task, 100);
        scheduler.watch(body, activity -> { throw new IllegalStateException("display offline"); });
        scheduler.tick(present);
        task.done = true;
        scheduler.tick(present);
        assertTrue(result.join().ok());
        assertEquals(2, task.ticks);
    }

    private void freeAfter(CompletableFuture<ServerTool.Result> receipt, Task task, String prefix) {
        assertTrue(receipt.join().feedback().startsWith(prefix));
        assertFalse(scheduler.busy(body));
        assertEquals(1, task.cleanups);
        assertTrue(scheduler.execute(UUID.randomUUID(), List.of(area), () -> CompletableFuture.completedFuture(
                new ServerTool.Result(true, "next", null))).join().ok());
    }

    @Test void normalTerminalReleasesWorldAndBodyBeforeCallbacks() {
        var task = new Task();
        var receipt = start(task, 20);
        var denied = scheduler.execute(UUID.randomUUID(), List.of(area),
                () -> { fail("occupied region"); return null; }).join();
        assertTrue(denied.feedback().startsWith("BUSY:"));
        task.done = true;
        AtomicInteger callbacks = new AtomicInteger();
        receipt.thenRun(() -> {
            assertFalse(scheduler.busy(body));
            callbacks.incrementAndGet();
        });
        scheduler.tick(present);
        freeAfter(receipt, task, "done");
        scheduler.tick(present);
        assertEquals(1, callbacks.get());
        assertEquals(1, task.ticks);
    }

    @Test void observationShowsActualAgeAndClearsAtTerminalWithoutGuessingPercent() {
        var task = new Task();
        assertFalse(scheduler.observation(body).get("busy").getAsBoolean());
        var result = start(task, 20);
        scheduler.tick(present);
        var observation = scheduler.observation(body);
        assertTrue(observation.get("busy").getAsBoolean());
        assertEquals(1, observation.get("elapsed_ticks").getAsInt());
        assertEquals(20, observation.get("cap_ticks").getAsInt());
        assertFalse(observation.has("percent"));
        task.done = true;
        scheduler.tick(present);
        assertTrue(result.join().ok());
        assertFalse(scheduler.observation(body).get("busy").getAsBoolean());
        assertFalse(scheduler.observation(body).has("progress"));
    }

    @Test void exceptionCleansEvenIfCleanupAlsoThrows() {
        var task = new Task();
        task.throwsTick = task.throwsCleanup = true;
        var receipt = start(task, 20);
        scheduler.tick(present);
        freeAfter(receipt, task, "INTERNAL:");
    }

    @Test void bodyLossOrReplacementUsesAbortAndNeverTicksOldBody() {
        var task = new Task();
        var receipt = start(task, 20);
        scheduler.tick(bodies(false));
        freeAfter(receipt, task, "TARGET_LOST:");
        assertEquals(0, task.ticks);
    }

    @Test void deathStopsBeforePollOrAnyDormantWorkCanWake() {
        var polls = new AtomicInteger();
        var task = new TickTask() {
            @Override public Progress poll(CompanionPlayer companion, int elapsed) {
                polls.incrementAndGet(); return running();
            }
            @Override public boolean wantsBody() { return false; }
            @Override public Progress tick(CompanionPlayer companion) { fail("dead body"); return running(); }
        };
        var future = new CompletableFuture<ServerTool.Result>();
        scheduler.submit(body, null, task, future, 20);
        scheduler.tick(new CompanionScheduler.Bodies() {
            @Override public CompanionPlayer get(UUID id) { return null; }
            @Override public boolean same(CompanionPlayer expected, CompanionPlayer fresh) { return true; }
            @Override public boolean alive(CompanionPlayer companion) { return false; }
        });
        assertTrue(future.join().feedback().startsWith("BODY_DIED:"));
        assertEquals(0, polls.get());
        assertFalse(scheduler.busy(body));
    }

    @Test void deathInterruptionKeepsPartialEffectsAndReleasesWorkAndBorrowedReflexExactlyOnce() {
        var task = new Task();
        var receipt = start(task, 20);
        var reflex = scheduler.reflexLease(body);
        assertNotNull(reflex);
        assertTrue(scheduler.interrupt(body, new ServerTool.Result(false, "BODY_DIED:actual partial work", null)));
        assertFalse(reflex.valid());
        freeAfter(receipt, task, "BODY_DIED:");
        assertFalse(scheduler.interrupt(body, new ServerTool.Result(false, "BODY_DIED:duplicate", null)));
        scheduler.tick(present);
        assertEquals(0, task.ticks);
        assertEquals(1, task.cleanups);
    }

    @Test void dimensionChangeStopsBeforeTheNextActionTick() {
        var task = new Task();
        var receipt = start(task, 20);
        scheduler.tick(new CompanionScheduler.Bodies() {
            @Override public CompanionPlayer get(UUID id) { return null; }
            @Override public boolean same(CompanionPlayer expected, CompanionPlayer fresh) { return true; }
            @Override public boolean sameDimension(CompanionPlayer fresh, net.minecraft.server.level.ServerLevel expected) {
                return false;
            }
        });
        freeAfter(receipt, task, "TARGET_LOST:");
        assertEquals(0, task.ticks);
    }

    @Test void timeoutOccursAtCapAndReleasesOnce() {
        var task = new Task();
        var receipt = start(task, 2);
        scheduler.tick(present);
        assertFalse(receipt.isDone());
        scheduler.tick(present);
        freeAfter(receipt, task, "TIMEOUT:");
        assertEquals(2, task.ticks);
        assertFalse(scheduler.cancel(body, null));
    }

    @Test void cancelAllReleasesEverySlotAndRegionIncludingThrowingCleanup() {
        var task = new Task();
        task.throwsCleanup = true;
        var receipt = start(task, 20);
        scheduler.cancelAll("shutdown");
        freeAfter(receipt, task, "CANCELLED:");
        scheduler.tick(present);
        assertEquals(0, task.ticks);
    }

    @Test void supplierExceptionAfterSubmissionCannotLeaveTaskOrLease() {
        var task = new Task();
        var future = new CompletableFuture<ServerTool.Result>();
        assertThrows(IllegalStateException.class, () -> scheduler.execute(body, List.of(area), () -> {
            scheduler.submit(body, null, task, future, 20);
            throw new IllegalStateException("offline");
        }));
        freeAfter(future, task, "CANCELLED:");
    }

    @Test void exceptionallyCompletedToolStopsItsStillRunningTask() {
        var task = new Task();
        var future = new CompletableFuture<ServerTool.Result>();
        var receipt = scheduler.execute(body, List.of(area), () -> {
            scheduler.submit(body, null, task, future, 20);
            return future;
        });
        future.completeExceptionally(new IllegalStateException("offline"));
        assertTrue(receipt.isCompletedExceptionally());
        assertEquals(1, task.cleanups);
        assertFalse(scheduler.busy(body));
        scheduler.tick(present);
        assertEquals(0, task.ticks);
    }

    @Test void readOnlyToolsNeedNoLeaseAndSyncMutationCannotRunDuringTask() {
        var task = new Task();
        var receipt = start(task, 20);
        assertTrue(scheduler.execute(body, List.of(), () -> { fail("busy"); return null; })
                .join().feedback().startsWith("BUSY:"));
        scheduler.cancel(body, null);
        freeAfter(receipt, task, "CANCELLED:");
    }

    private com.neko.mcbot.body.BodyControl controlledBody() {
        return new com.neko.mcbot.body.BodyControl(previous -> {
            if (previous == com.neko.mcbot.body.BodyControl.Owner.WORK) scheduler.preempt(body);
        });
    }

    private CompletableFuture<ServerTool.Result> startControlled(Task task, int cap,
            com.neko.mcbot.body.BodyControl control, java.util.function.BooleanSupplier valid) {
        return scheduler.execute(body, List.of(area), () -> scheduler.controlled(body, control, valid, () -> {
            var future = new CompletableFuture<ServerTool.Result>();
            assertTrue(scheduler.submit(body, null, task, future, cap));
            return future;
        }));
    }

    @Test void reflexSuspendsImmediatelyAndResumesOnceWithOriginalElapsedBudget() {
        var task = new Task();
        var control = controlledBody();
        var receipt = startControlled(task, 7, control, () -> true);
        scheduler.tick(present);
        control.reflex(true);
        assertEquals(1, task.suspends);
        scheduler.tick(present);
        scheduler.tick(present);
        assertEquals(1, task.ticks);
        assertEquals(3, scheduler.observation(body).get("elapsed_ticks").getAsInt());
        assertEquals(1, scheduler.observation(body).get("execution_ticks").getAsInt());
        assertFalse(receipt.isDone());
        assertTrue(scheduler.observation(body).get("suspended").getAsBoolean());
        control.reflex(false);
        scheduler.tick(present);
        assertEquals(1, task.resumes);
        assertEquals(2, task.ticks);
        for (int i = 0; i < 3; i++) scheduler.tick(present);
        freeAfter(receipt, task, "TIMEOUT:");
        assertEquals(5, task.ticks);
    }

    @Test void cancelWhilePreemptedReleasesLeaseAndNeverResumesEvenWhenDangerEnds() {
        var task = new Task();
        var control = controlledBody();
        var receipt = startControlled(task, 20, control, () -> true);
        control.reflex(true);
        scheduler.cancel(body, null);
        freeAfter(receipt, task, "CANCELLED:");
        control.reflex(false);
        scheduler.tick(present);
        assertEquals(0, task.resumes);
        assertEquals(0, task.ticks);
        assertEquals(com.neko.mcbot.body.BodyControl.Owner.IDLE, control.owner());
    }

    @Test void preemptedWorkStillTimesOutAndReleasesBeforeCallbacks() {
        var task = new Task();
        var control = controlledBody();
        var receipt = startControlled(task, 2, control, () -> true);
        control.reflex(true);
        scheduler.tick(present);
        scheduler.tick(present);
        freeAfter(receipt, task, "TIMEOUT:");
        control.reflex(false);
        assertEquals(0, task.ticks);
        assertEquals(0, task.resumes);
    }

    @Test void revokedPermissionOrBodyReplacementStopsSuspendedWorkWithoutResuming() {
        for (boolean revoke : new boolean[]{true, false}) {
            var valid = new java.util.concurrent.atomic.AtomicBoolean(true);
            var control = controlledBody();
            var task = new Task();
            var receipt = startControlled(task, 20, control, valid::get);
            control.reflex(true);
            if (revoke) valid.set(false);
            scheduler.tick(revoke ? present : bodies(false));
            freeAfter(receipt, task, revoke ? "DENIED:" : "TARGET_LOST:");
            assertEquals(0, task.ticks);
            assertEquals(0, task.resumes);
        }
    }

    @Test void failedFreshResumeStopsBeforeAnyNewAction() {
        var control = controlledBody();
        var task = new Task();
        var receipt = startControlled(task, 20, control, () -> true);
        scheduler.tick(present);
        control.reflex(true);
        task.resumeAllowed = false;
        control.reflex(false);
        scheduler.tick(present);
        freeAfter(receipt, task, "TARGET_LOST:");
        assertEquals(1, task.ticks);
        assertEquals(1, task.resumes);
    }

    @Test void synchronousWorkCannotRunUnderReflexOwnership() {
        var control = controlledBody();
        control.reflex(true);
        assertTrue(scheduler.execute(body, List.of(area), () -> scheduler.controlled(body, control,
                () -> true, () -> { fail("reflex owns body"); return null; }))
                .join().feedback().startsWith("BUSY:"));
        control.reflex(false);
        assertTrue(scheduler.execute(body, List.of(area), () -> scheduler.controlled(body, control,
                () -> true, () -> CompletableFuture.completedFuture(new ServerTool.Result(true, "sync", null))))
                .join().ok());
        assertEquals(com.neko.mcbot.body.BodyControl.Owner.IDLE, control.owner());
    }
}
