package com.neko.mcbot.task;

import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.AttackFixtures;
import com.neko.mcbot.server.BlockActionFixtures;
import com.neko.mcbot.server.EntityAttack;
import com.neko.mcbot.server.ServerActionGate;
import com.neko.mcbot.server.ActionPermissions;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.server.tools.AttackTool;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class AttackSchedulerTest {
    @BeforeAll static void registriesOnly() { BlockActionFixtures.bootstrap(); }
    private final CompanionScheduler scheduler = new CompanionScheduler();
    private final UUID body = UUID.randomUUID(), entity = UUID.randomUUID();
    private final ResourceLocks.Target target = new ResourceLocks.Target("world", entity);
    private final CompanionScheduler.Bodies present = new CompanionScheduler.Bodies() {
        @Override public CompanionPlayer get(UUID id) { return null; }
        @Override public boolean same(CompanionPlayer expected, CompanionPlayer fresh) { return true; }
    };

    private CompletableFuture<ServerTool.Result> start(AttackFixtures.Access access, int cap) {
        return scheduler.execute(body, List.of(), () -> {
            assertTrue(scheduler.reserveTarget(body, target));
            var future = new CompletableFuture<ServerTool.Result>();
            assertTrue(scheduler.submit(body, null, new AttackTool.Task(new EntityAttack(access, 10)), future, cap));
            return future;
        });
    }

    private void assertReleased() {
        assertFalse(scheduler.busy(body));
        assertFalse(scheduler.reserveTarget(body, target), "ended body no longer owns a lease");
        UUID other = UUID.randomUUID();
        assertTrue(scheduler.execute(other, List.of(), () -> {
            assertTrue(scheduler.reserveTarget(other, target));
            return CompletableFuture.completedFuture(new ServerTool.Result(true, "next target owner", null));
        }).join().ok());
    }

    @Test void cancellationAfterOneStrikePreservesEffectsAndPreventsFutureTicks() {
        var access = new AttackFixtures.Access();
        var receipt = start(access, AttackTool.CAP_TICKS);
        scheduler.tick(present);
        assertEquals(1, access.strikes);
        assertTrue(scheduler.cancel(body, "owner"));
        assertTrue(receipt.join().feedback().startsWith("CANCELLED:"));
        assertEquals(1, receipt.join().data().get("strikes").getAsInt());
        scheduler.tick(present);
        assertEquals(1, access.strikes);
        assertReleased();
    }

    @Test void perpetualCooldownEndsAtActualCapWithZeroStrikes() {
        var access = new AttackFixtures.Access();
        access.ready = false;
        var receipt = start(access, AttackTool.CAP_TICKS);
        for (int i = 1; i < AttackTool.CAP_TICKS; i++) scheduler.tick(present);
        assertFalse(receipt.isDone());
        scheduler.tick(present);
        assertTrue(receipt.join().feedback().startsWith("TIMEOUT:"));
        assertEquals(0, receipt.join().data().get("strikes").getAsInt());
        assertEquals(0, access.strikes);
        assertReleased();
    }

    @Test void bodyReplacementAndDimensionChangeStopBeforeAnyStrike() {
        for (boolean dimension : new boolean[]{false, true}) {
            var access = new AttackFixtures.Access();
            var receipt = start(access, 400);
            scheduler.tick(new CompanionScheduler.Bodies() {
                @Override public CompanionPlayer get(UUID id) { return null; }
                @Override public boolean same(CompanionPlayer expected, CompanionPlayer fresh) { return dimension; }
                @Override public boolean sameDimension(CompanionPlayer fresh, net.minecraft.server.level.ServerLevel expected) {
                    return !dimension;
                }
            });
            assertTrue(receipt.join().feedback().startsWith("TARGET_LOST:"));
            assertEquals(0, receipt.join().data().get("strikes").getAsInt());
            assertEquals(0, access.strikes);
            assertReleased();
        }
    }

    @Test void nativeCallbackExceptionCompletesOnceWithUnknownAttemptAndReleases() {
        var access = new AttackFixtures.Access();
        access.effect = () -> { access.health--; throw new IllegalStateException("controlled native exception"); };
        var receipt = start(access, 400);
        scheduler.tick(present);
        assertTrue(receipt.join().feedback().startsWith("INTERNAL:"));
        assertEquals(1, receipt.join().data().get("strikes").getAsInt());
        assertFalse(receipt.join().data().get("observation_known").getAsBoolean());
        scheduler.tick(present);
        assertEquals(1, access.strikes);
        assertReleased();
    }

    @Test void serverGateRequiresNormalTaskAndBodyLeaseUntilAttackTerminal() {
        UUID owner = UUID.randomUUID();
        var normal = new ActionPermissions.Context(owner, body, "world", 1, false);
        var read = new ActionPermissions.Context(owner, body, "world", 2, true);
        for (ActionPermissions.Context context : new ActionPermissions.Context[]{null, read}) {
            var denied = ServerActionGate.execute(context, body, "world", "attack", new JsonObject(), scheduler,
                    () -> { fail("no mutating permission"); return null; });
            assertTrue(denied.join().feedback().startsWith("DENIED:"));
        }
        var future = new CompletableFuture<ServerTool.Result>();
        var receipt = ServerActionGate.execute(normal, body, "world", "attack", new JsonObject(), scheduler, () -> future);
        assertTrue(ServerActionGate.execute(normal, body, "world", "equip", new JsonObject(), scheduler,
                () -> { fail("body still leased"); return null; }).join().feedback().startsWith("BUSY:"));
        future.complete(new ServerTool.Result(true, "attack terminal", null));
        assertTrue(receipt.join().ok());
        assertTrue(ServerActionGate.execute(normal, body, "world", "equip", new JsonObject(), scheduler,
                () -> CompletableFuture.completedFuture(new ServerTool.Result(true, "equipped", null))).join().ok());
    }
}
