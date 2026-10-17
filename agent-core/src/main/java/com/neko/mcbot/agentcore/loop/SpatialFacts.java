package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;

/** Chunk stamps describe observed terrain, never permissions or proof of reachability. */
public final class SpatialFacts {
    private SpatialFacts() { }

    public static JsonObject read(JsonObject data) {
        try {
            var facts = data.getAsJsonObject("spatial_facts");
            if (facts == null || !facts.get("dimension").getAsJsonPrimitive().isString()
                    || facts.get("dimension").getAsString().length() > 128) return null;
            var chunks = facts.getAsJsonObject("chunks");
            if (chunks == null || chunks.isEmpty() || chunks.size() > 81) return null;
            for (var entry : chunks.entrySet()) {
                String[] axes = entry.getKey().split(",", -1);
                if (axes.length != 2) return null;
                for (String axis : axes) {
                    int number = Integer.parseInt(axis);
                    if (Math.abs((long) number) > 1_875_001 || !Integer.toString(number).equals(axis)) return null;
                }
                var value = entry.getValue();
                if (!value.isJsonNull() && (!value.isJsonPrimitive()
                        || !value.getAsJsonPrimitive().isString()
                        || value.getAsString().isEmpty() || value.getAsString().length() > 96)) return null;
            }
            return facts.deepCopy();
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /** Missing/unloaded evidence cannot prove an improvement and does not replenish attempts. */
    public static boolean changed(JsonObject previous, JsonObject fresh) {
        if (previous == null || fresh == null
                || !previous.get("dimension").equals(fresh.get("dimension"))) return false;
        var chunks = fresh.getAsJsonObject("chunks");
        for (var entry : previous.getAsJsonObject("chunks").entrySet()) {
            var next = chunks.get(entry.getKey());
            if (next != null && !next.isJsonNull() && !entry.getValue().isJsonNull()
                    && !next.equals(entry.getValue())) return true;
        }
        return false;
    }

    /** Coverage is usable only while every previously observed chunk is still known and identical. */
    public static boolean retained(JsonObject previous, JsonObject fresh) {
        if (previous == null || fresh == null
                || !previous.get("dimension").equals(fresh.get("dimension"))) return false;
        var chunks = fresh.getAsJsonObject("chunks");
        for (var entry : previous.getAsJsonObject("chunks").entrySet()) {
            if (entry.getValue().isJsonNull() || !entry.getValue().equals(chunks.get(entry.getKey()))) return false;
        }
        return true;
    }

    public static JsonObject forWork(JsonObject snapshot, String tool, JsonObject args) {
        if (snapshot == null || !tool.equals("break_block")) return snapshot;
        try {
            int x = args.get("x").getAsBigDecimal().intValueExact();
            int z = args.get("z").getAsBigDecimal().intValueExact();
            var selected = new JsonObject();
            for (var entry : snapshot.getAsJsonObject("chunks").entrySet()) {
                String[] axes = entry.getKey().split(",");
                int cx = Integer.parseInt(axes[0]), cz = Integer.parseInt(axes[1]);
                if (cx >= (x - 6 >> 4) && cx <= (x + 6 >> 4)
                        && cz >= (z - 6 >> 4) && cz <= (z + 6 >> 4))
                    selected.add(entry.getKey(), entry.getValue().deepCopy());
            }
            var result = new JsonObject();
            result.add("dimension", snapshot.get("dimension"));
            result.add("chunks", selected);
            return result;
        } catch (RuntimeException invalid) {
            return null;
        }
    }
}
