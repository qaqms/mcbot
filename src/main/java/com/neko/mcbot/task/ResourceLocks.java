package com.neko.mcbot.task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bounded world regions and companion bodies, held until the actual terminal receipt. */
public final class ResourceLocks {
    public record Region(String dimension, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public boolean overlaps(Region other) {
            return dimension.equals(other.dimension) && minX <= other.maxX && maxX >= other.minX
                    && minY <= other.maxY && maxY >= other.minY && minZ <= other.maxZ && maxZ >= other.minZ;
        }
    }

    public record Target(String dimension, UUID entity) {
    }

    private record Held(UUID body, List<Region> regions, List<Target> targets) {
    }

    public final class Lease implements AutoCloseable {
        private final UUID id;
        private boolean closed;
        private Lease(UUID id) { this.id = id; }

        public boolean extend(List<Region> regions) {
            if (closed || conflicts(id, null, regions)) return false;
            Held current = held.get(id);
            var all = new ArrayList<>(current.regions());
            for (Region region : regions) if (!all.contains(region)) all.add(region);
            if (all.size() > 512) return false;
            held.put(id, new Held(current.body(), List.copyOf(all), current.targets()));
            return true;
        }

        public boolean claim(Target target) {
            if (closed) return false;
            Held current = held.get(id);
            if (current.targets().contains(target)) return true;
            if (current.targets().size() >= 256 || held.entrySet().stream().anyMatch(entry ->
                    !entry.getKey().equals(id) && entry.getValue().targets().contains(target))) return false;
            var targets = new ArrayList<>(current.targets());
            targets.add(target);
            held.put(id, new Held(current.body(), current.regions(), List.copyOf(targets)));
            return true;
        }

        @Override public void close() {
            if (!closed) {
                closed = true;
                held.remove(id);
            }
        }
    }

    private final Map<UUID, Held> held = new HashMap<>();

    public Lease acquire(UUID body, List<Region> regions) {
        if (regions.size() > 256 || conflicts(null, body, regions)) return null;
        UUID id = UUID.randomUUID();
        held.put(id, new Held(body, List.copyOf(regions), List.of()));
        return new Lease(id);
    }

    private boolean conflicts(UUID exclude, UUID body, List<Region> regions) {
        for (var entry : held.entrySet()) {
            if (entry.getKey().equals(exclude)) continue;
            if (body != null && body.equals(entry.getValue().body())) return true;
            for (Region region : regions) {
                for (Region other : entry.getValue().regions()) if (region.overlaps(other)) return true;
            }
        }
        return false;
    }

    public int size() { return held.size(); }
}
