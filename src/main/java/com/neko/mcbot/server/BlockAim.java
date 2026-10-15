package com.neko.mcbot.server;

import com.neko.mcbot.body.CompanionPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.function.Predicate;

/** Loaded, bounded sight checks before a coordinate-based action can touch the world. */
public final class BlockAim {
    public interface Access {
        Vec3 eye();
        boolean loaded(BlockPos pos);
        List<AABB> outline(BlockPos pos);
        BlockHitResult trace(Vec3 from, Vec3 to);
        default BlockHitResult traceIgnoring(Vec3 from, Vec3 to, Set<BlockPos> ignored) {
            return ignored.isEmpty() ? trace(from, to) : null;
        }
    }

    public record Aim(Vec3 point, String failure) {
        public boolean ok() { return failure == null; }
    }

    public record MiningPlan(Aim aim, List<BlockPos> blockers) {
        public boolean ok() { return aim.ok(); }
    }

    public static final int MAX_CLEAR_BLOCKS = 3;
    public static final int MAX_PLAN_TRACES = 64;

    public static final class TraceBudget {
        private final int limit;
        private int used;
        public TraceBudget(int limit) { this.limit = limit; }
        public boolean available() { return used < limit; }
        public int used() { return used; }
        private boolean take() {
            if (!available()) return false;
            used++;
            return true;
        }
    }

    private static final double[] SAMPLES = {.5, .1, .9};
    private static final int MAX_BOXES = 16;

    private BlockAim() {}

    public static Access forPlayer(CompanionPlayer player) {
        var level = player.level();
        return new Access() {
            @Override public Vec3 eye() { return player.getEyePosition(); }
            @Override public boolean loaded(BlockPos pos) {
                return level.isInWorldBounds(pos) && level.getWorldBorder().isWithinBounds(pos)
                        && level.isLoaded(pos);
            }
            @Override public List<AABB> outline(BlockPos pos) {
                return level.getBlockState(pos).getShape(level, pos, CollisionContext.of(player)).toAabbs();
            }
            @Override public BlockHitResult trace(Vec3 from, Vec3 to) {
                return level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE, player));
            }
            @Override public BlockHitResult traceIgnoring(Vec3 from, Vec3 to, Set<BlockPos> ignored) {
                return clipIgnoring(level, from, to, CollisionContext.of(player), ignored);
            }
        };
    }

    /** Evaluate a proposed stance without moving the body or changing its look direction. */
    public static Access fromEye(Access world, Vec3 eye) {
        return new Access() {
            @Override public Vec3 eye() { return eye; }
            @Override public boolean loaded(BlockPos pos) { return world.loaded(pos); }
            @Override public List<AABB> outline(BlockPos pos) { return world.outline(pos); }
            @Override public BlockHitResult trace(Vec3 from, Vec3 to) { return world.trace(from, to); }
            @Override public BlockHitResult traceIgnoring(Vec3 from, Vec3 to, Set<BlockPos> ignored) {
                return world.traceIgnoring(from, to, ignored);
            }
        };
    }

    /** Only admitted cells disappear in this read-only view; real mining never uses it. */
    public static BlockHitResult clipIgnoring(BlockGetter world, Vec3 from, Vec3 to,
                                              CollisionContext context, Set<BlockPos> ignored) {
        BlockGetter view = new BlockGetter() {
            @Override public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos pos) {
                return ignored.contains(pos) ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                        : world.getBlockState(pos);
            }
            @Override public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
                return getBlockState(pos).getFluidState();
            }
            @Override public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
                return ignored.contains(pos) ? null : world.getBlockEntity(pos);
            }
            @Override public int getHeight() { return world.getHeight(); }
            @Override public int getMinY() { return world.getMinY(); }
        };
        return view.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, context));
    }

    /** A small concrete sight-clearing plan, ranked by actual blockers rather than target distance. */
    public static MiningPlan miningPlan(Access access, BlockPos target, double reach,
                                        Predicate<BlockPos> clearable) {
        return miningPlan(access, target, reach, clearable, new TraceBudget(MAX_PLAN_TRACES));
    }

    public static MiningPlan miningPlan(Access access, BlockPos target, double reach,
                                        Predicate<BlockPos> clearable, TraceBudget budget) {
        if (!validReach(reach)) return new MiningPlan(failed("OUT_OF_REACH:当前交互距离无效。"), List.of());
        if (!access.loaded(target)) return new MiningPlan(unloaded(), List.of());
        Vec3 eye = access.eye();
        MiningPlan best = null;
        int traces = 0;
        boolean distant = false;
        boolean capped = false;
        boolean unknown = false;
        var boxes = access.outline(target);
        search: for (int i = 0; i < Math.min(MAX_BOXES, boxes.size()); i++) {
            AABB box = boxes.get(i);
            for (double x : SAMPLES) for (double y : SAMPLES) for (double z : SAMPLES) {
                Vec3 point = new Vec3(target.getX() + box.minX + (box.maxX - box.minX) * x,
                        target.getY() + box.minY + (box.maxY - box.minY) * y,
                        target.getZ() + box.minZ + (box.maxZ - box.minZ) * z);
                if (eye.distanceToSqr(point) > (reach + 2) * (reach + 2)) { distant = true; continue; }
                if (!loadedSegment(access, eye, point)) { unknown = true; continue; }
                var blockers = new ArrayList<BlockPos>();
                var ignored = new java.util.HashSet<BlockPos>();
                while (true) {
                    if (++traces > MAX_PLAN_TRACES || !budget.take()) { capped = true; break search; }
                    var hit = access.traceIgnoring(eye, point, ignored);
                    if (hit == null || hit.getType() != HitResult.Type.BLOCK) break;
                    if (eye.distanceToSqr(hit.getLocation()) > reach * reach) { distant = true; break; }
                    BlockPos pos = hit.getBlockPos().immutable();
                    if (target.equals(pos)) {
                        var plan = new MiningPlan(new Aim(point, null), List.copyOf(blockers));
                        if (best == null || blockers.size() < best.blockers().size()) best = plan;
                        if (blockers.isEmpty()) return plan;
                        break;
                    }
                    if (blockers.size() >= MAX_CLEAR_BLOCKS || !access.loaded(pos)
                            || !clearable.test(pos) || !ignored.add(pos)) break;
                    blockers.add(pos);
                }
            }
        }
        return best != null ? best : new MiningPlan(failed(unknown
                ? "TARGET_LOST:工作射线经过未加载或界外格子。"
                : capped
                ? "WORK_BUDGET:本次工作射线评估已撞帽，未证明不存在工作位置。"
                : distant
                ? "OUT_OF_REACH:眼睛到工作表面超出实际交互距离。"
                : "TARGET_BLOCKED:有界视线内没有可安全清理的工作射线。"), List.of());
    }

    public static Aim mining(Access access, BlockPos pos, double reach) {
        if (!validReach(reach)) return failed("OUT_OF_REACH:当前交互距离无效，先核对身体状态。");
        if (!access.loaded(pos)) return unloaded();
        Vec3 eye = access.eye();
        boolean distant = false;
        List<AABB> boxes = access.outline(pos);
        for (int i = 0; i < Math.min(MAX_BOXES, boxes.size()); i++) {
            AABB box = boxes.get(i);
            for (double x : SAMPLES) for (double y : SAMPLES) for (double z : SAMPLES) {
                Vec3 point = new Vec3(pos.getX() + box.minX + (box.maxX - box.minX) * x,
                        pos.getY() + box.minY + (box.maxY - box.minY) * y,
                        pos.getZ() + box.minZ + (box.maxZ - box.minZ) * z);
                if (eye.distanceToSqr(point) > (reach + 2) * (reach + 2)) {
                    distant = true;
                    continue;
                }
                Aim aim = miningRay(access, pos, point, reach);
                if (aim.ok() || aim.failure().startsWith("TARGET_LOST:")) return aim;
                if (aim.failure().startsWith("OUT_OF_REACH:")) distant = true;
            }
        }
        return distant ? failed("OUT_OF_REACH:眼睛到目标表面超出实际交互距离，先 move_to 靠近。")
                : failed("TARGET_BLOCKED:视线未命中指定方块；目标被遮挡或无可点击轮廓，先查看或调整站位。");
    }

    public static Aim miningRay(Access access, BlockPos pos, Vec3 point, double reach) {
        if (!validReach(reach) || access.eye().distanceToSqr(point) > (reach + 2) * (reach + 2)) {
            return failed("OUT_OF_REACH:目标视线超出实际交互距离，先 move_to 靠近。");
        }
        if (!access.loaded(pos) || !loadedSegment(access, access.eye(), point)) return unloaded();
        BlockHitResult hit = access.trace(access.eye(), point);
        if (hit.getType() != HitResult.Type.BLOCK || !pos.equals(hit.getBlockPos())) {
            return failed("TARGET_BLOCKED:当前视线未命中指定方块，先查看或调整站位。");
        }
        if (access.eye().distanceToSqr(hit.getLocation()) > reach * reach) {
            return failed("OUT_OF_REACH:眼睛到目标表面超出实际交互距离，先 move_to 靠近。");
        }
        return new Aim(point, null);
    }

    /** Empty placement cells may produce MISS; a nearer blocking cell may never be clicked through. */
    public static Aim placement(Access access, BlockPos pos, Vec3 point, double reach) {
        if (!validReach(reach) || access.eye().distanceToSqr(point) > reach * reach) {
            return failed("OUT_OF_REACH:放置点超出实际交互距离，先 move_to 靠近。");
        }
        Aim first = placementRay(access, pos, point);
        if (first.ok() || first.failure().startsWith("TARGET_LOST:")) return first;
        // A face's centre can be covered while another part of the exact placement cell is exposed.
        for (double x : SAMPLES) for (double y : SAMPLES) for (double z : SAMPLES) {
            Vec3 sample = new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
            if (access.eye().distanceToSqr(sample) > reach * reach) continue;
            Aim aim = placementRay(access, pos, sample);
            if (aim.ok() || aim.failure().startsWith("TARGET_LOST:")) return aim;
        }
        return first;
    }

    private static Aim placementRay(Access access, BlockPos pos, Vec3 point) {
        if (!access.loaded(pos) || !loadedSegment(access, access.eye(), point)) return unloaded();
        BlockHitResult hit = access.trace(access.eye(), point);
        if (hit.getType() == HitResult.Type.BLOCK && !pos.equals(hit.getBlockPos()))
            return failed("TARGET_BLOCKED:放置目标的可见采样点被方块 " + hit.getBlockPos().toShortString()
                    + " 遮挡；先inspect_block查看遮挡或move_to exact=true换位，再决定放置。");
        return new Aim(point, null);
    }

    private static boolean loadedSegment(Access access, Vec3 from, Vec3 to) {
        // Check exactly the ray's cells before vanilla clip can read any block or fluid state.
        return BlockGetter.traverseBlocks(from, to, access,
                (view, pos) -> view.loaded(pos) ? null : Boolean.FALSE, view -> Boolean.TRUE);
    }

    private static boolean validReach(double reach) {
        return Double.isFinite(reach) && reach > 0 && reach <= 6.5;
    }

    private static Aim unloaded() {
        return failed("TARGET_LOST:目标视线经过未加载或超出世界范围的格子，先靠近再查看。");
    }

    private static Aim failed(String reason) { return new Aim(null, reason); }
}
