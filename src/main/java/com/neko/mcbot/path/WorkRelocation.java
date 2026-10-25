package com.neko.mcbot.path;

import java.util.function.Predicate;

/** Select a real adjacent construction stance using the same premises as ordinary navigation. */
public final class WorkRelocation {
    private WorkRelocation() { }

    public static DigAStar.Step choose(DigSampler terrain, int x, int y, int z, int digs,
                                       Predicate<DigAStar.Step> usable) {
        DigAStar.Step best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        int drop = terrain.geometry() == null ? 1 : (int) Math.ceil(terrain.geometry().maxDrop());
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            int depth = dx == 0 || dz == 0 ? drop : 1;
            for (int dy = 1; dy >= -depth; dy--) {
                var check = PathTransition.check(terrain, TerrainEdits.EMPTY, x, y, z,
                        x + dx, y + dy, z + dz, digs, terrain.maxPlaces());
                if (check.plan() == null) continue;
                var plan = check.plan();
                var step = new DigAStar.Step(x + dx, y + dy, z + dz, plan.dig(), plan.place(),
                        plan.jump() ? DigAStar.ACT_JUMP : dy < 0 ? DigAStar.ACT_FALL : DigAStar.ACT_WALK,
                        plan.feetY(), plan.jump());
                if (!usable.test(step)) continue;
                double cost = Math.abs(dx) + Math.abs(dz) + Math.abs(dy) * .25;
                for (long p : plan.dig()) cost += 10 + DigAStar.digCost(terrain.digSeconds(
                        DigAStar.unpackX(p), DigAStar.unpackY(p), DigAStar.unpackZ(p)));
                for (long p : plan.place()) cost += 10 + terrain.placeCost(
                        DigAStar.unpackX(p), DigAStar.unpackY(p), DigAStar.unpackZ(p));
                if (cost < bestCost) { best = step; bestCost = cost; }
            }
        }
        return best;
    }
}
