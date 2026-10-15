package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;

/** A strictly advancing server page chain is one search, without forgiving repeated queries. */
public final class SearchPages {
    private String query;
    private int next = -1;
    private boolean valid;

    public void reset() { query = null; next = -1; }
    public boolean valid() { return valid; }

    public boolean continues(String name, String arguments, ToolOutcome receipt) {
        valid = false;
        if (!name.equals("find_resource")) {
            if (name.equals("scan_area")) reset();
            return false;
        }
        try {
            if (!receipt.ok()) { reset(); return false; }
            var args = JsonParser.parseString(arguments).getAsJsonObject();
            var data = receipt.data();
            int offset = integer(data, "offset"), radius = integer(data, "radius");
            int total = integer(data, "samples_planned"), examined = integer(data, "samples_examined");
            int following = integer(data, "next_offset");
            var center = data.getAsJsonObject("scan_center");
            for (String axis : java.util.List.of("x", "y", "z")) integer(center, axis);
            if (radius < 1 || radius > 16 || total < 1 || total > 17077
                    || offset < 0 || offset > 16384 || offset % 4096 != 0 || offset >= total
                    || examined != Math.min(4096, total - offset)
                    || following != (offset + examined < total ? offset + examined : -1)
                    || offset != (args.has("offset") ? integer(args, "offset") : 0)
                    || radius != (args.has("r") ? integer(args, "r") : 8)
                    || offset > 0 && !center.equals(args.getAsJsonObject("scan_center"))
                    || !args.has("targets") || !args.get("targets").isJsonArray())
                throw new IllegalArgumentException();
            String identity = args.get("targets") + "|" + radius + "|" + center + "|" + total;
            valid = true;
            boolean continuation = offset > 0 && offset == next && identity.equals(query);
            if (offset == 0 || continuation) {
                query = following < 0 ? null : identity;
                next = following;
            } else reset();
            return continuation;
        } catch (RuntimeException invalid) {
            reset();
            return false;
        }
    }

    private static int integer(JsonObject data, String key) {
        var value = data.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException();
        return value.getAsBigDecimal().intValueExact();
    }
}
