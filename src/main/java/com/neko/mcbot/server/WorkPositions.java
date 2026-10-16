package com.neko.mcbot.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.path.TerrainEdits;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Nearby collision-supported standing candidates. The normal path tool decides reachability. */
public final class WorkPositions {
    public static final int MAX_POSITIONS = 6;
    public static final int MAX_MINING_CHECKS = 24;
    private WorkPositions() { }
    public static boolean standable(CompanionPlayer player, BlockPos pos) {
        return foot(player, pos) != null;
    }

    public static com.neko.mcbot.path.NavigationGeometry.Foot foot(CompanionPlayer player, BlockPos pos) {
        var level = player.level();
        var geometry = StandingGeometry.forPlayer(player);
        var foot = geometry.standing(pos.getX(), pos.getY(), pos.getZ(), TerrainEdits.EMPTY);
        return foot != null && level.noCollision(player, geometry.body(pos.getX(), foot.height(), pos.getZ()))
                ? foot : null;
    }
    public interface Access {
        boolean standable(BlockPos pos);
        double distanceSquared(BlockPos pos);
        default boolean workable(BlockPos pos) { return true; }
        default boolean available() { return true; }
    }

    public static List<BlockPos> candidates(BlockPos target, Access access) {
        return candidates(target, access, false);
    }

    public static List<BlockPos> pickupCandidates(BlockPos target, Access access) {
        return candidates(target, access, true);
    }

    private static List<BlockPos> candidates(BlockPos target, Access access, boolean contact) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            BlockPos pos = target.offset(x, y, z);
            if (access.standable(pos) && (!contact || access.workable(pos))) candidates.add(pos);
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
        if (!MiningEnvironment.safe(MiningEnvironment.world(player.level()), target))
            return new BlockAim.Aim(null, "ENVIRONMENT_UNSAFE:目标邻接流体、重力方块或未知区域。");
        return BlockAim.mining(BlockAim.forPlayer(player), target, Math.min(5.5, player.blockInteractionRange()));
    }

    public static JsonArray describe(CompanionPlayer player, BlockPos target) {
        return describe(player, target, false);
    }

    public static JsonArray describePickup(CompanionPlayer player, net.minecraft.world.entity.item.ItemEntity item) {
        var points = pickupCandidates(item.blockPosition(), new Access() {
            @Override public boolean standable(BlockPos pos) { return WorkPositions.standable(player, pos); }
            @Override public double distanceSquared(BlockPos pos) {
                var foot = WorkPositions.foot(player, pos);
                return foot == null ? Double.POSITIVE_INFINITY
                        : player.distanceToSqr(pos.getX() + .5, foot.height(), pos.getZ() + .5);
            }
            @Override public boolean workable(BlockPos pos) {
                var foot = WorkPositions.foot(player, pos);
                if (foot == null) return false;
                var bounds = player.getBoundingBox().move(pos.getX() + .5 - player.getX(),
                        foot.height() - player.getY(), pos.getZ() + .5 - player.getZ());
                return GroundItems.touches(bounds, item.getBoundingBox());
            }
        });
        var result = new JsonArray();
        for (BlockPos pos : points) {
            var point = new JsonObject();
            point.addProperty("x", pos.getX());
            point.addProperty("y", pos.getY());
            point.addProperty("z", pos.getZ());
            var foot = WorkPositions.foot(player, pos);
            if (foot != null) point.addProperty("feet_y", foot.height());
            result.add(point);
        }
        return result;
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
        double eyeHeight = player.getEyePosition().y - player.getY();
        var access = new Access() {
            @Override public boolean standable(BlockPos pos) {
                return WorkPositions.standable(player, pos);
            }
            @Override public double distanceSquared(BlockPos pos) {
                var foot = WorkPositions.foot(player, pos);
                return foot == null ? Double.POSITIVE_INFINITY
                        : player.distanceToSqr(pos.getX() + .5, foot.height(), pos.getZ() + .5);
            }
            @Override public boolean workable(BlockPos pos) {
                var foot = WorkPositions.foot(player, pos);
                if (foot == null) return false;
                var eye = new Vec3(pos.getX() + .5, foot.height() + eyeHeight, pos.getZ() + .5);
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
            var foot = WorkPositions.foot(player, pos);
            if (foot != null) point.addProperty("feet_y", foot.height());
            positions.add(point);
        }
        return positions;
    }
}
