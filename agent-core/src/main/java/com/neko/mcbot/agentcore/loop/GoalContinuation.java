package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;

import java.util.LinkedHashSet;
import java.util.Set;

/** Stage checkpoints retain task identity and inventory baselines; a positive run cap is optional. */
final class GoalContinuation {
    enum Verdict { CONTINUE, LIMIT }
    private final int maxRuns;
    private static final int HISTORY_LIMIT = 128;
    private final Set<String> observed = new LinkedHashSet<>();
    private int completedRuns, stalled;

    GoalContinuation(int maxRuns, JsonObject initial) {
        this(maxRuns, initial, "");
    }

    GoalContinuation(int maxRuns, JsonObject initial, String goalFacts) {
        this.maxRuns = maxRuns;
        observed.add(BodyFacts.fingerprint(initial) + "|" + goalFacts);
    }

    static int limit(JsonObject args) {
        var value = args.get("max_runs");
        if (value == null) return 0;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
        int count = value.getAsBigDecimal().intValueExact();
        if (count < 0) throw new IllegalArgumentException();
        return count;
    }

    boolean matches(JsonObject args) {
        return !args.has("max_runs") || limit(args) == maxRuns;
    }

    JsonObject snapshot() {
        var data = new JsonObject();
        data.addProperty("max_runs", maxRuns);
        data.addProperty("completed_runs", completedRuns);
        data.addProperty("stalled", stalled);
        var values = new com.google.gson.JsonArray();
        observed.stream().sorted().forEach(values::add);
        data.add("observed", values);
        return data;
    }

    static GoalContinuation restore(JsonObject data, JsonObject initial) {
        int limit = GoalCheckpoint.bounded(data, "max_runs", 0, Integer.MAX_VALUE);
        var value = new GoalContinuation(limit, initial);
        value.completedRuns = GoalCheckpoint.bounded(data, "completed_runs", 0,
                limit == 0 ? Integer.MAX_VALUE : limit);
        value.stalled = GoalCheckpoint.bounded(data, "stalled", 0, 2);
        var facts = data.getAsJsonArray("observed");
        if (facts == null || facts.isEmpty() || facts.size() > HISTORY_LIMIT) throw new IllegalArgumentException();
        value.observed.clear();
        for (var fact : facts) {
            if (!fact.isJsonPrimitive() || !fact.getAsJsonPrimitive().isString()
                    || fact.getAsString().length() > 16384 || !value.observed.add(fact.getAsString()))
                throw new IllegalArgumentException();
        }
        return value;
    }

    boolean exhausted() { return maxRuns > 0 && completedRuns >= maxRuns; }

    Verdict checkpoint(JsonObject state) {
        return checkpoint(state, "");
    }

    Verdict checkpoint(JsonObject state, String goalFacts) {
        String facts = BodyFacts.fingerprint(state) + "|" + goalFacts;
        if (completedRuns < Integer.MAX_VALUE) completedRuns++;
        stalled = observed.add(facts) ? 0 : Math.min(2, stalled + 1);
        if (observed.size() > HISTORY_LIMIT) observed.remove(observed.iterator().next());
        return maxRuns > 0 && completedRuns >= maxRuns ? Verdict.LIMIT : Verdict.CONTINUE;
    }

    String facts() {
        return "长期目标生命周期：阶段已核对=" + completedRuns + "/" + (maxRuns == 0 ? "不限" : maxRuns)
                + "，连续重复事实=" + stalled
                + "；阶段结束须新鲜身体及目标现场核验，材料与位移仅作阶段进展；总步数、权限、探索预算和原始目标继续沿用。";
    }

}
