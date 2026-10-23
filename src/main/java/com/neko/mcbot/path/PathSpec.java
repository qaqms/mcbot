package com.neko.mcbot.path;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

/** Per-route constraints are reapplied to search, cached plans and every live edit. */
public record PathSpec(boolean workArrival, boolean clear, boolean underground, int maxDigs,
                       BlockPos anchor, int radius) {
    public static final PathSpec DEFAULT = new PathSpec(false, false, false, Integer.MAX_VALUE, null, 64);

    public static PathSpec parse(JsonObject args) {
        boolean work = false;
        if (args.has("arrival")) {
            var value = args.get("arrival");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                    || !value.getAsString().equals("work") || !args.has("work_target"))
                throw new IllegalArgumentException();
            work = true;
        }
        boolean clear = bool(args, "clear_obstructions");
        boolean underground = bool(args, "underground");
        int digs = args.has("max_digs") ? integer(args, "max_digs") : Integer.MAX_VALUE;
        if (digs < 0) throw new IllegalArgumentException();
        BlockPos anchor = null;
        int radius = 64;
        if (args.has("bounds")) {
            var box = args.getAsJsonObject("bounds");
            if (!box.keySet().equals(java.util.Set.of("x", "y", "z", "radius")))
                throw new IllegalArgumentException();
            anchor = new BlockPos(integer(box, "x"), integer(box, "y"), integer(box, "z"));
            radius = integer(box, "radius");
            if (radius < 1 || radius > 64) throw new IllegalArgumentException();
        }
        return new PathSpec(work, clear, underground, digs, anchor, radius);
    }

    public boolean contains(int x, int y, int z) {
        if (anchor == null) return true;
        double dx = (double) x - anchor.getX(), dy = (double) y - anchor.getY(), dz = (double) z - anchor.getZ();
        return dx * dx + dy * dy + dz * dz <= radius * (double) radius;
    }

    public JsonObject snapshot() {
        var data = new JsonObject();
        data.addProperty("work", workArrival);
        data.addProperty("clear_obstructions", clear);
        data.addProperty("underground", underground);
        data.addProperty("max_digs", maxDigs);
        if (anchor != null) {
            var bounds = new JsonObject();
            bounds.addProperty("x", anchor.getX()); bounds.addProperty("y", anchor.getY());
            bounds.addProperty("z", anchor.getZ()); bounds.addProperty("radius", radius);
            data.add("bounds", bounds);
        }
        return data;
    }

    public static PathSpec restore(JsonObject data) {
        var parsed = parse(data);
        return new PathSpec(bool(data, "work"), parsed.clear, parsed.underground,
                parsed.maxDigs, parsed.anchor, parsed.radius);
    }

    public boolean allowsStep(int fx, int fy, int fz, int x, int y, int z) {
        return !underground || Math.abs(x - fx) + Math.abs(z - fz) == 1 && Math.abs(y - fy) <= 1;
    }

    private static boolean bool(JsonObject args, String key) {
        if (!args.has(key)) return false;
        var value = args.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException();
        return value.getAsBoolean();
    }

    private static int integer(JsonObject args, String key) {
        var value = args.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException();
        return value.getAsBigDecimal().intValueExact();
    }
}
