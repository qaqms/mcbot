package com.neko.mcbot.path;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable planned terrain for one route prefix; never a record of actual world effects. */
public record TerrainEdits(Set<Long> removed, Set<Long> placed) {
    public static final TerrainEdits EMPTY = new TerrainEdits(Set.of(), Set.of());

    public TerrainEdits {
        removed = Set.copyOf(removed);
        placed = Set.copyOf(placed);
        if (!Collections.disjoint(removed, placed)) throw new IllegalArgumentException("Conflicting edits");
    }

    public TerrainEdits after(List<Long> dig, List<Long> place) {
        if (dig.isEmpty() && place.isEmpty()) return this;
        var nextRemoved = new HashSet<>(removed);
        var nextPlaced = new HashSet<>(placed);
        for (long cell : dig) {
            nextPlaced.remove(cell);
            nextRemoved.add(cell);
        }
        for (long cell : place) {
            nextRemoved.remove(cell);
            nextPlaced.add(cell);
        }
        return new TerrainEdits(nextRemoved, nextPlaced);
    }

    public DigSampler view(DigSampler base) {
        if (removed.isEmpty() && placed.isEmpty()) return base;
        return new DigSampler() {
            @Override public boolean passable(int x, int y, int z) {
                long key = DigAStar.pack(x, y, z);
                return !placed.contains(key) && (removed.contains(key) || base.passable(x, y, z));
            }
            @Override public boolean support(int x, int y, int z) {
                long key = DigAStar.pack(x, y, z);
                return !removed.contains(key) && (placed.contains(key) || base.support(x, y, z));
            }
            @Override public double digSeconds(int x, int y, int z) {
                long key = DigAStar.pack(x, y, z);
                // Routes leave their supports in place; recycling planned blocks needs a separate contract.
                return placed.contains(key) ? INFEASIBLE : removed.contains(key) ? 0 : base.digSeconds(x, y, z);
            }
            @Override public boolean placeable(int x, int y, int z) {
                return !placed.contains(DigAStar.pack(x, y, z)) && base.placeable(x, y, z);
            }
            @Override public double placeCost(int x, int y, int z) { return base.placeCost(x, y, z); }
            @Override public boolean inBounds(int x, int y, int z) { return base.inBounds(x, y, z); }
            @Override public boolean known(int x, int y, int z) { return base.known(x, y, z); }
            @Override public int maxPlaces() { return base.maxPlaces(); }
            @Override public boolean allowsStep(int fx, int fy, int fz, int x, int y, int z) {
                return base.allowsStep(fx, fy, fz, x, y, z);
            }
            @Override public boolean canClear(int x, int y, int z, List<Long> cells) {
                return base.canClear(x, y, z, cells, TerrainEdits.this);
            }
        };
    }
}
