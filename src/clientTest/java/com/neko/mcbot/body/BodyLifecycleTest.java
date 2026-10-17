package com.neko.mcbot.body;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BodyLifecycleTest {
    private final UUID id = UUID.randomUUID();
    private final BodyLifecycle life = new BodyLifecycle(id);
    private final List<String> events = new ArrayList<>();
    private boolean current = true, owner = true, rebuilt = true;
    private String failAt = "";
    private int builds;
    private final BodyLifecycle.Access access = new BodyLifecycle.Access() {
        private void action(String name) {
            events.add(name);
            if (failAt.equals(name)) throw new IllegalStateException(name);
        }
        @Override public boolean current() { return current; }
        @Override public void announce() { action("notice"); }
        @Override public void revoke() { action("revoke"); }
        @Override public void stop() { action("stop"); }
        @Override public boolean ownerReady() { return owner; }
        @Override public boolean rebuild() { builds++; action("build"); return rebuilt; }
    };
    private void die() {
        life.die(access, "native_test", () -> {
            events.add("native");
            if (failAt.equals("native")) throw new IllegalStateException("native");
        });
    }
    private void ticks(BodyLifecycle target, int n) { for (int i = 0; i < n; i++) target.tick(access); }
    private CompoundTag save(BodyLifecycle target) {
        var out = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        target.save(out);
        return out.buildResult();
    }
    private BodyLifecycle load(CompoundTag tag) {
        var result = new BodyLifecycle(UUID.randomUUID());
        result.load(TagValueInput.create(ProblemReporter.DISCARDING, net.minecraft.core.RegistryAccess.EMPTY, tag));
        return result;
    }

    @Test void deathNoticePrecedesTerminalCleanupAndNativeDropsExactlyOnce() {
        die(); die();
        assertEquals(List.of("notice", "revoke", "stop", "native"), events);
        assertTrue(life.retired());
        assertTrue(life.observation().get("native_death_complete").getAsBoolean());
        assertFalse(life.observation().get("automatic_resume").getAsBoolean());
        ticks(life, BodyLifecycle.DELAY_TICKS);
        assertEquals(0, builds);
        ticks(life, 1);
        assertEquals(1, builds);
        assertEquals("retired", life.state());
        ticks(life, 100);
        assertEquals(1, builds);
    }

    @ParameterizedTest @ValueSource(strings = {"notice", "revoke", "stop", "native"})
    void everyFailureStillAttemptsNativeDeathButNeverReconstructs(String phase) {
        failAt = phase;
        assertThrows(IllegalStateException.class, this::die);
        assertEquals(List.of("notice", "revoke", "stop", "native"), events);
        assertEquals("recovery_failed", life.state());
        ticks(life, 1000);
        assertEquals(0, builds);
        die();
        assertEquals(4, events.size());
    }

    @Test void missingOwnerWaitsWithoutSpendingSearchAttemptsOrReopeningTheDelay() {
        die(); owner = false;
        ticks(life, 1000);
        assertEquals(0, builds);
        assertEquals(40, life.observation().get("recovery_elapsed_ticks").getAsInt());
        owner = true;
        ticks(life, 1);
        assertEquals(1, builds);
    }

    @Test void sameUuidButDifferentRegisteredObjectOrDismissalRetiresRecovery() {
        die(); current = false;
        ticks(life, 100);
        current = true;
        ticks(life, 100);
        assertEquals("retired", life.state());
        assertEquals(0, builds);
    }

    @Test void unsafePlacementsHaveOneCumulativeBoundedSearchBudget() {
        die(); rebuilt = false;
        ticks(life, 1000);
        assertEquals(BodyLifecycle.MAX_ATTEMPTS, builds);
        assertEquals("recovery_blocked", life.state());
        rebuilt = true; die();
        ticks(life, 1000);
        assertEquals(BodyLifecycle.MAX_ATTEMPTS, builds);
        assertEquals(2, events.stream().filter("notice"::equals).count());
    }

    @Test void partiallyRegisteredReplacementExceptionIsNeverRetried() {
        die(); failAt = "build";
        assertThrows(IllegalStateException.class, () -> ticks(life, 100));
        ticks(life, 1000);
        assertEquals(1, builds);
        assertEquals("recovery_failed", life.state());
        ticks(load(save(life)), 1000);
        assertEquals(1, builds);
    }

    @Test void unexplainedZeroHealthCannotReplayNativeDeathOrOldInventory() {
        life.unavailable(access);
        assertEquals(List.of("notice", "revoke", "stop"), events);
        assertFalse(life.observation().get("native_death_complete").getAsBoolean());
        ticks(life, 1000);
        assertEquals(0, builds);
    }

    @Test void completedDeathSaveRetainsCauseOriginalIdentityAndSpentAttemptsAfterRestart() {
        die(); rebuilt = false;
        ticks(life, 41);
        assertEquals(1, builds);
        var restored = load(save(life));
        assertEquals("dead", restored.state());
        assertEquals("native_test", restored.cause());
        assertEquals(id.toString(), restored.observation().get("death_body_id").getAsString());
        assertNotEquals(id.toString(), restored.observation().get("body_id").getAsString());
        ticks(restored, 1000);
        assertEquals(BodyLifecycle.MAX_ATTEMPTS, builds);
        assertEquals("recovery_blocked", restored.state());
        ticks(load(save(restored)), 1000);
        assertEquals(BodyLifecycle.MAX_ATTEMPTS, builds);
    }

    @Test void persistedRebuildingOrUnknownMetadataFailsClosedWithoutReplayingDrops() {
        die();
        var tag = save(life);
        tag.putString("state", "rebuilding");
        var restored = load(tag);
        assertEquals("recovery_failed", restored.state());
        ticks(restored, 1000);
        tag.putString("state", "dead"); tag.putInt("version", 8);
        assertEquals("recovery_failed", load(tag).state());
        assertEquals("recovery_failed", load(new CompoundTag()).state());
        assertEquals(0, builds);
    }

    @Test void corruptRecoveryCountersCannotBuyFreshSearchAttempts() {
        die();
        var tag = save(life);
        tag.putInt("attempts", -1);
        assertEquals("recovery_failed", load(tag).state());
        tag = save(life); tag.putInt("elapsed", Integer.MAX_VALUE);
        assertEquals("recovery_failed", load(tag).state());
    }

    @Test void saveOfOrdinaryAliveBodyDoesNotCarryOldAuthorityOrIncarnation() {
        var restored = load(save(life));
        assertEquals("alive", restored.state());
        assertNotEquals(id.toString(), restored.observation().get("body_id").getAsString());
        ticks(restored, 1000);
        assertEquals(0, builds);
    }
}
