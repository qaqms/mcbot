package com.neko.mcbot.path;

import java.util.ArrayList;
import java.util.List;

/** One step's terrain premises, shared by search and live route validation. */
final class PathTransition {
    record Plan(List<Long> dig, List<Long> place, TerrainEdits terrain, double feetY, boolean jump) { }
    record Check(Plan plan, String reason, Long cell) {
        static Check refused(String reason, Long cell) { return new Check(null, reason, cell); }
    }

    private PathTransition() { }

    static Plan evaluate(DigSampler base, TerrainEdits edits, int fx, int fy, int fz,
                         int x, int y, int z, int remainingDigs, int remainingPlaces) {
        return check(base, edits, fx, fy, fz, x, y, z, remainingDigs, remainingPlaces).plan();
    }

    static Check check(DigSampler base, TerrainEdits edits, int fx, int fy, int fz,
                       int x, int y, int z, int remainingDigs, int remainingPlaces) {
        int dx = x - fx, dy = y - fy, dz = z - fz;
        if (Math.abs(dx) > 1 || Math.abs(dy) > 1 || Math.abs(dz) > 1
                || dx == 0 && dy == 0 && dz == 0) return Check.refused("NON_ADJACENT", DigAStar.pack(x, y, z));
        if (!base.inBounds(x, y, z)) return Check.refused("OUT_OF_BOUNDS", DigAStar.pack(x, y, z));
        if (!base.allowsStep(fx, fy, fz, x, y, z)) return Check.refused("STEP_RESTRICTED", DigAStar.pack(x, y, z));
        if (base.geometry() != null)
            return shaped(base, edits, fx, fy, fz, x, y, z, remainingDigs, remainingPlaces);
        DigSampler terrain = edits.view(base);
        if (dy > 0 && (!terrain.known(fx, fy + 2, fz) || !terrain.passable(fx, fy + 2, fz)))
            return Check.refused(terrain.known(fx, fy + 2, fz) ? "JUMP_CLEARANCE" : "UNKNOWN_TERRAIN",
                    DigAStar.pack(fx, fy + 2, fz));
        if (dx != 0 && dz != 0) for (int cy = fy; cy <= fy + 1; cy++) {
            for (int side = 0; side < 2; side++) {
                int cx = side == 0 ? fx + dx : fx, cz = side == 0 ? fz : fz + dz;
                if (!terrain.known(cx, cy, cz) || !terrain.passable(cx, cy, cz))
                    return Check.refused(terrain.known(cx, cy, cz) ? "CORNER_BLOCKED" : "UNKNOWN_TERRAIN",
                            DigAStar.pack(cx, cy, cz));
            }
        }
        var dig = new ArrayList<Long>(3);
        for (long cell : DigAStar.clearanceCells(fy, x, y, z)) {
            int cy = DigAStar.unpackY(cell);
            if (!terrain.known(x, cy, z)) return Check.refused("UNKNOWN_TERRAIN", cell);
            if (terrain.passable(x, cy, z)) continue;
            if (cell == DigAStar.pack(fx, fy - 1, fz)) return Check.refused("DEPARTURE_SUPPORT", cell);
            if (!DigSampler.feasibleDig(terrain.digSeconds(x, cy, z)))
                return Check.refused("DIG_UNAVAILABLE", cell);
            dig.add(cell);
            if (dig.size() > remainingDigs) return Check.refused("DIG_LIMIT", cell);
        }
        if (!dig.isEmpty() && !terrain.canClear(fx, fy, fz, dig)) return Check.refused("WORK_SIGHT", null);
        List<Long> place = List.of();
        long floor = DigAStar.pack(x, y - 1, z);
        if (!terrain.known(x, y - 1, z)) return Check.refused("UNKNOWN_TERRAIN", floor);
        if (!terrain.support(x, y - 1, z)) {
            if (remainingPlaces < 1) return Check.refused("PLACE_LIMIT", floor);
            if (!terrain.placeable(x, y - 1, z)) return Check.refused("NO_SUPPORT", floor);
            place = List.of(floor);
        }
        List<Long> clearing = List.copyOf(dig);
        return new Check(new Plan(clearing, place, edits.after(clearing, place), y, dy > 0), null, null);
    }

    private static Check shaped(DigSampler base, TerrainEdits edits, int fx, int fy, int fz,
                                int x, int y, int z, int remainingDigs, int remainingPlaces) {
        var geometry = base.geometry();
        var from = geometry.foot(fx, fy, fz, edits);
        if (from == null) return Check.refused("DEPARTURE_FOOTING", DigAStar.pack(fx, fy, fz));
        var dest = geometry.foot(x, y, z, edits);
        double feet = dest == null ? y : dest.height();
        var clear = geometry.clearance(fx, from.height(), fz, x, feet, z, edits);
        if (!clear.known()) return Check.refused(clear.failure(), clear.cell());
        var terrain = edits.view(base);
        for (long cell : clear.blockers()) {
            if (cell == from.support()) return Check.refused("DEPARTURE_SUPPORT", cell);
            if (dest != null && cell == dest.support()) return Check.refused("DESTINATION_SUPPORT", cell);
            if (feet > from.height() && DigAStar.unpackX(cell) == fx && DigAStar.unpackZ(cell) == fz)
                return Check.refused("JUMP_CLEARANCE", cell);
            if (fx != x && fz != z && (DigAStar.unpackX(cell) != x || DigAStar.unpackZ(cell) != z))
                return Check.refused("CORNER_BLOCKED", cell);
            if (!base.inBounds(DigAStar.unpackX(cell), DigAStar.unpackY(cell), DigAStar.unpackZ(cell)))
                return Check.refused("OUT_OF_BOUNDS", cell);
            if (!DigSampler.feasibleDig(terrain.digSeconds(
                    DigAStar.unpackX(cell), DigAStar.unpackY(cell), DigAStar.unpackZ(cell))))
                return Check.refused("DIG_UNAVAILABLE", cell);
        }
        var dig = clear.blockers();
        if (dig.size() > remainingDigs) return Check.refused("DIG_LIMIT", null);
        if (!dig.isEmpty() && !terrain.canClear(fx, fy, fz, dig)) return Check.refused("WORK_SIGHT", null);
        List<Long> place = List.of();
        if (dest == null) {
            long floor = DigAStar.pack(x, y - 1, z);
            if (remainingPlaces < 1) return Check.refused("PLACE_LIMIT", floor);
            if (!terrain.known(x, y - 1, z)) return Check.refused("UNKNOWN_TERRAIN", floor);
            if (!terrain.placeable(x, y - 1, z)) return Check.refused("NO_SUPPORT", floor);
            place = List.of(floor);
        }
        var after = edits.after(dig, place);
        dest = geometry.foot(x, y, z, after);
        if (dest == null) return Check.refused("NO_SUPPORT", DigAStar.pack(x, y - 1, z));
        var motion = geometry.motion(fx, from.height(), fz, x, dest.height(), z, after);
        if (motion == NavigationGeometry.Motion.BLOCKED) return Check.refused("MOVEMENT_GEOMETRY", null);
        return new Check(new Plan(dig, place, after, dest.height(), motion == NavigationGeometry.Motion.JUMP), null, null);
    }
}
