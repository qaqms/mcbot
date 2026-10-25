package com.neko.mcbot.path;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** Actual grounded observations only. Returned coordinates are candidates, never cached routes. */
public final class NavigationTrail {
    public static final int LIMIT = 256;
    public record Point(long id, int x, int y, int z, double feetY, PathSpec scope) { }
    private final List<Point> points = new ArrayList<>();
    private final List<JsonObject> edits = new ArrayList<>();
    private final java.util.Set<net.minecraft.core.BlockPos> passage = new java.util.HashSet<>();
    private Object task, world;
    private long sequence;
    private boolean truncated, gap;

    public boolean boundTo(Object expected, Object expectedWorld) { return task == expected && world == expectedWorld; }
    public void continueTask(Object expected, Object next, Object nextWorld) {
        if (!boundTo(expected, nextWorld)) throw new IllegalArgumentException("trail_session_changed");
        task = next;
    }

    public void bind(Object nextTask, Object nextWorld) {
        if (task != nextTask || world != nextWorld) {
            points.clear(); edits.clear(); passage.clear(); truncated = gap = false;
            task = nextTask; world = nextWorld;
        }
    }

    public void observe(int x, int y, int z, double feetY, boolean grounded) {
        observe(x, y, z, feetY, grounded, PathSpec.DEFAULT);
    }

    public void observe(int x, int y, int z, double feetY, boolean grounded, PathSpec scope) {
        if (task == null || !grounded || !Double.isFinite(feetY)) return;
        if (!points.isEmpty()) {
            var previous = points.getLast();
            if (previous.x == x && previous.y == y && previous.z == z
                    && Math.abs(previous.feetY - feetY) < 1e-5) return;
            if (Math.abs(previous.x - (long) x) > 1 || Math.abs(previous.z - (long) z) > 1
                    || Math.abs(previous.feetY - feetY) > 1.26) gap = true;
        }
        if (points.size() >= LIMIT) {
            truncated = true;
            return;
        }
        points.add(new Point(++sequence, x, y, z, feetY, scope));
        int bottom = (int) Math.floor(feetY), top = (int) Math.ceil(feetY + 1.8) - 1;
        for (int height = bottom - 1; height <= top; height++)
            passage.add(new net.minecraft.core.BlockPos(x, height, z));
    }
    public boolean protectsPassage(net.minecraft.core.BlockPos cell) { return passage.contains(cell); }
    public boolean passageComplete() { return !truncated && !gap; }

    public void edited(String operation, int x, int y, int z) {
        if (task == null) return;
        if (!operation.equals("dig") && !operation.equals("place")) throw new IllegalArgumentException();
        if (edits.size() >= LIMIT) { truncated = true; return; }
        var cell = new JsonObject();
        cell.addProperty("operation", operation); cell.addProperty("x", x);
        cell.addProperty("y", y); cell.addProperty("z", z);
        edits.add(cell);
    }

    public Point point(long id) {
        return points.stream().filter(p -> p.id == id).findFirst().orElse(null);
    }

    public JsonObject snapshot() {
        var data = new JsonObject();
        var rows = new JsonArray();
        var scopes = new java.util.ArrayList<PathSpec>();
        for (var p : points) {
            if (!scopes.contains(p.scope)) scopes.add(p.scope);
            var row = new JsonArray();
            row.add(p.id); row.add(p.x); row.add(p.y); row.add(p.z); row.add(p.feetY); row.add(scopes.indexOf(p.scope));
            rows.add(row);
        }
        var specs = new JsonArray(); scopes.forEach(s -> specs.add(s.snapshot()));
        data.add("points", rows); data.add("scopes", specs);
        var changes = new JsonArray(); edits.forEach(e -> changes.add(e.deepCopy()));
        data.add("edits", changes);
        data.addProperty("sequence", sequence); data.addProperty("truncated", truncated); data.addProperty("gap", gap);
        return data;
    }

    public void restore(JsonObject data) {
        if (task == null || !points.isEmpty() || !edits.isEmpty()) throw new IllegalArgumentException();
        var rows = data.getAsJsonArray("points");
        var specs = data.getAsJsonArray("scopes");
        var changes = data.getAsJsonArray("edits");
        if (rows.size() > LIMIT || specs.size() > LIMIT || changes.size() > LIMIT) throw new IllegalArgumentException();
        long previous = 0;
        for (var value : rows) {
            var row = value.getAsJsonArray();
            if (row.size() != 6) throw new IllegalArgumentException();
            long id = row.get(0).getAsBigDecimal().longValueExact();
            int x = row.get(1).getAsBigDecimal().intValueExact(), y = row.get(2).getAsBigDecimal().intValueExact(),
                    z = row.get(3).getAsBigDecimal().intValueExact();
            double feet = row.get(4).getAsDouble();
            if (id <= previous || !Double.isFinite(feet) || feet < y || feet > y + 1.01) throw new IllegalArgumentException();
            var spec = PathSpec.restore(specs.get(row.get(5).getAsBigDecimal().intValueExact()).getAsJsonObject());
            observe(x, y, z, feet, true, spec);
            points.set(points.size() - 1, new Point(id, x, y, z, feet, spec));
            previous = id;
        }
        sequence = data.get("sequence").getAsBigDecimal().longValueExact();
        if (sequence < previous || sequence < 0) throw new IllegalArgumentException();
        for (var value : changes) {
            var row = value.getAsJsonObject();
            edited(row.get("operation").getAsString(), row.get("x").getAsBigDecimal().intValueExact(),
                    row.get("y").getAsBigDecimal().intValueExact(), row.get("z").getAsBigDecimal().intValueExact());
        }
        for (String key : List.of("truncated", "gap"))
            if (!data.getAsJsonPrimitive(key).isBoolean()) throw new IllegalArgumentException();
        truncated |= data.get("truncated").getAsBoolean();
        gap |= data.get("gap").getAsBoolean();
    }

    public JsonObject report() {
        var data = new JsonObject();
        var samples = new JsonArray();
        for (int i = 0; i < points.size(); i++) {
            if (points.size() > 16 && i > 0 && i < points.size() - 15) continue;
            var p = points.get(i);
            var sample = new JsonObject();
            sample.addProperty("id", p.id); sample.addProperty("x", p.x);
            sample.addProperty("y", p.y); sample.addProperty("z", p.z);
            sample.addProperty("feet_y", p.feetY);
            sample.addProperty("underground", p.scope.underground());
            if (p.scope.anchor() != null) {
                var bounds = new JsonObject();
                bounds.addProperty("x", p.scope.anchor().getX());
                bounds.addProperty("y", p.scope.anchor().getY());
                bounds.addProperty("z", p.scope.anchor().getZ());
                bounds.addProperty("radius", p.scope.radius());
                sample.add("bounds", bounds);
            }
            samples.add(sample);
        }
        data.add("waypoints", samples);
        data.addProperty("recorded_points", points.size());
        data.addProperty("samples_omitted", Math.max(0, points.size() - samples.size()));
        data.addProperty("recording_truncated", truncated);
        data.addProperty("has_observation_gap", gap);
        data.addProperty("return_guaranteed", false);
        data.addProperty("requires_fresh_navigation", true);
        var changes = new JsonArray();
        edits.subList(Math.max(0, edits.size() - 16), edits.size()).forEach(e -> changes.add(e.deepCopy()));
        data.add("actual_edits", changes);
        data.addProperty("recorded_edits", edits.size());
        data.addProperty("edit_samples_omitted", Math.max(0, edits.size() - changes.size()));
        return data;
    }
}
