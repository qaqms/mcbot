package com.neko.mcbot.server;

import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.path.DigSampler;
import com.neko.mcbot.path.LevelDigSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Shared clearing geometry for observations, stance arrival and bounded execution. */
public final class MiningWork {
    private MiningWork() { }

    public static BlockAim.MiningPlan plan(CompanionPlayer body, BlockPos target) {
        return plan(body, target, body.getEyePosition(), body.blockPosition());
    }

    public static BlockAim.MiningPlan plan(CompanionPlayer body, BlockPos target, Vec3 eye, BlockPos feet) {
        return plan(body, target, eye, feet, new BlockAim.TraceBudget(BlockAim.MAX_PLAN_TRACES));
    }

    public static BlockAim.MiningPlan plan(CompanionPlayer body, BlockPos target, Vec3 eye, BlockPos feet,
                                           BlockAim.TraceBudget budget) {
        if (!MiningEnvironment.safe(MiningEnvironment.world(body.level()), target))
            return new BlockAim.MiningPlan(new BlockAim.Aim(null,
                    "ENVIRONMENT_UNSAFE:目标邻接流体、重力方块或未知区域，不属于当前干燥采掘范围。"), java.util.List.of());
        var world = BlockAim.fromEye(BlockAim.forPlayer(body), eye);
        var sampler = new LevelDigSampler(body.level(), body, feet, 0);
        return BlockAim.miningPlan(world, target, Math.min(5.5, body.blockInteractionRange()),
                pos -> safe(body, sampler, feet, pos), budget);
    }

    public static boolean safe(CompanionPlayer body, LevelDigSampler sampler, BlockPos feet, BlockPos pos) {
        var level = body.level();
        if (pos.equals(feet.below()) || !level.isLoaded(pos)
                || !DigSampler.feasibleDig(sampler.digSeconds(pos.getX(), pos.getY(), pos.getZ()))
                || !level.mayInteract(body, pos)
                || body.blockActionRestricted(level, pos, body.gameMode.getGameModeForPlayer())) return false;
        return MiningEnvironment.safe(MiningEnvironment.world(level), pos);
    }
}
