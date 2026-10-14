package com.neko.mcbot.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Task-local exploration evidence, independent of resources, recipes or stop conditions. */
final class ExplorationRoute {
    final int maxSteps, radius;
    private final List<JsonObject> visited = new ArrayList<>();
    private final Set<String> tried = new HashSet<>();
    private JsonObject anchor, current, departure;
    private String dimension;
    private boolean pending;
    private int attempts, regions;

    ExplorationRoute(int maxSteps, int radius) { this.maxSteps = maxSteps; this.radius = radius; }
    int attempts() { return attempts; }
    int regions() { return regions; }
    boolean exhausted() { return attempts >= maxSteps; }
    void abandon() { pending = false; }

    boolean observe(JsonObject state) {
        var point = new JsonObject();
        for (String key : List.of("x", "y", "z")) point.addProperty(key, number(state, key));
        String world = state.get("dimension").getAsString();
        if (anchor == null) { anchor = point.deepCopy(); dimension = world; }
        if (!dimension.equals(world) || distance(point, anchor) > radius * (double) radius)
            throw new IllegalArgumentException("SCOPE_CHANGED");
        current = point;
        boolean moved = pending && distance(current, departure) >= 16;
        if (moved) regions++;
        pending = false;
        return moved;
    }

    void searchedHere() {
        if (current == null) throw new IllegalArgumentException();
        if (visited.stream().noneMatch(p -> distance(p, current) < 1))
            visited.add(current.deepCopy());
    }

    JsonObject choose(JsonArray points, int inset) {
        if (points == null || points.size() > 8 || current == null) throw new IllegalArgumentException();
        JsonObject best = null;
        double score = -1;
        for (var entry : points) {
            var point = entry.getAsJsonObject();
            for (String key : List.of("x", "y", "z")) integer(point, key);
            if (tried.contains(key(point)) || distance(point, anchor) > Math.pow(Math.max(0, radius - inset), 2)
                    || distance(point, current) > 256) continue;
            double novelty = visited.stream().mapToDouble(p -> distance(p, point)).min().orElse(0);
            if (novelty < 16 || novelty <= score) continue;
            best = point; score = novelty;
        }
        return best == null ? null : best.deepCopy();
    }

    void attempt(JsonObject point) {
        if (exhausted() || !tried.add(key(point))) throw new IllegalArgumentException();
        attempts++;
        departure = current.deepCopy();
        pending = true;
    }

    boolean within(JsonObject point) { return anchor != null && distance(point, anchor) <= radius * (double) radius; }
    static double number(JsonObject data, String key) {
        if (!data.has(key) || !data.get(key).isJsonPrimitive() || !data.get(key).getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException();
        double value = data.get(key).getAsDouble();
        if (!Double.isFinite(value)) throw new IllegalArgumentException();
        return value;
    }
    static int integer(JsonObject data, String key) {
        number(data, key);
        return data.get(key).getAsBigDecimal().intValueExact();
    }
    private static String key(JsonObject p) { return integer(p, "x") + ":" + integer(p, "y") + ":" + integer(p, "z"); }
    private static double distance(JsonObject a, JsonObject b) {
        double sum = 0;
        for (String key : List.of("x", "y", "z")) {
            double delta = number(a, key) - number(b, key);
            sum += delta * delta;
        }
        return sum;
    }
}
