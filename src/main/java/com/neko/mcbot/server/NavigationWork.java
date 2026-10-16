package com.neko.mcbot.server;

import com.neko.mcbot.path.DigAStar;
import com.neko.mcbot.path.NavigationGoal;
import com.neko.mcbot.path.TerrainEdits;
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
        return view(world, eye, removed, Set.of());
    }

    public static BlockAim.Access view(BlockAim.Access world, Vec3 eye, Set<BlockPos> removed, Set<BlockPos> placed) {
        return new BlockAim.Access() {
            @Override public Vec3 eye() { return eye; }
            @Override public boolean loaded(BlockPos pos) { return world.loaded(pos); }
            @Override public List<AABB> outline(BlockPos pos) {
                return removed.contains(pos) ? List.of() : placed.contains(pos)
                        ? List.of(new AABB(0, 0, 0, 1, 1, 1)) : world.outline(pos);
            }
            @Override public BlockHitResult trace(Vec3 from, Vec3 to) {
                return world.traceEdited(from, to, removed, placed);
            }
            @Override public BlockHitResult traceIgnoring(Vec3 from, Vec3 to, Set<BlockPos> ignored) {
                var union = new HashSet<>(removed);
                union.addAll(ignored);
                var remainingPlaced = new HashSet<>(placed);
                remainingPlaced.removeAll(ignored);
                return world.traceEdited(from, to, union, remainingPlaced);
            }
        };
    }

    public static boolean canClear(BlockAim.Access world, BlockPos feet, double eyeHeight, double reach,
                                   List<Long> cells) {
        return canClear(world, feet, eyeHeight, reach, cells, TerrainEdits.EMPTY);
    }

    public static boolean canClear(BlockAim.Access world, BlockPos feet, double eyeHeight, double reach,
                                   List<Long> cells, TerrainEdits edits) {
        var removed = positions(edits.removed());
        var placed = positions(edits.placed());
        removed.add(feet); removed.add(feet.above());
        Vec3 eye = new Vec3(feet.getX() + .5, feet.getY() + eyeHeight, feet.getZ() + .5);
        for (long cell : cells) {
            BlockPos pos = new BlockPos(DigAStar.unpackX(cell), DigAStar.unpackY(cell), DigAStar.unpackZ(cell));
            if (!BlockAim.mining(view(world, eye, removed, placed), pos, reach).ok()) return false;
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
                return reached(x, y, z, TerrainEdits.EMPTY);
            }
            @Override public boolean reached(int x, int y, int z, TerrainEdits edits) {
                if (remaining(x, y, z) > 0) return false;
                BlockPos feet = new BlockPos(x, y, z);
                if (feet.equals(target) || feet.above().equals(target)) return false;
                var removed = positions(edits.removed());
                removed.add(feet); removed.add(feet.above());
                var view = view(world, new Vec3(x + .5, y + eyeHeight, z + .5),
                        removed, positions(edits.placed()));
                return clear ? BlockAim.miningPlan(view, target, reach,
                        pos -> !pos.equals(feet.below()) && !pos.equals(target.below()) && safe.test(pos)).ok()
                        : BlockAim.mining(view, target, reach).ok();
            }
        };
    }

    private static Set<BlockPos> positions(Set<Long> cells) {
        var positions = new HashSet<BlockPos>();
        for (long cell : cells)
            positions.add(new BlockPos(DigAStar.unpackX(cell), DigAStar.unpackY(cell), DigAStar.unpackZ(cell)));
        return positions;
    }
}
