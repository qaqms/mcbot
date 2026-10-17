package com.neko.mcbot.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.neko.mcbot.agentcore.loop.SearchPages;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;

/** Task-local exploration evidence, independent of resources, recipes or stop conditions. */
final class ExplorationRoute {
    final int maxSteps, radius;
    private final List<JsonObject> visited = new ArrayList<>();
    private final Set<String> tried = new HashSet<>();
    private JsonObject anchor, current, departure;
    private String dimension;
    private boolean pending;
    private int attempts, regions;
    private double headingX, headingZ;
    private record Coverage(String query, JsonObject center, int radius, long time, JsonObject terrain) { }
    private JsonObject scanTerrain;
    private final List<Coverage> coverage = new ArrayList<>();
    private final SearchPages pages = new SearchPages();
    private String searchQuery = "";
    private int searchUnknown;
    private long now;
    private double selectedCoverage;
    private boolean spatial;
    private final List<JsonObject> undergroundTrail = new ArrayList<>();
    private int undergroundDigs;

    int coverageRegions() { return coverage.size(); }
    double selectedCoverage() { return selectedCoverage; }
    void invalidateCoverage() { coverage.clear(); pages.reset(); scanTerrain = null; }

    void terrainObserved(JsonObject state) {
        var fresh = com.neko.mcbot.agentcore.loop.SpatialFacts.read(state);
        if (fresh == null) return;
        coverage.removeIf(c -> c.terrain() != null
                && !com.neko.mcbot.agentcore.loop.SpatialFacts.retained(c.terrain(), fresh));
        if (scanTerrain != null
                && !com.neko.mcbot.agentcore.loop.SpatialFacts.retained(scanTerrain, fresh)) {
            pages.reset();
            scanTerrain = null;
        }
    }

    void searchPage(JsonObject args, ToolOutcome receipt, long time) {
        now = time;
        coverage.removeIf(c -> time < c.time() || time - c.time() > 120000);
        var names = new ArrayList<String>();
        for (var name : args.getAsJsonArray("targets")) names.add(name.getAsString());
        names.sort(String::compareTo);
        searchQuery = names.toString();
        boolean continued = pages.continues("find_resource", args.toString(), receipt);
        if (!pages.valid()) return;
        var data = receipt.data();
        int offset = integer(data, "offset");
        if (offset == 0) searchUnknown = 0;
        else if (!continued) return;
        var terrain = com.neko.mcbot.agentcore.loop.SpatialFacts.read(data);
        if (offset == 0) scanTerrain = terrain;
        if (data.has("spatial_stable") && !data.get("spatial_stable").getAsBoolean()
                || (scanTerrain == null) != (terrain == null)
                || scanTerrain != null && !scanTerrain.equals(terrain)) {
            pages.reset(); scanTerrain = null; return;
        }
        int missing = integer(data, "unknown"), read = integer(data, "cells_read");
        if (missing < 0 || read < 0 || missing + read != integer(data, "samples_examined")) {
            pages.reset(); return;
        }
        searchUnknown += missing;
        if (integer(data, "next_offset") != -1 || searchUnknown != 0) return;
        var center = data.getAsJsonObject("scan_center").deepCopy();
        int size = integer(data, "radius");
        coverage.removeIf(c -> c.query().equals(searchQuery) && c.center().equals(center));
        if (coverage.size() >= 64) coverage.removeFirst();
        coverage.add(new Coverage(searchQuery, center, size, time, scanTerrain));
    }

    // Coarse volume samples rank future views; they never certify cells as empty.
    private double additionalCoverage(JsonObject center, int size) {
        int fresh = 0, total = 0;
        for (int x = -2; x <= 2; x++) for (int y = -2; y <= 2; y++) for (int z = -2; z <= 2; z++) {
            if (x * x + y * y + z * z > 4) continue;
            var sample = new JsonObject();
            sample.addProperty("x", number(center, "x") + x * size / 2d);
            sample.addProperty("y", number(center, "y") + y * size / 2d);
            sample.addProperty("z", number(center, "z") + z * size / 2d);
            total++;
            if (coverage.stream().noneMatch(c -> c.query().equals(searchQuery)
                    && now >= c.time() && now - c.time() <= 120000
                    && distance(c.center(), sample) <= c.radius() * (double) c.radius())) fresh++;
        }
        return fresh / (double) total;
    }

    ExplorationRoute(int maxSteps, int radius) { this.maxSteps = maxSteps; this.radius = radius; }
    int attempts() { return attempts; }
    int regions() { return regions; }
    boolean exhausted() { return attempts >= maxSteps; }
    void abandon() { pending = false; }
    int remainingSteps() { return Math.max(0, maxSteps - attempts); }
    int remainingDigs() { return Math.max(0, 128 - undergroundDigs); }
    void recordDigs(JsonObject movement) {
        if (!movement.has("removed_blocks")) throw new IllegalArgumentException();
        int count = integer(movement, "removed_blocks");
        if (count < 0 || count > remainingDigs()) throw new IllegalArgumentException();
        undergroundDigs += count;
        if (count > 0) invalidateCoverage();
    }
    JsonObject bounds() {
        if (anchor == null) throw new IllegalArgumentException();
        var result = anchor.deepCopy();
        for (String axis : List.of("x", "y", "z")) result.addProperty(axis, (int) Math.floor(number(anchor, axis)));
        result.addProperty("radius", radius);
        return result;
    }
    boolean tried(JsonObject point) { return tried.contains(key(point)); }
    void beginUnderground(JsonObject state) {
        spatial = true;
        observe(state);
        if (undergroundTrail.isEmpty()) undergroundTrail.add(feet(state));
        else reachedUnderground(state, false);
    }
    private static JsonObject feet(JsonObject state) {
        var point = new JsonObject();
        for (String axis : List.of("x", "y", "z")) point.addProperty(axis, (int) Math.floor(number(state, axis)));
        return point;
    }
    void reachedUnderground(JsonObject state, boolean returning) {
        var point = feet(state);
        if (returning) {
            if (undergroundTrail.size() > 1 && point.equals(undergroundTrail.get(undergroundTrail.size() - 2)))
                undergroundTrail.removeLast();
        } else if (!point.equals(undergroundTrail.getLast())) {
            if (undergroundTrail.size() >= maxSteps + 1)
                throw new IllegalArgumentException("RETURN_TRAIL_LIMIT");
            undergroundTrail.add(point);
        }
    }
    int returnSteps() { return Math.max(0, undergroundTrail.size() - 1); }
    boolean hasUndergroundTrail() { return !undergroundTrail.isEmpty(); }
    JsonObject returnPoint() {
        return returnSteps() == 0 ? null : undergroundTrail.get(undergroundTrail.size() - 2).deepCopy();
    }
    JsonArray trail() {
        var points = new JsonArray();
        undergroundTrail.forEach(p -> points.add(p.deepCopy()));
        return points;
    }
    void attemptReturn(JsonObject point) {
        if (exhausted()) throw new IllegalArgumentException();
        attempts++; departure = current.deepCopy(); pending = true;
    }

    ToolOutcome configurationMismatch() {
        var data = new JsonObject();
        data.addProperty("exploration_attempts", attempts);
        data.addProperty("exploration_max_steps", maxSteps);
        data.addProperty("exploration_remaining_steps", Math.max(0, maxSteps - attempts));
        data.addProperty("exploration_radius", radius);
        data.addProperty("explored_regions", regions);
        return new ToolOutcome(false, "DENIED:同任务gather/explore须保持首次探索步数/半径。"
                + "当前累计移动=" + attempts + "/" + maxSteps + "，剩余="
                + Math.max(0, maxSteps - attempts) + "，范围半径=" + radius
                + "；省略探索参数可沿用原配置，不能重置范围或预算。"
                + "若只需当前工区附近采集，可用gather explore_steps=0，不换区且不改原探索账。", data);
    }

    boolean observe(JsonObject state) {
        terrainObserved(state);
        var point = new JsonObject();
        for (String key : List.of("x", "y", "z")) point.addProperty(key, number(state, key));
        String world = state.get("dimension").getAsString();
        if (anchor == null) { anchor = point.deepCopy(); dimension = world; }
        if (!dimension.equals(world) || scopeDistance(point, anchor) > radius * (double) radius)
            throw new IllegalArgumentException("SCOPE_CHANGED");
        current = point;
        boolean moved = pending && separation(current, departure) >= 16
                && visited.stream().noneMatch(p -> separation(p, current) < 16);
        if (moved) {
            regions++;
            headingX = number(current, "x") - number(departure, "x");
            headingZ = number(current, "z") - number(departure, "z");
        }
        pending = false;
        searchedHere();
        return moved;
    }

    void searchedHere() {
        if (current == null) throw new IllegalArgumentException();
        if (visited.stream().noneMatch(p -> separation(p, current) < 1))
            visited.add(current.deepCopy());
    }

    JsonObject choose(JsonArray points, int inset) {
        if (points == null || points.size() > 8 || current == null) throw new IllegalArgumentException();
        JsonObject best = null;
        double score = Double.NEGATIVE_INFINITY;
        for (var entry : points) {
            var point = entry.getAsJsonObject();
            for (String key : List.of("x", "y", "z")) integer(point, key);
            if (tried.contains(key(point)) || scopeDistance(point, anchor) > Math.pow(Math.max(0, radius - inset), 2)
                    || horizontal(point, current) > Math.pow(inset > 0 ? Math.min(17, Math.max(4, inset + 1)) : 17, 2)
                    || inset > 0 && distance(point, current) > Math.pow(2d * inset, 2)
                    || Math.abs(number(point, "y") - number(current, "y")) > 12) continue;
            double novelty = visited.stream().mapToDouble(p -> separation(p, point)).min().orElse(0);
            if (novelty < 16) continue;
            double dx = number(point, "x") - number(current, "x");
            double dz = number(point, "z") - number(current, "z");
            double length = Math.sqrt(dx * dx + dz * dz);
            double headingLength = Math.sqrt(headingX * headingX + headingZ * headingZ);
            double alignment = headingLength == 0 || length == 0 ? 0
                    : (dx * headingX + dz * headingZ) / (length * headingLength);
            // Prefer additional ground coverage and a steady heading over climbing or zigzagging.
            double gain = inset > 0 ? additionalCoverage(point, inset) : 0;
            double value = Math.sqrt(novelty) + 4 * alignment + 8 * gain
                    - .25 * Math.abs(number(point, "y") - number(current, "y"));
            if (value <= score) continue;
            best = point; score = value; selectedCoverage = gain;
        }
        return best == null ? null : best.deepCopy();
    }

    void attempt(JsonObject point) {
        if (exhausted() || !tried.add(key(point))) throw new IllegalArgumentException();
        attempts++;
        departure = current.deepCopy();
        pending = true;
    }

    boolean within(JsonObject point) { return anchor != null && scopeDistance(point, anchor) <= radius * (double) radius; }
    private double scopeDistance(JsonObject a, JsonObject b) {
        return spatial ? distance(feet(a), feet(b)) : distance(a, b);
    }
    private double separation(JsonObject a, JsonObject b) { return spatial ? distance(a, b) : horizontal(a, b); }
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
    private static double horizontal(JsonObject a, JsonObject b) {
        double dx = number(a, "x") - number(b, "x"), dz = number(a, "z") - number(b, "z");
        return dx * dx + dz * dz;
    }
    private static double distance(JsonObject a, JsonObject b) {
        double sum = 0;
        for (String key : List.of("x", "y", "z")) {
            double delta = number(a, key) - number(b, key);
            sum += delta * delta;
        }
        return sum;
    }
}
