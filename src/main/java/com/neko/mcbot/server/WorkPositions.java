package com.neko.mcbot.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Nearby full-floor standing candidates. The normal path tool still decides reachability. */
public final class WorkPositions {
    public static final int MAX_POSITIONS = 6;
    public static final int MAX_MINING_CHECKS = 24;
    private WorkPositions() { }
    public interface Access {
        boolean standable(BlockPos pos);
        double distanceSquared(BlockPos pos);
        default boolean workable(BlockPos pos) { return true; }
        default boolean available() { return true; }
    }

    public static List<BlockPos> candidates(BlockPos target, Access access) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            BlockPos pos = target.offset(x, y, z);
            if (access.standable(pos)) candidates.add(pos);
        }
        candidates.sort(Comparator.comparingDouble(access::distanceSquared)
                .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        return List.copyOf(candidates.subList(0, Math.min(MAX_POSITIONS, candidates.size())));
    }

    public static List<BlockPos> miningCandidates(BlockPos target, Access access) {
        var nearby = new ArrayList<BlockPos>();
        // Surface work can be two or three feet cells above a buried target.
        for (int y = -1; y <= 3; y++) for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            nearby.add(target.offset(x, y, z));
        nearby.sort(Comparator.comparingDouble(access::distanceSquared)
                .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        var found = new ArrayList<BlockPos>();
        int checked = 0;
        for (BlockPos pos : nearby) {
            if (!access.available()) break;
            if (!access.standable(pos)) continue;
            if (++checked > MAX_MINING_CHECKS) break;
            if (access.workable(pos)) found.add(pos);
            if (found.size() == MAX_POSITIONS) break;
        }
        return List.copyOf(found);
    }

    public static BlockAim.Aim miningAim(CompanionPlayer player, BlockPos target) {
        return BlockAim.mining(BlockAim.forPlayer(player), target, Math.min(5.5, player.blockInteractionRange()));
    }

    public static JsonArray describe(CompanionPlayer player, BlockPos target) {
        return describe(player, target, false);
    }

    public static JsonArray describeMining(CompanionPlayer player, BlockPos target) {
        return describeMining(player, target, new BlockAim.TraceBudget(MAX_MINING_CHECKS * BlockAim.MAX_PLAN_TRACES));
    }

    public static JsonArray describeMining(CompanionPlayer player, BlockPos target, BlockAim.TraceBudget budget) {
        return describe(player, target, true, budget);
    }

    private static JsonArray describe(CompanionPlayer player, BlockPos target, boolean mining) {
        return describe(player, target, mining, new BlockAim.TraceBudget(MAX_MINING_CHECKS * BlockAim.MAX_PLAN_TRACES));
    }

    private static JsonArray describe(CompanionPlayer player, BlockPos target, boolean mining,
                                      BlockAim.TraceBudget budget) {
        var level = player.level();
        double eyeHeight = player.getEyePosition().y - player.getY();
        var access = new Access() {
            @Override public boolean standable(BlockPos pos) {
                for (BlockPos cell : List.of(pos.below(), pos, pos.above())) {
                    if (!level.isInWorldBounds(cell) || !level.getWorldBorder().isWithinBounds(cell)
                            || level.getChunkSource().getChunkNow(cell.getX() >> 4, cell.getZ() >> 4) == null)
                        return false;
                    if (!level.getBlockState(cell).getFluidState().isEmpty()) return false;
                }
                if (!level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) return false;
                var box = player.getBoundingBox().move(pos.getX() + .5 - player.getX(),
                        pos.getY() - player.getY(), pos.getZ() + .5 - player.getZ());
                return level.noCollision(player, box);
            }
            @Override public double distanceSquared(BlockPos pos) {
                return player.distanceToSqr(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
            }
            @Override public boolean workable(BlockPos pos) {
                var eye = new Vec3(pos.getX() + .5, pos.getY() + eyeHeight, pos.getZ() + .5);
                return MiningWork.plan(player, target, eye, pos, budget).ok();
            }
            @Override public boolean available() { return budget.available(); }
        };
        var positions = new JsonArray();
        for (BlockPos pos : mining ? miningCandidates(target, access) : candidates(target, access)) {
            var point = new JsonObject();
            point.addProperty("x", pos.getX());
            point.addProperty("y", pos.getY());
            point.addProperty("z", pos.getZ());
            positions.add(point);
        }
        return positions;
    }
}
