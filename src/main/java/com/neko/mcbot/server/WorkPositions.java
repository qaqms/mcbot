package com.neko.mcbot.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Nearby full-floor standing candidates. The normal path tool still decides reachability. */
public final class WorkPositions {
    private WorkPositions() { }
    public interface Access {
        boolean standable(BlockPos pos);
        double distanceSquared(BlockPos pos);
    }

    public static List<BlockPos> candidates(BlockPos target, Access access) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            BlockPos pos = target.offset(x, y, z);
            if (access.standable(pos)) candidates.add(pos);
        }
        candidates.sort(Comparator.comparingDouble(access::distanceSquared)
                .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        return List.copyOf(candidates.subList(0, Math.min(6, candidates.size())));
    }

    public static JsonArray describe(CompanionPlayer player, BlockPos target) {
        var level = player.level();
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
        };
        var positions = new JsonArray();
        for (BlockPos pos : candidates(target, access)) {
            var point = new JsonObject();
            point.addProperty("x", pos.getX());
            point.addProperty("y", pos.getY());
            point.addProperty("z", pos.getZ());
            positions.add(point);
        }
        return positions;
    }
}
