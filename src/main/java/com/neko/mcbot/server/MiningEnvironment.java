package com.neko.mcbot.server;

import com.neko.mcbot.body.CompanionPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Dry work has one physical hazard rule across observations, navigation and execution. */
public final class MiningEnvironment {
    public interface Access {
        boolean loaded(BlockPos pos);
        BlockState state(BlockPos pos);
    }

    private MiningEnvironment() { }

    public static Access world(net.minecraft.server.level.ServerLevel level) {
        return new Access() {
            @Override public boolean loaded(BlockPos pos) {
                return level.isInWorldBounds(pos) && level.getWorldBorder().isWithinBounds(pos) && level.isLoaded(pos);
            }
            @Override public BlockState state(BlockPos pos) { return level.getBlockState(pos); }
        };
    }

    public static boolean safe(Access world, BlockPos target) {
        if (!world.loaded(target)) return false;
        var state = world.state(target);
        if (!state.getFluidState().isEmpty() || state.getBlock() instanceof FallingBlock) return false;
        for (var side : Direction.values()) {
            var near = target.relative(side);
            if (!world.loaded(near)) return false;
            var neighbor = world.state(near);
            if (!neighbor.getFluidState().isEmpty()
                    || side == Direction.UP && neighbor.getBlock() instanceof FallingBlock) return false;
        }
        return true;
    }

    public static ServerTool.Result bodyFailure(CompanionPlayer body) {
        return bodyFailure(body.isEyeInFluid(net.minecraft.tags.FluidTags.WATER), body.isInLava());
    }

    static ServerTool.Result bodyFailure(boolean headUnderwater, boolean lava) {
        return headUnderwater || lava ? new ServerTool.Result(false,
                "ENVIRONMENT_UNSAFE:身体已没入水中或接触岩浆，已停止普通挖掘/陆地路线；"
                        + "先status核对身体与避险状态，脱离后重新观察并决定。", null) : null;
    }
}
