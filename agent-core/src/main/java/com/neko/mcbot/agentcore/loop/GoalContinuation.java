package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;

/** Bounded stage checkpoints inside one task; neither task budgets nor inventory baselines restart. */
final class GoalContinuation {
    enum Verdict { CONTINUE, NO_PROGRESS, LIMIT }
    private final int maxRuns;
    private final Set<String> observed = new HashSet<>();
    private int completedRuns, stalled;

    GoalContinuation(int maxRuns, JsonObject initial) {
        this.maxRuns = maxRuns;
        observed.add(fingerprint(initial));
    }

    static int limit(JsonObject args) {
        var value = args.get("max_runs");
        if (value == null) return 8;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
        int count = value.getAsBigDecimal().intValueExact();
        if (count < 1 || count > 16) throw new IllegalArgumentException();
        return count;
    }

    boolean matches(JsonObject args) {
        return !args.has("max_runs") || limit(args) == maxRuns;
    }

    Verdict checkpoint(JsonObject state) {
        String facts = fingerprint(state);
        completedRuns++;
        stalled = observed.add(facts) ? 0 : stalled + 1;
        if (stalled >= 2) return Verdict.NO_PROGRESS;
        return completedRuns >= maxRuns ? Verdict.LIMIT : Verdict.CONTINUE;
    }

    String facts() {
        return "长期目标生命周期：阶段已核对=" + completedRuns + "/" + maxRuns
                + "，连续重复事实=" + stalled
                + "；阶段结束须新鲜背包核验，材料与位移仅作阶段进展；总步数、权限、探索预算和原始目标继续沿用。";
    }

    private static String fingerprint(JsonObject state) {
        ItemGoal.count(state, "minecraft:air");
        var items = new TreeMap<String, Integer>();
        for (var value : state.getAsJsonObject("inventory").getAsJsonArray("slots")) {
            var slot = value.getAsJsonObject();
            int count = slot.get("count").getAsInt();
            if (count > 0) items.merge(slot.get("item").getAsString(), count, Math::addExact);
        }
        var point = new JsonObject();
        for (String axis : java.util.List.of("x", "y", "z")) {
            var value = state.get(axis);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                throw new IllegalArgumentException();
            double number = value.getAsDouble();
            if (!Double.isFinite(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE)
                throw new IllegalArgumentException();
            point.addProperty(axis, (int) Math.floor(number));
        }
        var dimension = state.get("dimension");
        if (dimension == null || !dimension.isJsonPrimitive() || !dimension.getAsJsonPrimitive().isString()
                || dimension.getAsString().isBlank() || dimension.getAsString().length() > 128)
            throw new IllegalArgumentException();
        point.addProperty("dimension", dimension.getAsString());
        // Slot order, clock, health and tool wear cannot masquerade as new stage results.
        return items + "|" + point;
    }
}
