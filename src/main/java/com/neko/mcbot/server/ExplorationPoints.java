package com.neko.mcbot.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Set;
import java.util.function.Predicate;

/** Loaded standing samples for a new observation; navigation still proves the route. */
public final class ExplorationPoints {
    public static final int MAX_CHECKS = 200;
    private static final int[][] DIRECTIONS = {
            {8, 0}, {6, 6}, {0, 8}, {-6, 6}, {-8, 0}, {-6, -6}, {0, -8}, {6, -6}
    };
    private static final int[][] FAR_DIRECTIONS = {
            {16, 0}, {11, 11}, {0, 16}, {-11, 11}, {-16, 0}, {-11, -11}, {0, -16}, {11, -11}
    };
    private static final Set<Block> HAZARDS = Set.of(Blocks.MAGMA_BLOCK, Blocks.CACTUS,
            Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.FIRE, Blocks.SOUL_FIRE,
            Blocks.SWEET_BERRY_BUSH, Blocks.WITHER_ROSE, Blocks.POWDER_SNOW);
    private ExplorationPoints() { }

    public static JsonArray describe(CompanionPlayer player) {
        return describe(player, 16);
    }

    public static JsonArray describe(CompanionPlayer player, int distance) {
        return candidates(StandingGeometry.feetCell(player), pos -> {
            if (!WorkPositions.standable(player, pos)) return false;
            for (BlockPos cell : java.util.List.of(pos.below(), pos, pos.above()))
                if (HAZARDS.contains(player.level().getBlockState(cell).getBlock())) return false;
            return true;
        }, distance);
    }

    public static JsonArray candidates(BlockPos center, Predicate<BlockPos> standable) {
        return candidates(center, standable, 16);
    }

    public static JsonArray candidates(BlockPos center, Predicate<BlockPos> standable, int distance) {
        if (distance < 4 || distance > 16) throw new IllegalArgumentException();
        var points = new JsonArray();
        for (int direction = 0; direction < DIRECTIONS.length; direction++) {
            for (int probe = 0; probe < 25; probe++) {
                boolean far = probe < 13;
                int vertical = far ? probe : probe - 13;
                int dy = vertical == 0 ? 0 : (vertical + 1) / 2 * (vertical % 2 == 1 ? 1 : -1);
                int[] delta = far ? FAR_DIRECTIONS[direction] : DIRECTIONS[direction];
                int dx = (int) Math.round(delta[0] * distance / 16d);
                int dz = (int) Math.round(delta[1] * distance / 16d);
                BlockPos pos = center.offset(dx, dy, dz);
                if (!standable.test(pos)) continue;
                var point = new JsonObject();
                point.addProperty("x", pos.getX());
                point.addProperty("y", pos.getY());
                point.addProperty("z", pos.getZ());
                points.add(point);
                break;
            }
        }
        return points;
    }
}
