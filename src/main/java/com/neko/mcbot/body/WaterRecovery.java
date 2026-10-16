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
    }
    private record Offset(int x, int y, int z) { }
    private record Node(Offset offset, Node parent) { }
    private static final int PROBES = 128, RADIUS = 6, HEIGHT = 8, EPISODE_TICKS = 600;
    private static final List<Offset> DIRECTIONS = List.of(
            new Offset(0, 1, 0), new Offset(1, 0, 0), new Offset(0, 0, 1),
            new Offset(-1, 0, 0), new Offset(0, 0, -1));
    private List<Vec3> route = List.of();
    private int cooldown, age, cursor;
    private String state = "idle";
    private Object world;

    String state() { return state; }

    MovementInput.Frame tick(Access body) {
        if (world != body.worldIdentity()) {
            reset();
            world = body.worldIdentity();
        }
        if (!body.submerged() || !body.allowed()) {
            reset();
            state = body.submerged() ? "suppressed" : "idle";
            return null;
        }
        if (++age > EPISODE_TICKS) {
            route = List.of();
            state = "limit";
            return MovementInput.Frame.IDLE;
        }
        Vec3 here = body.position();
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
        if (cursor < route.size()) {
            Vec3 next = route.get(cursor);
            state = "seeking_air";
            double dx = next.x - here.x, dz = next.z - here.z;
            double scale = Math.max(1, Math.hypot(dx, dz));
            return new MovementInput.Frame(dx / scale, dz / scale, true);
        }
        // Deep open water may have no surface within the local search height.
        if (body.passage(here, here.add(0, .5, 0))) {
            state = "rising";
            return new MovementInput.Frame(0, 0, true);
        }
        state = "trapped_or_unknown";
        return MovementInput.Frame.IDLE;
    }

    private void reset() {
        route = List.of();
        cooldown = age = cursor = 0;
    }

    private static List<Vec3> find(Access body) {
        Vec3 anchor = body.position();
        var queue = new ArrayDeque<Node>();
        var seen = new HashSet<Offset>();
        var origin = new Offset(0, 0, 0);
        queue.add(new Node(origin, null));
        seen.add(origin);
        int probes = 1;
        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            for (Offset delta : DIRECTIONS) {
                var offset = new Offset(node.offset.x + delta.x, node.offset.y + delta.y, node.offset.z + delta.z);
                if (Math.abs(offset.x) > RADIUS || Math.abs(offset.z) > RADIUS || offset.y > HEIGHT
                        || seen.contains(offset)) continue;
                if (probes++ >= PROBES) return List.of();
                Vec3 point = at(anchor, offset);
                if (!body.passage(at(anchor, node.offset), point)) continue;
                Medium medium = body.eyesAt(point);
                if (medium == Medium.BLOCKED) continue;
                // A blocked edge does not make the destination unreachable from another side.
                seen.add(offset);
                Node child = new Node(offset, node);
                if (medium == Medium.AIR) {
                    var reversed = new ArrayList<Vec3>();
                    for (Node n = child; n.parent != null; n = n.parent) reversed.add(at(anchor, n.offset));
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
