package com.neko.mcbot.body;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** A bounded body reflex, independent of model requests and dry-land work plans. */
final class WaterRecovery {
    enum Medium { WATER, AIR, BLOCKED }
    interface Access {
        boolean allowed();
        boolean submerged();
        default Object worldIdentity() { return this; }
        Vec3 position();
        Medium eyesAt(Vec3 feet);
        boolean passage(Vec3 from, Vec3 to);
        default boolean withinScope(Vec3 feet) { return true; }
    }
    private record Offset(int x, int y, int z) { }
    private record Node(Offset offset, Node parent) { }
    private static final int PROBES = 128, RADIUS = 6, HEIGHT = 8, EPISODE_TICKS = 600;
    private static final List<Offset> DIRECTIONS = List.of(
            new Offset(0, 1, 0), new Offset(1, 0, 0), new Offset(0, 0, 1),
            new Offset(-1, 0, 0), new Offset(0, 0, -1));
    private List<Vec3> route = List.of();
    private int cooldown, age, cursor;
    private int probes, searches;
    private long searchNanos;
    private Vec3 anchor;
    private String state = "idle";
    private Object world;

    String state() { return state; }

    com.google.gson.JsonObject observation() {
        var data = new com.google.gson.JsonObject();
        data.addProperty("state", state);
        data.addProperty("elapsed_ticks", age);
        data.addProperty("cap_ticks", EPISODE_TICKS);
        data.addProperty("search_probes", probes);
        data.addProperty("searches", searches);
        data.addProperty("search_nanos", searchNanos);
        data.addProperty("max_search_probes", 4096);
        data.addProperty("max_search_nanos", 400_000_000L);
        if (anchor != null) {
            var point = new com.google.gson.JsonObject();
            point.addProperty("x", anchor.x); point.addProperty("y", anchor.y); point.addProperty("z", anchor.z);
            data.add("anchor", point);
        }
        return data;
    }

    MovementInput.Frame tick(Access body) {
        if (world != body.worldIdentity()) {
            reset();
            world = body.worldIdentity();
        }
        if (!body.submerged()) {
            reset();
            state = "idle";
            return null;
        }
        age = Math.min(EPISODE_TICKS + 1, age + 1);
        if (!body.allowed()) {
            route = List.of();
            state = "suppressed";
            return null;
        }
        if (anchor == null) anchor = body.position();
        if (age > EPISODE_TICKS) {
            route = List.of();
            state = "limit";
            return MovementInput.Frame.IDLE;
        }
        Vec3 here = body.position();
        if (!within(body, here)) {
            route = List.of();
            state = "scope_limit";
            return MovementInput.Frame.IDLE;
        }
        if (probes >= 4096 || searchNanos >= 400_000_000L) {
            state = "search_limit";
            return MovementInput.Frame.IDLE;
        }
        if (cursor < route.size() && here.distanceToSqr(route.get(cursor)) > 4) {
            route = List.of();
            cooldown = 0;
        }
        while (cursor < route.size()) {
            Vec3 next = route.get(cursor);
            if (Math.hypot(here.x - next.x, here.z - next.z) > .3 || here.y < next.y - .25) break;
            cursor++;
        }
        if (--cooldown <= 0 || cursor < route.size() && !body.passage(here, route.get(cursor))) {
            route = find(body);
            cursor = 0;
            cooldown = 20;
        }
        if (probes >= 4096 || searchNanos >= 400_000_000L) {
            state = "search_limit";
            return MovementInput.Frame.IDLE;
        }
        if (cursor < route.size()) {
            Vec3 next = route.get(cursor);
            state = "seeking_air";
            double dx = next.x - here.x, dz = next.z - here.z;
            double scale = Math.max(1, Math.hypot(dx, dz));
            return new MovementInput.Frame(dx / scale, dz / scale, true);
        }
        // Deep open water may have no surface within the local search height.
        if (within(body, here.add(0, .5, 0)) && body.passage(here, here.add(0, .5, 0))) {
            state = "rising";
            return new MovementInput.Frame(0, 0, true);
        }
        state = "trapped_or_unknown";
        return MovementInput.Frame.IDLE;
    }

    private void reset() {
        route = List.of();
        cooldown = age = cursor = 0;
        probes = searches = 0;
        searchNanos = 0;
        anchor = null;
    }

    private boolean within(Access body, Vec3 feet) {
        return Math.abs(feet.x - anchor.x) <= RADIUS && Math.abs(feet.z - anchor.z) <= RADIUS
                && feet.y >= anchor.y - .5 && feet.y <= anchor.y + HEIGHT && body.withinScope(feet);
    }

    private List<Vec3> find(Access body) {
        long started = System.nanoTime();
        searches++;
        try {
            return search(body, started);
        } finally {
            searchNanos += Math.max(0, System.nanoTime() - started);
        }
    }

    private List<Vec3> search(Access body, long started) {
        Vec3 origin = body.position();
        var queue = new ArrayDeque<Node>();
        var seen = new HashSet<Offset>();
        var zero = new Offset(0, 0, 0);
        queue.add(new Node(zero, null));
        seen.add(zero);
        int examined = 1;
        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            for (Offset delta : DIRECTIONS) {
                var offset = new Offset(node.offset.x + delta.x, node.offset.y + delta.y, node.offset.z + delta.z);
                if (Math.abs(offset.x) > RADIUS || Math.abs(offset.z) > RADIUS || offset.y > HEIGHT
                        || seen.contains(offset)) continue;
                if (examined++ >= PROBES || probes >= 4096
                        || searchNanos + System.nanoTime() - started >= 400_000_000L) return List.of();
                probes++;
                Vec3 point = at(origin, offset);
                if (!within(body, point) || !body.passage(at(origin, node.offset), point)) continue;
                Medium medium = body.eyesAt(point);
                if (medium == Medium.BLOCKED) continue;
                // A blocked edge does not make the destination unreachable from another side.
                seen.add(offset);
                Node child = new Node(offset, node);
                if (medium == Medium.AIR) {
                    var reversed = new ArrayList<Vec3>();
                    for (Node n = child; n.parent != null; n = n.parent) reversed.add(at(origin, n.offset));
                    return List.copyOf(reversed.reversed());
                }
                queue.addLast(child);
            }
        }
        return List.of();
    }

    private static Vec3 at(Vec3 anchor, Offset offset) {
        return anchor.add(offset.x, offset.y, offset.z);
    }
}
