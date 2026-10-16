package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Set;

/** Bounded stage checkpoints inside one task; neither task budgets nor inventory baselines restart. */
final class GoalContinuation {
    enum Verdict { CONTINUE, NO_PROGRESS, LIMIT }
    private final int maxRuns;
    private final Set<String> observed = new HashSet<>();
    private int completedRuns, stalled;

    GoalContinuation(int maxRuns, JsonObject initial) {
        this.maxRuns = maxRuns;
        observed.add(BodyFacts.fingerprint(initial));
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
        String facts = BodyFacts.fingerprint(state);
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

}
