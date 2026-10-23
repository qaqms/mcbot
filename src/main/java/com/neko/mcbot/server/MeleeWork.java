package com.neko.mcbot.server;

import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.path.NavigationGoal;
import com.neko.mcbot.path.TerrainEdits;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Hypothetical stance uses the native attack range, actual strikes still call the native predicate. */
public final class MeleeWork {
    private MeleeWork() { }

    public static boolean inRange(AttackRange range, boolean creative, Vec3 eye, AABB target) {
        double distance = Math.sqrt(target.distanceToSqr(eye));
        double min = (creative ? range.minCreativeRange() : range.minRange()) - range.hitboxMargin();
        double max = (creative ? range.maxCreativeRange() : range.maxRange()) + range.hitboxMargin();
        return Double.isFinite(distance) && Double.isFinite(min) && Double.isFinite(max)
                && min <= max && distance >= min && distance <= max;
    }

    public static NavigationGoal goal(CompanionPlayer body, LivingEntity entity) {
        var world = BlockAim.forPlayer(body);
        var geometry = StandingGeometry.forPlayer(body);
        var target = entity.getBoundingBox();
        var eye = entity.getEyePosition();
        var range = body.entityAttackRange();
        double eyeHeight = body.getEyeHeight();
        var live = goal(world, geometry, target, eye, range, eyeHeight);
        return new NavigationGoal() {
            @Override public boolean reached(int x, int y, int z) { return live.reached(x, y, z); }
            @Override public double remaining(int x, int y, int z) { return live.remaining(x, y, z); }
            @Override public NavigationGoal snapshot(com.neko.mcbot.path.DigSampler terrain) {
                return terrain instanceof com.neko.mcbot.path.NavigationSnapshot snapshot
                        ? goal(snapshot.sight(eye), snapshot.standing(), target, eye, range, eyeHeight) : null;
            }
        };
    }

    static NavigationGoal goal(BlockAim.Access world, StandingGeometry geometry, AABB target,
                               Vec3 targetEye, AttackRange range, double eyeHeight) {
        return new NavigationGoal() {
            @Override public double remaining(int x, int y, int z) {
                return Math.max(0, Math.sqrt(target.distanceToSqr(new Vec3(x + .5, y + eyeHeight, z + .5)))
                        - Math.min(6.5, range.maxRange() + range.hitboxMargin()));
            }
            @Override public boolean reached(int x, int y, int z) {
                var foot = geometry.standing(x, y, z, TerrainEdits.EMPTY);
                if (foot == null) return false;
                Vec3 eye = new Vec3(x + .5, foot.height() + eyeHeight, z + .5);
                if (!inRange(range, false, eye, target) || geometry.body(x, foot.height(), z).intersects(target)
                        || !BlockAim.loadedSegment(world, eye, targetEye)) return false;
                var hit = world.trace(eye, targetEye);
                return hit != null && hit.getType() == HitResult.Type.MISS;
            }
        };
    }
}
