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
    private Object task, world;
    private long sequence;
    private boolean truncated, gap;

    public void bind(Object nextTask, Object nextWorld) {
        if (!java.util.Objects.equals(task, nextTask) || world != nextWorld) {
            points.clear(); edits.clear(); truncated = gap = false;
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
    }

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
