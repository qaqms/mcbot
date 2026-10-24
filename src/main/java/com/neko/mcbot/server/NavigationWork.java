package com.neko.mcbot.server;

import com.neko.mcbot.path.DigAStar;
import com.neko.mcbot.path.NavigationGoal;
import com.neko.mcbot.path.NavigationGeometry;
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
        return canClear(world, feet, eyeHeight, reach, cells, edits, null);
    }

    public static boolean canClear(BlockAim.Access world, BlockPos feet, double eyeHeight, double reach,
                                   List<Long> cells, TerrainEdits edits, NavigationGeometry geometry) {
        var removed = positions(edits.removed());
        var placed = positions(edits.placed());
        double height = feet.getY();
        if (geometry == null) {
            removed.add(feet); removed.add(feet.above());
        } else {
            var foot = geometry.foot(feet.getX(), feet.getY(), feet.getZ(), edits);
            if (foot == null) return false;
            height = foot.height();
        }
        Vec3 eye = new Vec3(feet.getX() + .5, height + eyeHeight, feet.getZ() + .5);
        for (long cell : cells) {
            BlockPos pos = new BlockPos(DigAStar.unpackX(cell), DigAStar.unpackY(cell), DigAStar.unpackZ(cell));
            if (!BlockAim.mining(view(world, eye, removed, placed), pos, reach).ok()) return false;
            removed.add(pos);
        }
        return true;
    }

    public static NavigationGoal goal(BlockAim.Access world, BlockPos target, double eyeHeight, double reach,
                                      boolean clear, Predicate<BlockPos> safe) {
        return goal(world, target, eyeHeight, reach, clear, safe, null);
    }

    public static NavigationGoal goal(BlockAim.Access world, BlockPos target, double eyeHeight, double reach,
                                      boolean clear, Predicate<BlockPos> safe, NavigationGeometry geometry) {
        return new NavigationGoal() {
            @Override public NavigationGoal snapshot(com.neko.mcbot.path.DigSampler terrain) {
                // Freeze only the dry work predicate; never publish the live safe/world closures.
                return terrain instanceof com.neko.mcbot.path.NavigationSnapshot snapshot
                        ? snapshot.workGoal(target, eyeHeight, reach) : null;
            }
            @Override public Bounds estimateBounds() {
                int radius = (int) Math.ceil(reach + .87);
                double feetY = target.getY() + .5 - eyeHeight;
                return new Bounds(target.getX() - radius, (int) Math.floor(feetY - radius - (geometry == null ? 0 : 1)), target.getZ() - radius,
                        target.getX() + radius, (int) Math.ceil(feetY + radius), target.getZ() + radius);
            }
            @Override public double remaining(int x, int y, int z) {
                double dx = x + .5 - (target.getX() + .5);
                double dy = geometry == null ? y + eyeHeight - (target.getY() + .5)
                        : Math.max(0, Math.abs(y + .5 + eyeHeight - (target.getY() + .5)) - .5);
                double dz = z + .5 - (target.getZ() + .5);
                return Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - reach - .87);
            }
            @Override public double approach(int x, int y, int z) {
                double dx = (double) x - target.getX(), dz = (double) z - target.getZ();
                double dy = y + eyeHeight - (target.getY() + .5);
                return dx * dx + dy * dy + dz * dz;
            }
            @Override public boolean reached(int x, int y, int z) {
                return reached(x, y, z, TerrainEdits.EMPTY);
            }
            @Override public boolean reached(int x, int y, int z, TerrainEdits edits) {
                return plan(x, y, z, edits).ok();
            }
            @Override public double arrivalCost(com.neko.mcbot.path.DigSampler terrain,
                                                int x, int y, int z, TerrainEdits edits) {
                var plan = plan(x, y, z, edits);
                if (!plan.ok()) return Double.POSITIVE_INFINITY;
                double cost = 0;
                var view = edits.view(terrain);
                for (var blocker : plan.blockers()) {
                    double seconds = view.digSeconds(blocker.getX(), blocker.getY(), blocker.getZ());
                    if (!com.neko.mcbot.path.DigSampler.feasibleDig(seconds)) return Double.POSITIVE_INFINITY;
                    cost += DigAStar.digCost(seconds);
                }
                return cost;
            }
            private BlockAim.MiningPlan plan(int x, int y, int z, TerrainEdits edits) {
                if (remaining(x, y, z) > 0) return failed();
                BlockPos feet = new BlockPos(x, y, z);
                if (feet.equals(target) || feet.above().equals(target)) return failed();
                var removed = positions(edits.removed());
                double height = y;
                BlockPos support = feet.below();
                if (geometry == null) {
                    removed.add(feet); removed.add(feet.above());
                } else {
                    var foot = geometry.foot(x, y, z, edits);
                    if (foot == null) return failed();
                    height = foot.height();
                    support = new BlockPos(DigAStar.unpackX(foot.support()), DigAStar.unpackY(foot.support()),
                            DigAStar.unpackZ(foot.support()));
                }
                BlockPos protectedSupport = support;
                var view = view(world, new Vec3(x + .5, height + eyeHeight, z + .5),
                        removed, positions(edits.placed()));
                return clear ? BlockAim.miningPlan(view, target, reach,
                        pos -> !pos.equals(protectedSupport) && !pos.equals(target.below()) && safe.test(pos))
                        : new BlockAim.MiningPlan(BlockAim.mining(view, target, reach), List.of());
            }
            private BlockAim.MiningPlan failed() {
                return new BlockAim.MiningPlan(new BlockAim.Aim(null, "NO_WORK_STANCE"), List.of());
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
