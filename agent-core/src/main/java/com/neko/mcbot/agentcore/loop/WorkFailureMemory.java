package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;

import java.util.LinkedHashMap;
import java.util.Set;

/** Task-owned evidence at the primitive terminal boundary, independent of program lifetimes. */
public final class WorkFailureMemory {
    public static final int FAILURE_LIMIT = 5;
    private static final int ENTRY_LIMIT = 128;
    private record Key(String tool, JsonObject args, String reason) { }
    private final LinkedHashMap<Key, Integer> failures = new LinkedHashMap<>();
    private final LinkedHashMap<Key, JsonObject> terrain = new LinkedHashMap<>();
    private JsonObject spatial;
    private String bodyFacts;

    public static boolean localFailure(String tool, String feedback) {
        if (!Set.of("move_to", "break_block", "gather", "explore").contains(tool)
                || feedback == null) return false;
        int colon = feedback.indexOf(':');
        return colon > 0 && Set.of("NO_PATH", "NO_PROGRESS", "PARTIAL", "TARGET_BLOCKED",
                "OUT_OF_REACH", "TARGET_LOST").contains(feedback.substring(0, colon));
    }

    private static boolean tracked(String tool) {
        return tool.equals("move_to") || tool.equals("break_block");
    }

    /** Null permits normal permission checks and dispatch; a refusal never dispatches or retries. */
    public synchronized ToolOutcome refusal(String tool, JsonObject args) {
        if (!tracked(tool)) return null;
        for (var entry : failures.entrySet()) {
            Key key = entry.getKey();
            if (key.tool().equals(tool) && key.args().equals(args) && entry.getValue() >= FAILURE_LIMIT)
                return stopped("REPEATED_FAILURE", key.reason(), entry.getValue());
        }
        // Do not evict old evidence and silently give repeated work a new allowance.
        if (failures.size() >= ENTRY_LIMIT)
            return stopped("FAILURE_MEMORY_LIMIT", "CAPACITY", 0);
        return null;
    }

    private static ToolOutcome stopped(String code, String reason, int count) {
        var evidence = new JsonObject();
        evidence.addProperty("reason", reason);
        evidence.addProperty("failures", count);
        evidence.addProperty("limit", FAILURE_LIMIT);
        evidence.addProperty("entry_limit", ENTRY_LIMIT);
        evidence.addProperty("dispatched", false);
        var data = new JsonObject();
        data.add("failure_memory", evidence);
        return new ToolOutcome(false, code + ":当前任务的相同工作已反复失败或失败账已满，未再次派发。"
                + "原因=" + reason + "，次数=" + count
                + "；先核对新鲜完整status及真实环境，选择有依据的替代方案或报告受阻。"
                + "换程序、空动作和重复查询不补次数。", data);
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
            if (failures.containsKey(key) || failures.size() < ENTRY_LIMIT) {
                failures.merge(key, 1, (count, ignored) -> Math.min(FAILURE_LIMIT, count + 1));
                if (!terrain.containsKey(key)) {
                    var observed = SpatialFacts.read(data);
                    var evidence = SpatialFacts.forWork(observed == null ? spatial : observed, tool, args);
                    if (evidence != null) terrain.put(key, evidence);
                }
            }
        }
    }

    private void clear() { failures.clear(); terrain.clear(); }

    private static boolean effect(String tool, JsonObject data) {
        return switch (tool) {
            case "move_to" -> positive(data, "removed_blocks", "placed_blocks");
            case "break_block" -> flag(data, "removed") || positive(data, "cleared_blocks");
            case "place_block" -> flag(data, "changed");
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
