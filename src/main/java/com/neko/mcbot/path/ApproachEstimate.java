package com.neko.mcbot.path;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Optional search guidance on a relaxed feet grid with the body's adjacent-step envelope. No support, sight or edit
 * permission is inferred here. The actual transition remains the only admission gate.
 * Each call performs one seed probe or queue operation inside DigAStar's existing time slice.
 */
final class ApproachEstimate {
    private record Work(long cell, double price, Work next) { }
    private record Entry(long cell, double cost, int tie, Work work) { }
    private final DigSampler terrain;
    private final NavigationGoal goal;
    private final NavigationGoal.Bounds bounds;
    private final int sx, sy, sz, cap;
    private final Map<Long, Double> offered = new HashMap<>();
    private final Map<Long, Entry> settled = new HashMap<>();
    private final Map<Integer, int[]> workLevels = new HashMap<>();
    private final Set<Long> sealed = new HashSet<>();
    private final PriorityQueue<Entry> queue = new PriorityQueue<>(Comparator
            .comparingDouble(Entry::cost).thenComparingInt(Entry::tie).thenComparingLong(Entry::cell));
    private int x, y, z, scans, admitted, expanded;
    private int workStances;
    private boolean seeding = true, done;
    private double frontier;

    ApproachEstimate(DigSampler terrain, NavigationGoal goal, int sx, int sy, int sz, int cap) {
        this.terrain = terrain;
        this.goal = goal;
        this.bounds = goal.estimateBounds();
        this.sx = sx; this.sy = sy; this.sz = sz; this.cap = cap;
        done = bounds == null || cap == 0;
        if (!done) { x = bounds.minX(); y = bounds.minY(); z = bounds.minZ(); }
    }

    boolean done() { return done; }
    int expanded() { return expanded; }
    int admitted() { return admitted; }
    int seedProbes() { return scans; }
    int workStances() { return workStances; }

    void advance() {
        if (done) return;
        if (seeding) {
            if (scans >= 8192) { disable(); return; }
            scans++;
            if (terrain.inBounds(x, y, z) && goal.remaining(x, y, z) == 0
                    && (!goal.estimateWorkStances() || goal.reached(x, y, z, TerrainEdits.EMPTY))) {
                if (goal.estimateWorkStances()) {
                    workStances++;
                    var span = workLevels.computeIfAbsent(y, ignored -> new int[]{x, x, z, z});
                    span[0] = Math.min(span[0], x); span[1] = Math.max(span[1], x);
                    span[2] = Math.min(span[2], z); span[3] = Math.max(span[3], z);
                }
                offer(x, y, z, 0, null);
            }
            if (++z > bounds.maxZ()) {
                z = bounds.minZ();
                if (++y > bounds.maxY()) { y = bounds.minY(); if (++x > bounds.maxX()) seeding = false; }
            }
            return;
        }
        Entry entry = queue.poll();
        if (entry == null) { finish(); frontier = 0; return; }
        if (sealed.contains(entry.cell()) || entry.cost() != offered.get(entry.cell())) return;
        frontier = entry.cost();
        if (expanded >= cap) { finish(); return; }
        sealed.add(entry.cell());
        settled.put(entry.cell(), entry);
        expanded++;
        int cx = DigAStar.unpackX(entry.cell()), cy = DigAStar.unpackY(entry.cell()),
                cz = DigAStar.unpackZ(entry.cell());
        if (cx == sx && cy == sy && cz == sz) { finish(); return; }
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dy == 0 && dz == 0) continue;
            int px = cx + dx, py = cy + dy, pz = cz + dz;
            if (!terrain.inBounds(px, py, pz) || !terrain.allowsStep(px, py, pz, cx, cy, cz)) continue;
            // Only newly occupied cells carry a price; revisiting edited cells is discounted by the caller.
            double cost = goal.estimateWorkStances() ? DigAStar.H_UNIT : 0;
            Work work = entry.work();
            boolean departureFloor = false;
            for (int cellY = cy; cellY <= Math.max(py, cy) + 1; cellY++) {
                if (px != cx || pz != cz || cellY < py || cellY > py + 1) {
                    double price = price(cx, cellY, cz);
                    if (px == cx && pz == cz && cellY == py - 1 && price > 0) {
                        departureFloor = true;
                        break;
                    }
                    cost += price;
                    if (price > 0) work = new Work(DigAStar.pack(cx, cellY, cz), price, work);
                }
            }
            if (departureFloor) continue;
            offer(px, py, pz, entry.cost() + cost, work);
            if (done) return;
        }
    }

    private void offer(int px, int py, int pz, double cost, Work work) {
        if (!terrain.inBounds(px, py, pz)) return;
        long cell = DigAStar.pack(px, py, pz);
        if (sealed.contains(cell) || cost >= offered.getOrDefault(cell, Double.POSITIVE_INFINITY)) return;
        // Reserve most of the original storage ceiling for actual route states.
        if (admitted >= DigAStar.MAX_STORED_NODES / 4) {
            if (seeding) disable();
            else { frontier = Math.min(frontier, cost); finish(); }
            return;
        }
        offered.put(cell, cost);
        admitted++;
        queue.add(new Entry(cell, cost, Math.abs(px - sx) + Math.abs(py - sy) + Math.abs(pz - sz), work));
    }

    private double price(int px, int py, int pz) {
        if (!terrain.inBounds(px, py, pz) || !terrain.known(px, py, pz) || terrain.passable(px, py, pz)) return 0;
        double seconds = terrain.digSeconds(px, py, pz);
        return DigSampler.feasibleDig(seconds) ? DigAStar.digCost(seconds) : 0;
    }

    private void disable() {
        settled.clear();
        workLevels.clear();
        frontier = 0;
        finish();
    }

    private void finish() {
        done = true;
        offered.clear();
        sealed.clear();
        queue.clear();
    }

    double cost(int px, int py, int pz, TerrainEdits edits) {
        // This is guidance for an already weighted search, not an optimality bound or a proof of no route.
        Entry entry = settled.get(DigAStar.pack(px, py, pz));
        // An unrelated removal cannot erase travel to a verified work stance outside this truncated field.
        if (entry == null) {
            if (!goal.estimateWorkStances()) return edits.removed().isEmpty() ? frontier : 0;
            // Truncation must not flatten every unvisited floor into the same work distance.
            // These per-level envelopes only guide ordering; they cannot certify an arrival.
            double distance = Double.POSITIVE_INFINITY;
            for (var level : workLevels.entrySet()) {
                var span = level.getValue();
                double dx = Math.max(0, Math.max((double) span[0] - px, (double) px - span[1]));
                double dz = Math.max(0, Math.max((double) span[2] - pz, (double) pz - span[3]));
                distance = Math.min(distance, Math.max(Math.abs((double) py - level.getKey()), Math.max(dx, dz)));
            }
            return Double.isFinite(distance) ? Math.max(frontier, DigAStar.H_UNIT * distance) : frontier;
        }
        double cost = entry.cost();
        int inspected = 0;
        for (Work work = entry.work(); work != null && inspected++ < 128; work = work.next())
            if (edits.removed().contains(work.cell())) cost -= work.price();
        return Math.max(0, cost);
    }
}
