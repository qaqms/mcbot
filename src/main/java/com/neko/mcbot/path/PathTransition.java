package com.neko.mcbot.path;

import java.util.ArrayList;
import java.util.List;

/** One step's terrain premises, shared by search and live route validation. */
final class PathTransition {
    record Plan(List<Long> dig, List<Long> place, TerrainEdits terrain) { }

    private PathTransition() { }

    static Plan evaluate(DigSampler base, TerrainEdits edits, int fx, int fy, int fz,
                         int x, int y, int z, int remainingDigs, int remainingPlaces) {
        int dx = x - fx, dy = y - fy, dz = z - fz;
        if (Math.abs(dx) > 1 || Math.abs(dy) > 1 || Math.abs(dz) > 1
                || dx == 0 && dy == 0 && dz == 0
                || !base.inBounds(x, y, z) || !base.allowsStep(fx, fy, fz, x, y, z)) return null;
        DigSampler terrain = edits.view(base);
        if (dy > 0 && !terrain.passable(fx, fy + 2, fz)) return null;
        if (dx != 0 && dz != 0 && (!terrain.passable(fx + dx, fy, fz)
                || !terrain.passable(fx, fy, fz + dz) || !terrain.passable(fx + dx, fy + 1, fz)
                || !terrain.passable(fx, fy + 1, fz + dz))) return null;
        var dig = new ArrayList<Long>(3);
        for (long cell : DigAStar.clearanceCells(fy, x, y, z)) {
            int cy = DigAStar.unpackY(cell);
            if (terrain.passable(x, cy, z)) continue;
            if (cell == DigAStar.pack(fx, fy - 1, fz)
                    || !DigSampler.feasibleDig(terrain.digSeconds(x, cy, z))) return null;
            dig.add(cell);
            if (dig.size() > remainingDigs) return null;
        }
        if (!dig.isEmpty() && !terrain.canClear(fx, fy, fz, dig)) return null;
        List<Long> place = List.of();
        if (!terrain.support(x, y - 1, z)) {
            if (remainingPlaces < 1 || !terrain.placeable(x, y - 1, z)) return null;
            place = List.of(DigAStar.pack(x, y - 1, z));
        }
        List<Long> clearing = List.copyOf(dig);
        return new Plan(clearing, place, edits.after(clearing, place));
    }
}
