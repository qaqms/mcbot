package com.neko.mcbot.body;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/** Keeps observation boundaries without changing health or attributing unobserved damage. */
public final class HealthObservation {
    public enum Phase {
        CREATED, LOADED, NO_SAVED_DATA, POSITIONED, JOINED, READY,
        BEFORE_TICK, AFTER_TICK, BEFORE_DAMAGE, AFTER_DAMAGE, STATUS
    }

    public record Snapshot(float hp, float maxHp, float absorption, boolean invulnerable,
                           boolean onGround, double fallDistance, double x, double y, double z,
                           String dimension, long gameTick) {
        JsonObject data() {
            var data = new JsonObject();
            data.addProperty("hp", hp);
            data.addProperty("max_hp", maxHp);
            data.addProperty("absorption", absorption);
            data.addProperty("invulnerable", invulnerable);
            data.addProperty("on_ground", onGround);
            data.addProperty("fall_distance", fallDistance);
            data.addProperty("x", x);
            data.addProperty("y", y);
            data.addProperty("z", z);
            data.addProperty("dimension", dimension);
            data.addProperty("game_tick", gameTick);
            return data;
        }
    }

    public record Damage(String type, float requestedAmount, Boolean returned) {
        JsonObject data() {
            var data = new JsonObject();
            data.addProperty("type", type);
            data.addProperty("requested_amount", requestedAmount);
            data.addProperty("returned", returned);
            return data;
        }
    }

    public record Event(Phase phase, Snapshot previous, Snapshot current, Damage damage) {
        public JsonObject data() {
            var data = new JsonObject();
            data.addProperty("phase", phase.name());
            data.addProperty("health_changed", previous != null
                    && Float.compare(previous.hp(), current.hp()) != 0);
            data.add("previous", previous == null ? JsonNull.INSTANCE : previous.data());
            data.add("current", current.data());
            if (damage != null) data.add("damage", damage.data());
            return data;
        }
    }

    private Snapshot last;
    private boolean damageSeen;

    public Event sample(Phase phase, Snapshot current, Damage damage) {
        Snapshot previous = last;
        last = current;
        boolean firstDamage = phase == Phase.AFTER_DAMAGE && !damageSeen;
        if (phase == Phase.AFTER_DAMAGE) damageSeen = true;
        boolean lifecycle = switch (phase) {
            case CREATED, LOADED, NO_SAVED_DATA, POSITIONED, JOINED, READY -> true;
            default -> false;
        };
        boolean changed = previous != null && (Float.compare(previous.hp(), current.hp()) != 0
                || Float.compare(previous.maxHp(), current.maxHp()) != 0
                || Float.compare(previous.absorption(), current.absorption()) != 0
                || previous.invulnerable() != current.invulnerable());
        if (previous == null || lifecycle || changed || firstDamage)
            return new Event(phase, previous, current, damage);
        return null;
    }
}
