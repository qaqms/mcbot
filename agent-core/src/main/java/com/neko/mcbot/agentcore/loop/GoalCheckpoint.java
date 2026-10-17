package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.UUID;

/** Durable identity and bounded facts, independent of a network session or live body instance. */
public final class GoalCheckpoint {
    private GoalCheckpoint() { }

    public static String scope(JsonObject state) {
        return uuid(state, "world_id") + "_" + uuid(state, "owner_uuid") + "_" + uuid(state, "companion_uuid");
    }

    private static String uuid(JsonObject data, String key) {
        String value = WorldGoal.text(data, key);
        if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("Invalid identity");
        return value;
    }

    public static void verifyIdentity(JsonObject saved, JsonObject fresh) {
        if (!scope(saved).equals(scope(fresh))
                || !uuid(saved, "body_continuity_id").equals(uuid(fresh, "body_continuity_id"))
                || !WorldGoal.text(saved, "dimension").equals(WorldGoal.text(fresh, "dimension"))
                || !WorldGoal.flag(fresh, "alive") || BodyFacts.environmentUnsafe(fresh))
            throw new IllegalArgumentException("Recovery identity or environment changed");
        WorldGoal.text(fresh, "body_id");
    }

    public static JsonObject initial(JsonObject state) {
        BodyFacts.fingerprint(state);
        var result = new JsonObject();
        for (String key : List.of("x", "y", "z", "dimension", "world_id", "owner_uuid",
                "companion_uuid", "body_continuity_id", "body_id", "alive"))
            if (state.has(key)) result.add(key, state.get(key).deepCopy());
        var inventory = new JsonObject();
        var slots = new com.google.gson.JsonArray();
        for (var value : state.getAsJsonObject("inventory").getAsJsonArray("slots")) {
            var slot = value.getAsJsonObject();
            var kept = new JsonObject();
            for (String key : List.of("slot", "item", "count")) kept.add(key, slot.get(key).deepCopy());
            slots.add(kept);
        }
        inventory.add("slots", slots);
        result.add("inventory", inventory);
        return result;
    }

    public static int bounded(JsonObject data, String key, int low, int high) {
        int value = WorldGoal.integer(data, key);
        if (value < low || value > high) throw new IllegalArgumentException("Invalid checkpoint counter");
        return value;
    }
}
