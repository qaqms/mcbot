package com.neko.mcbot.server;

import com.neko.mcbot.path.DigAStar;
import com.neko.mcbot.path.NavigationGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Search-only geometry overlays. Execution always aims against the unmodified live world. */
public final class NavigationWork {
    private NavigationWork() { }

    public static BlockAim.Access view(BlockAim.Access world, Vec3 eye, Set<BlockPos> removed) {
        return new BlockAim.Access() {
            @Override public Vec3 eye() { return eye; }
            @Override public boolean loaded(BlockPos pos) { return world.loaded(pos); }
            @Override public List<AABB> outline(BlockPos pos) { return removed.contains(pos) ? List.of() : world.outline(pos); }
            @Override public BlockHitResult trace(Vec3 from, Vec3 to) { return world.traceIgnoring(from, to, removed); }
            @Override public BlockHitResult traceIgnoring(Vec3 from, Vec3 to, Set<BlockPos> ignored) {
                var union = new HashSet<>(removed);
                union.addAll(ignored);
                return world.traceIgnoring(from, to, union);
            }
        };
    }

    public static boolean canClear(BlockAim.Access world, BlockPos feet, double eyeHeight, double reach,
                                   List<Long> cells) {
        var removed = new HashSet<BlockPos>();
        removed.add(feet); removed.add(feet.above());
        Vec3 eye = new Vec3(feet.getX() + .5, feet.getY() + eyeHeight, feet.getZ() + .5);
        for (long cell : cells) {
            BlockPos pos = new BlockPos(DigAStar.unpackX(cell), DigAStar.unpackY(cell), DigAStar.unpackZ(cell));
            if (!BlockAim.mining(view(world, eye, removed), pos, reach).ok()) return false;
            removed.add(pos);
        }
        return true;
    }

    public static NavigationGoal goal(BlockAim.Access world, BlockPos target, double eyeHeight, double reach,
                                      boolean clear, Predicate<BlockPos> safe) {
        return new NavigationGoal() {
            @Override public double remaining(int x, int y, int z) {
                double dx = x + .5 - (target.getX() + .5);
                double dy = y + eyeHeight - (target.getY() + .5);
                double dz = z + .5 - (target.getZ() + .5);
                return Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - reach - .87);
            }
            @Override public boolean reached(int x, int y, int z) {
                if (remaining(x, y, z) > 0) return false;
                BlockPos feet = new BlockPos(x, y, z);
                if (feet.equals(target) || feet.above().equals(target)) return false;
                var view = view(world, new Vec3(x + .5, y + eyeHeight, z + .5), Set.of(feet, feet.above()));
                return clear ? BlockAim.miningPlan(view, target, reach,
                        pos -> !pos.equals(feet.below()) && !pos.equals(target.below()) && safe.test(pos)).ok()
                        : BlockAim.mining(view, target, reach).ok();
            }
        };
    }
}
