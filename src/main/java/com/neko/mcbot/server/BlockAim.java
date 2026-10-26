package com.neko.mcbot.server;

import com.neko.mcbot.body.CompanionPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
        default BlockGetter blocks() { return this instanceof BlockGetter getter ? getter : null; }
        Vec3 eye();
        boolean loaded(BlockPos pos);
        default boolean replaceable(BlockPos pos) { return false; }
        List<AABB> outline(BlockPos pos);
        BlockHitResult trace(Vec3 from, Vec3 to);
        default BlockHitResult traceIgnoring(Vec3 from, Vec3 to, Set<BlockPos> ignored) {
            return ignored.isEmpty() ? trace(from, to) : null;
        }
        default BlockHitResult traceEdited(Vec3 from, Vec3 to, Set<BlockPos> removed, Set<BlockPos> placed) {
            return placed.isEmpty() ? traceIgnoring(from, to, removed) : null;
        }
    }

    public record Aim(Vec3 point, String failure) {
        public boolean ok() { return failure == null; }
    }

    public record Contact(BlockHitResult hit, String failure) {
        public boolean ok() { return failure == null; }
    }

    public enum Anchor { PRESENT, ABSENT, UNKNOWN }

    /** An empty contact neighborhood cannot be fixed by moving the eye alone. No visibility claim is made here. */
    public static Anchor placementAnchor(Access access, BlockPos target) {
        return placementAnchor(access, target, face -> true);
    }

    /** Faces describe the native click, so a neighbor is opposite the allowed face. */
    public static Anchor placementAnchor(Access access, BlockPos target, Predicate<Direction> faces) {
        boolean unknown = false;
        var cells = new java.util.LinkedHashSet<BlockPos>();
        for (var face : Direction.values()) if (faces.test(face)) {
            cells.add(target);
            cells.add(target.relative(face.getOpposite()));
        }
        for (var cell : cells) {
            if (!access.loaded(cell)) { unknown = true; continue; }
            // Clicking a replaceable neighbor replaces that neighbor, not the requested cell.
            if (!cell.equals(target) && access.replaceable(cell)) continue;
            var outline = access.outline(cell);
            if (outline == null) { unknown = true; continue; }
            if (outline.stream().anyMatch(box -> box.getSize() > 0)) return Anchor.PRESENT;
        }
        return unknown ? Anchor.UNKNOWN : Anchor.ABSENT;
    }

    public record MiningPlan(Aim aim, List<BlockPos> blockers) {
        public boolean ok() { return aim.ok(); }
    }

    public static final int DEFAULT_CLEAR_LIMIT = Integer.MAX_VALUE;
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
            @Override public BlockGetter blocks() { return level; }
            @Override public Vec3 eye() { return player.getEyePosition(); }
            @Override public boolean loaded(BlockPos pos) {
                return level.isInWorldBounds(pos) && level.getWorldBorder().isWithinBounds(pos)
                        && level.isLoaded(pos);
            }
            @Override public List<AABB> outline(BlockPos pos) {
                return level.getBlockState(pos).getShape(level, pos, CollisionContext.of(player)).toAabbs();
            }
            @Override public boolean replaceable(BlockPos pos) {
                return level.getBlockState(pos).canBeReplaced();
            }
            @Override public BlockHitResult trace(Vec3 from, Vec3 to) {
                return level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE, player));
            }
            @Override public BlockHitResult traceIgnoring(Vec3 from, Vec3 to, Set<BlockPos> ignored) {
                return clipIgnoring(level, from, to, CollisionContext.of(player), ignored);
            }
            @Override public BlockHitResult traceEdited(Vec3 from, Vec3 to, Set<BlockPos> removed, Set<BlockPos> placed) {
                return clipEdited(level, from, to, CollisionContext.of(player), removed, placed);
            }
        };
    }

    /** Evaluate a proposed stance without moving the body or changing its look direction. */
    public static Access fromEye(Access world, Vec3 eye) {
        return new Access() {
            @Override public BlockGetter blocks() { return world.blocks(); }
            @Override public Vec3 eye() { return eye; }
            @Override public boolean loaded(BlockPos pos) { return world.loaded(pos); }
            @Override public boolean replaceable(BlockPos pos) { return world.replaceable(pos); }
            @Override public List<AABB> outline(BlockPos pos) { return world.outline(pos); }
            @Override public BlockHitResult trace(Vec3 from, Vec3 to) { return world.trace(from, to); }
            @Override public BlockHitResult traceIgnoring(Vec3 from, Vec3 to, Set<BlockPos> ignored) {
                return world.traceIgnoring(from, to, ignored);
            }
            @Override public BlockHitResult traceEdited(Vec3 from, Vec3 to, Set<BlockPos> removed, Set<BlockPos> placed) {
                return world.traceEdited(from, to, removed, placed);
            }
        };
    }

    public static Access withToggles(Access sight,BlockGetter world,Set<Long> toggled) {
        if (toggled.isEmpty()) return sight;
        var view=toggledWorld(world,toggled);
        return new Access() {
            @Override public BlockGetter blocks() { return view; }
            @Override public Vec3 eye() { return sight.eye(); }
            @Override public boolean loaded(BlockPos pos) { return sight.loaded(pos); }
            @Override public boolean replaceable(BlockPos pos) { return view.getBlockState(pos).canBeReplaced(); }
            @Override public List<AABB> outline(BlockPos pos) { return view.getBlockState(pos).getShape(view,pos).toAabbs(); }
            @Override public BlockHitResult trace(Vec3 from,Vec3 to) {
                return clipEdited(view,from,to,CollisionContext.empty(),Set.of(),Set.of());
            }
            @Override public BlockHitResult traceEdited(Vec3 from,Vec3 to,Set<BlockPos> removed,Set<BlockPos> placed) {
                return clipEdited(view,from,to,CollisionContext.empty(),removed,placed);
            }
        };
    }

    public static BlockGetter toggledWorld(BlockGetter world,Set<Long> toggled) {
        if (toggled.isEmpty()) return world;
        return new BlockGetter() {
            @Override public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos pos) {
                var state=world.getBlockState(pos);
                return toggled.contains(DoorTraversal.key(pos)) ? DoorTraversal.flip(state) : state;
            }
            @Override public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
                return getBlockState(pos).getFluidState();
            }
            @Override public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) { return null; }
            @Override public int getHeight() { return world.getHeight(); }
            @Override public int getMinY() { return world.getMinY(); }
        };
    }

    /** Only admitted cells disappear in this read-only view; real mining never uses it. */
    public static BlockHitResult clipIgnoring(BlockGetter world, Vec3 from, Vec3 to,
                                              CollisionContext context, Set<BlockPos> ignored) {
        return clipEdited(world, from, to, context, ignored, Set.of());
    }

    /** Path materials are full cubes; planned supports must also occlude later work rays. */
    public static BlockHitResult clipEdited(BlockGetter world, Vec3 from, Vec3 to, CollisionContext context,
                                             Set<BlockPos> removed, Set<BlockPos> placed) {
        return editedWorld(world, removed, placed).clip(
                new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, context));
    }

    public static BlockGetter editedWorld(BlockGetter world, Set<BlockPos> removed, Set<BlockPos> placed) {
        return new BlockGetter() {
            @Override public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos pos) {
                if (removed.contains(pos)) return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                if (placed.contains(pos)) return net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState();
                return world.getBlockState(pos);
            }
            @Override public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
                return getBlockState(pos).getFluidState();
            }
            @Override public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
                return removed.contains(pos) || placed.contains(pos) ? null : world.getBlockEntity(pos);
            }
            @Override public int getHeight() { return world.getHeight(); }
            @Override public int getMinY() { return world.getMinY(); }
        };
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
                    if (!access.loaded(pos)
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
        if (hit == null || hit.getType() != HitResult.Type.BLOCK || !pos.equals(hit.getBlockPos())) {
            return failed("TARGET_BLOCKED:当前视线未命中指定方块，先查看或调整站位。");
        }
        if (access.eye().distanceToSqr(hit.getLocation()) > reach * reach) {
            return failed("OUT_OF_REACH:眼睛到目标表面超出实际交互距离，先 move_to 靠近。");
        }
        return new Aim(point, null);
    }

    /** A placement click must hit a real outline on the requested face, including partial shapes. */
    public static Contact placementContact(Access access, BlockPos clicked, Direction face, double reach) {
        if (!validReach(reach)) return new Contact(null, "OUT_OF_REACH:当前放置交互距离无效。");
        if (!access.loaded(clicked)) return new Contact(null, unloaded().failure());
        Vec3 eye = access.eye();
        boolean distant = false, unknown = false;
        var boxes = access.outline(clicked);
        for (int i = 0; i < Math.min(MAX_BOXES, boxes.size()); i++) {
            AABB box = boxes.get(i);
            for (double u : SAMPLES) for (double v : SAMPLES) {
                boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
                Vec3 local = switch (face.getAxis()) {
                    case X -> new Vec3(positive ? box.maxX : box.minX,
                            box.minY + (box.maxY - box.minY) * u, box.minZ + (box.maxZ - box.minZ) * v);
                    case Y -> new Vec3(box.minX + (box.maxX - box.minX) * u,
                            positive ? box.maxY : box.minY, box.minZ + (box.maxZ - box.minZ) * v);
                    case Z -> new Vec3(box.minX + (box.maxX - box.minX) * u,
                            box.minY + (box.maxY - box.minY) * v, positive ? box.maxZ : box.minZ);
                };
                Vec3 surface = local.add(clicked.getX(), clicked.getY(), clicked.getZ());
                Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
                if (eye.subtract(surface).dot(normal) <= 1e-7) continue;
                if (eye.distanceToSqr(surface) > reach * reach) { distant = true; continue; }
                Vec3 inside = surface.subtract(normal.scale(1e-4));
                if (!loadedSegment(access, eye, inside)) { unknown = true; continue; }
                var hit = access.trace(eye, inside);
                if (hit != null && hit.getType() == HitResult.Type.BLOCK && clicked.equals(hit.getBlockPos())
                        && hit.getDirection() == face && eye.distanceToSqr(hit.getLocation()) <= reach * reach)
                    return new Contact(hit, null);
            }
        }
        return new Contact(null, unknown ? unloaded().failure()
                : distant ? "OUT_OF_REACH:眼睛到可点击放置面超出实际交互距离，先靠近。"
                : "TARGET_BLOCKED:没有可见且实际命中的放置接触面；空气/流体不能作为点击锚点。"
                + "核对face、邻接方块或调整站位，再查询放置。");
    }

    static boolean loadedSegment(Access access, Vec3 from, Vec3 to) {
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
