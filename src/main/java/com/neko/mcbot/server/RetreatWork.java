package com.neko.mcbot.server;

import com.neko.mcbot.path.PathSpec;
import com.neko.mcbot.path.TerrainEdits;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/** Finite destination choice, never another navigator or a material dependency planner. */
public final class RetreatWork {
    public static BlockPos haven(BodyDefense.Frame frame, BlockPos anchor, PathSpec original,
                                StandingGeometry geometry) {
        long started = System.nanoTime();
        var local = new PathSpec(false, false, original.underground(), 0, anchor, 6);
        BlockPos best = null;
        double score = Double.NEGATIVE_INFINITY;
        // At most 60 stances, all in the original local sphere. One destination per episode.
        for (int dx : new int[]{-5, -3, 0, 3, 5}) for (int dz : new int[]{-5, -3, 0, 3, 5}) {
            if (dx == 0 && dz == 0 || dx * dx + dz * dz > 30) continue;
            for (int dy = -1; dy <= 1; dy++) {
                if (System.nanoTime() - started >= 50_000_000L) return best;
                var p = anchor.offset(dx, dy, dz);
                if (!local.contains(p.getX(), p.getY(), p.getZ())
                        || !original.contains(p.getX(), p.getY(), p.getZ())) continue;
                var foot = geometry.standing(p.getX(), p.getY(), p.getZ(), TerrainEdits.EMPTY);
                if (foot == null) continue;
                var body = geometry.body(p.getX(), foot.height(), p.getZ());
                if (frame.facts().threats().stream().anyMatch(t -> t.dangerous(body, 1.5))) continue;
                double clearance = frame.facts().threats().stream()
                        .mapToDouble(t -> ThreatFacts.clearance(t, body)).min().orElse(0);
                if (clearance > score) { score = clearance; best = p; }
            }
        }
        return best;
    }

    public static AABB at(AABB original, BlockPos feet) {
        double halfX = original.getXsize() / 2, halfZ = original.getZsize() / 2;
        // Integer-cell projection includes the supported fractional-height band conservatively.
        return new AABB(feet.getX() + .5 - halfX, feet.getY() - 1, feet.getZ() + .5 - halfZ,
                feet.getX() + .5 + halfX, feet.getY() + original.getYsize(), feet.getZ() + .5 + halfZ);
    }

    private RetreatWork() { }
}
