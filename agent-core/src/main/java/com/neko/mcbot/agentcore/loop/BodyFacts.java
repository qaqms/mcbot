package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.TreeMap;

/** Stable work facts; time, slot order and tool wear do not prove progress. */
public final class BodyFacts {
    private BodyFacts() { }

    public static String fingerprint(JsonObject state) {
        ItemGoal.count(state, "minecraft:air");
        var items = new TreeMap<String, Integer>();
        for (var value : state.getAsJsonObject("inventory").getAsJsonArray("slots")) {
            var slot = value.getAsJsonObject();
            int count = slot.get("count").getAsInt();
            if (count > 0) items.merge(slot.get("item").getAsString(), count, Math::addExact);
        }
        var point = new JsonObject();
        for (String axis : List.of("x", "y", "z")) {
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
        return items + "|" + point;
    }
}
