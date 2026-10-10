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
        int ticks, cleanups;
        boolean done, throwsTick, throwsCleanup;
        @Override public Progress tick(CompanionPlayer companion) {
            ticks++;
            if (throwsTick) throw new IllegalStateException("offline");
            return done ? new Progress.Done(new ServerTool.Result(true, "done", null)) : running();
        }
        @Override public void onAbort() {
            cleanups++;
            if (throwsCleanup) throw new IllegalStateException("offline cleanup");
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
}
