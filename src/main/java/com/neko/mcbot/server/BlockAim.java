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

/** Loaded, bounded sight checks before a coordinate-based action can touch the world. */
public final class BlockAim {
    public interface Access {
        Vec3 eye();
        boolean loaded(BlockPos pos);
        List<AABB> outline(BlockPos pos);
        BlockHitResult trace(Vec3 from, Vec3 to);
    }

    public record Aim(Vec3 point, String failure) {
        public boolean ok() { return failure == null; }
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
        };
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
        if (!access.loaded(pos) || !loadedSegment(access, access.eye(), point)) return unloaded();
        BlockHitResult hit = access.trace(access.eye(), point);
        if (hit.getType() == HitResult.Type.BLOCK && !pos.equals(hit.getBlockPos())) {
            return failed("TARGET_BLOCKED:放置点被其他方块遮挡，先查看或调整站位。");
        }
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
