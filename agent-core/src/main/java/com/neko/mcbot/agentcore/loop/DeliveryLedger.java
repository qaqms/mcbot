package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/** Task-local net transfers. Unobserved mutations cannot buy delivery credit. */
public final class DeliveryLedger {
    private final Map<String, Long> balances = new HashMap<>();
    private boolean complete = true;
    private int pending;

    public void started() { pending++; }

    public void terminal(JsonObject args, ToolExecutor.ToolOutcome outcome) {
        if (pending > 0) pending--;
        try {
            JsonObject data = outcome.data();
            if (!WorldGoal.flag(data, "transfer_evidence_known")) throw new IllegalArgumentException();
            String item = ItemGoal.Target.parse("{\"item\":" + args.get("item") + ",\"count\":1}").item();
            if (!item.equals(WorldGoal.text(data, "item"))) throw new IllegalArgumentException();
            String direction = args.has("dir") ? WorldGoal.text(args, "dir") : "out";
            if (!direction.equals(WorldGoal.text(data, "dir"))) throw new IllegalArgumentException();
            for (String axis : java.util.List.of("x", "y", "z"))
                if (WorldGoal.integer(args, axis) != WorldGoal.integer(data, axis)) throw new IllegalArgumentException();
            int moved = WorldGoal.integer(data, "moved_items");
            int before = WorldGoal.integer(data, "container_count_before"), after = WorldGoal.integer(data, "container_count_after");
            if (moved < 0 || before < 0 || after < 0
                    || (direction.equals("in") ? (long) after - before : (long) before - after) != moved)
                throw new IllegalArgumentException();
            String key = key(data);
            if (!balances.containsKey(key) && balances.size() >= 128) throw new IllegalArgumentException();
            balances.put(key, Math.addExact(balances.getOrDefault(key, 0L), direction.equals("in") ? moved : -moved));
        } catch (RuntimeException unknown) {
            complete = false;
        }
    }

    public void describe(JsonObject evidence) {
        evidence.addProperty("delivery_ledger_complete", complete && pending == 0);
        evidence.addProperty("delivery_balance", balances.getOrDefault(key(evidence), 0L));
    }

    public JsonObject snapshot() {
        if (!complete || pending != 0) throw new IllegalStateException("Incomplete transfers");
        var data = new JsonObject();
        balances.forEach(data::addProperty);
        return data;
    }

    public static DeliveryLedger restore(JsonObject data) {
        if (data == null || data.size() > 128) throw new IllegalArgumentException();
        var ledger = new DeliveryLedger();
        for (var entry : data.entrySet()) {
            if (entry.getKey().length() > 512 || !entry.getValue().isJsonPrimitive()
                    || !entry.getValue().getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
            long value = entry.getValue().getAsBigDecimal().longValueExact();
            if (Math.abs(value) > 1_000_000_000L || value == Long.MIN_VALUE) throw new IllegalArgumentException();
            ledger.balances.put(entry.getKey(), value);
        }
        return ledger;
    }

    private static String key(JsonObject data) {
        return WorldGoal.text(data, "dimension") + "|" + WorldGoal.text(data, "container_id") + "|"
                + WorldGoal.integer(data, "x") + "," + WorldGoal.integer(data, "y") + "," + WorldGoal.integer(data, "z")
                + "|" + WorldGoal.text(data, "item");
    }
}
