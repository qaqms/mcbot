package com.neko.mcbot.body;

import org.junit.jupiter.api.Test;

import static com.neko.mcbot.body.HealthObservation.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

class HealthObservationTest {
    private static HealthObservation.Snapshot snapshot(float hp, float maxHp, float absorption,
                                                       boolean invulnerable, double x, long tick) {
        return new HealthObservation.Snapshot(hp, maxHp, absorption, invulnerable,
                true, 0, x, 80, 100.5, "minecraft:overworld", tick);
    }

    @Test void loadedLowHealthRemainsVisibleThroughJoiningWithoutHealing() {
        var observer = new HealthObservation();
        var created = observer.sample(CREATED, snapshot(20, 20, 0, false, 0, 1), null);
        assertTrue(created.data().get("previous").isJsonNull());
        var saved = snapshot(19, 20, 0, false, 0, 1);
        var loaded = observer.sample(LOADED, saved, null);
        assertTrue(loaded.data().get("health_changed").getAsBoolean());
        assertEquals(20, loaded.previous().hp());
        assertSame(saved, loaded.current());
        for (var phase : new HealthObservation.Phase[]{POSITIONED, JOINED, READY}) {
            var event = observer.sample(phase, snapshot(19, 20, 0, true, 100.5, 1), null);
            assertEquals(19, event.current().hp());
            assertFalse(event.data().get("health_changed").getAsBoolean());
        }
    }

    @Test void normalMovementIsSilentButTheNextChangeUsesTheLatestSample() {
        var observer = new HealthObservation();
        observer.sample(READY, snapshot(20, 20, 0, true, 100.5, 10), null);
        assertNull(observer.sample(BEFORE_TICK, snapshot(20, 20, 0, true, 103.5, 11), null));
        assertNull(observer.sample(AFTER_TICK, snapshot(20, 20, 0, true, 107.5, 11), null));
        var event = observer.sample(BEFORE_TICK, snapshot(19, 20, 0, true, 107.5, 12), null);
        assertEquals(11, event.previous().gameTick());
        assertEquals(107.5, event.previous().x());
        assertEquals(20, event.previous().hp());
        assertEquals(19, event.current().hp());
        assertFalse(event.data().has("damage"), "a sampled change has no proven damage source");
        assertNull(observer.sample(STATUS, snapshot(19, 20, 0, true, 107.5, 12), null));
    }

    @Test void rejectedDamageIsRecordedOnceAndDoesNotBecomeAHealthLoss() {
        var observer = new HealthObservation();
        var body = snapshot(20, 20, 0, true, 100.5, 10);
        observer.sample(READY, body, null);
        var damage = new HealthObservation.Damage("minecraft:arrow", 1, false);
        var event = observer.sample(AFTER_DAMAGE, body, damage);
        assertFalse(event.data().get("health_changed").getAsBoolean());
        assertFalse(event.data().getAsJsonObject("damage").get("returned").getAsBoolean());
        assertNull(observer.sample(AFTER_DAMAGE, body, damage), "stable rejected hits must not flood logs");
        var change = observer.sample(AFTER_DAMAGE, snapshot(19, 20, 0, true, 100.5, 11), damage);
        assertTrue(change.data().get("health_changed").getAsBoolean());
        assertFalse(change.damage().returned(), "returned=false cannot hide an observed change");
    }

    @Test void absorptionLossAndInvulnerabilityOrMaximumChangesAreIndependentOfHp() {
        var observer = new HealthObservation();
        observer.sample(READY, snapshot(20, 20, 4, true, 100.5, 10), null);
        var damage = new HealthObservation.Damage("minecraft:mob_attack", 2, true);
        var absorption = observer.sample(AFTER_DAMAGE, snapshot(20, 20, 2, true, 100.5, 11), damage);
        assertFalse(absorption.data().get("health_changed").getAsBoolean());
        assertEquals(4, absorption.previous().absorption());
        assertEquals(2, absorption.current().absorption());
        var invulnerability = observer.sample(STATUS, snapshot(20, 20, 2, false, 100.5, 12), null);
        assertFalse(invulnerability.current().invulnerable());
        var maximum = observer.sample(AFTER_TICK, snapshot(20, 24, 2, false, 100.5, 13), null);
        assertEquals(24, maximum.current().maxHp());
        assertFalse(maximum.data().get("health_changed").getAsBoolean());
    }

    @Test void missingDamageReturnAndUnobservedDecreaseAreNotInventedResults() {
        var observer = new HealthObservation();
        observer.sample(READY, snapshot(20, 20, 0, true, 100.5, 10), null);
        var damage = new HealthObservation.Damage("unregistered", 1, null);
        var event = observer.sample(AFTER_DAMAGE, snapshot(19, 20, 0, true, 100.5, 11), damage);
        assertTrue(event.data().getAsJsonObject("damage").get("returned").isJsonNull());
        var other = new HealthObservation();
        assertNull(other.sample(NO_SAVED_DATA, snapshot(19, 20, 0, true, 0, 1), null).previous());
    }

    @Test void separateBodiesCannotInheritObservationOrDamageSuppression() {
        var first = new HealthObservation();
        first.sample(READY, snapshot(19, 20, 0, true, 100.5, 10), null);
        first.sample(AFTER_DAMAGE, snapshot(19, 20, 0, true, 100.5, 10),
                new HealthObservation.Damage("minecraft:arrow", 1, false));
        var second = new HealthObservation();
        var ready = second.sample(READY, snapshot(20, 20, 0, true, -5.5, 1), null);
        assertNull(ready.previous());
        var hit = second.sample(AFTER_DAMAGE, ready.current(),
                new HealthObservation.Damage("minecraft:arrow", 1, false));
        assertNotNull(hit);
        assertEquals(20, hit.previous().hp());
    }
}
