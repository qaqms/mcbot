package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;

import java.util.LinkedHashMap;
import java.util.Set;

/** Task-owned evidence at the primitive terminal boundary, independent of program lifetimes. */
public final class WorkFailureMemory {
    private static final int ENTRY_LIMIT = 128;
    private record Key(String tool, JsonObject args, String reason) { }
    private final LinkedHashMap<Key, Integer> failures = new LinkedHashMap<>();
    private final LinkedHashMap<Key, JsonObject> terrain = new LinkedHashMap<>();
    private JsonObject spatial;
    private String bodyFacts;

    public static boolean localFailure(String tool, String feedback) {
        if (!Set.of("move_to", "break_block", "follow", "gather", "explore").contains(tool)
                || feedback == null) return false;
        int colon = feedback.indexOf(':');
        return colon > 0 && Set.of("NO_PATH", "NO_PROGRESS", "PARTIAL", "TARGET_BLOCKED",
                "OUT_OF_REACH", "TARGET_LOST").contains(feedback.substring(0, colon));
    }

    private static boolean tracked(String tool) {
        return tool.equals("move_to") || tool.equals("break_block") || tool.equals("follow");
    }

    /** Diagnostic count only. This ledger never authorizes or blocks dispatch. */
    public synchronized int failures(String tool, JsonObject args) {
        int count = 0;
        for (var entry : failures.entrySet()) {
            Key key = entry.getKey();
            if (key.tool().equals(tool) && key.args().equals(args))
                count = (int) Math.min(Integer.MAX_VALUE, (long) count + entry.getValue());
        }
        return count;
    }

    /** Called only for a real, matched terminal; acknowledgments and synthetic stops are excluded. */
    public synchronized void terminal(String tool, JsonObject args, ToolOutcome result) {
        if (result.accepted()) return;
        JsonObject data = result.data();
        if (result.ok() && tool.equals("status") && flag(args, "details")) {
            try {
                String fresh = BodyFacts.fingerprint(data);
                if (bodyFacts != null && !bodyFacts.equals(fresh)) clear();
                bodyFacts = fresh;
                var observed = SpatialFacts.read(data);
                if (observed != null) {
                    failures.keySet().removeIf(key -> {
                        if (!SpatialFacts.changed(terrain.get(key), observed)) return false;
                        terrain.remove(key);
                        return true;
                    });
                    spatial = observed;
                }
            } catch (RuntimeException incomplete) {
                // Incomplete observations cannot establish a new body baseline.
            }
        }
        if (!TaskPolicy.observation(tool, args) && effect(tool, data)) {
            clear();
            return;
        }
        if (tracked(tool) && !result.ok() && localFailure(tool, result.feedback())) {
            var key = new Key(tool, args.deepCopy(), result.feedback().split(":", 2)[0]);
            if (!failures.containsKey(key) && failures.size() >= ENTRY_LIMIT) {
                var oldest = failures.keySet().iterator().next();
                failures.remove(oldest);
                terrain.remove(oldest);
            }
            failures.merge(key, 1, (count, ignored) -> count == Integer.MAX_VALUE ? count : count + 1);
            if (!terrain.containsKey(key)) {
                var observed = SpatialFacts.read(data);
                var evidence = SpatialFacts.forWork(observed == null ? spatial : observed, tool, args);
                if (evidence != null) terrain.put(key, evidence);
            }
        }
    }

    private void clear() { failures.clear(); terrain.clear(); }

    public synchronized JsonObject snapshot() {
        var data = new JsonObject();
        var entries = new com.google.gson.JsonArray();
        failures.forEach((key, count) -> {
            var value = new JsonObject();
            value.addProperty("tool", key.tool());
            value.add("args", key.args().deepCopy());
            value.addProperty("reason", key.reason());
            value.addProperty("count", count);
            entries.add(value);
        });
        data.add("entries", entries);
        if (bodyFacts != null) data.addProperty("body_facts", bodyFacts);
        return data;
    }

    public static WorkFailureMemory restore(JsonObject data) {
        var memory = new WorkFailureMemory();
        var entries = data.getAsJsonArray("entries");
        if (entries == null || entries.size() > ENTRY_LIMIT) throw new IllegalArgumentException();
        for (var entry : entries) {
            var value = entry.getAsJsonObject();
            String tool = WorldGoal.text(value, "tool"), reason = WorldGoal.text(value, "reason");
            var args = value.getAsJsonObject("args");
            if (!tracked(tool) || !localFailure(tool, reason + ":") || args == null
                    || args.toString().length() > 20000) throw new IllegalArgumentException();
            var key = new Key(tool, args.deepCopy(), reason);
            if (memory.failures.put(key, GoalCheckpoint.bounded(value, "count", 1, Integer.MAX_VALUE)) != null)
                throw new IllegalArgumentException();
        }
        if (data.has("body_facts")) {
            var value = data.get("body_facts");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                    || value.getAsString().length() > 16384) throw new IllegalArgumentException();
            memory.bodyFacts = value.getAsString();
        }
        // Live chunk versions cannot survive a server restart; retaining failure counts is conservative.
        return memory;
    }

    private static boolean effect(String tool, JsonObject data) {
        return switch (tool) {
            case "move_to" -> positive(data, "removed_blocks", "placed_blocks");
            case "break_block" -> flag(data, "removed") || positive(data, "cleared_blocks");
            case "place_block", "build_nearby" -> flag(data, "changed");
            case "craft" -> positive(data, "crafted_count");
            case "collect" -> positive(data, "collected_count");
            case "smelt" -> positive(data, "loaded_input_count", "loaded_fuel_count", "taken_count");
            case "transfer" -> positive(data, "moved_items");
            default -> false;
        };
    }

    private static boolean flag(JsonObject data, String key) {
        var value = data.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                && value.getAsBoolean();
    }

    private static boolean positive(JsonObject data, String... keys) {
        for (String key : keys) {
            var value = data.get(key);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) continue;
            try {
                if (value.getAsBigDecimal().intValueExact() > 0) return true;
            } catch (RuntimeException invalid) {
                // Only the primitive's bounded integer effect counters prove an edit or transfer.
            }
        }
        return false;
    }
}
