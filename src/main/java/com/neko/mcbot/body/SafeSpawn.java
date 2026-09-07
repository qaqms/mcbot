package com.neko.mcbot.body;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** 找一处可站立的落点：两格空气 + 非空流体、脚下非空气。找不到就退回给定原点。 */
public final class SafeSpawn {

    private SafeSpawn() {
    }

    public static BlockPos findNear(ServerLevel level, BlockPos origin) {
        if (isStandable(level, origin)) {
            return origin;
        }
        for (int dy = 1; dy <= 12; dy++) {
            BlockPos p = origin.above(dy);
            if (isStandable(level, p)) {
                return p;
            }
        }
        for (int dy = 1; dy <= 8; dy++) {
            BlockPos p = origin.below(dy);
            if (isStandable(level, p)) {
                return p;
            }
        }
        return origin;
    }

    public static boolean isStandable(ServerLevel level, BlockPos feet) {
        BlockState a = level.getBlockState(feet);
        BlockState above = level.getBlockState(feet.above());
        BlockState below = level.getBlockState(feet.below());
        return a.isAir() && above.isAir() && !below.isAir() && below.getFluidState().isEmpty();
    }
}
