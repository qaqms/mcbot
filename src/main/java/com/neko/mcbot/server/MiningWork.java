package com.neko.mcbot.server;

import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.path.DigSampler;
import com.neko.mcbot.path.LevelDigSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Shared clearing geometry for observations, stance arrival and bounded execution. */
public final class MiningWork {
    private MiningWork() { }

    /** A vanished plant has no mining stance. It is a fresh observation, not a failed route. */
    public static ServerTool.Result targetFailure(BlockPos target, BlockState state) {
        if (state == null) return new ServerTool.Result(false,
                "TARGET_LOST:采掘工作目标未加载，未搜索未知目标的工作站位。", null);
        if (!state.isAir()) return null;
        var data = new com.google.gson.JsonObject();
        data.add("work_target", com.neko.mcbot.building.BuildTarget.coordinates(target));
        data.addProperty("target_already_clear", true);
        data.addProperty("can_work_here", false);
        data.addProperty("removed", false);
        return new ServerTool.Result(false,
                "TARGET_BLOCKED:采掘工作目标已经为空，已停止寻找挖掘站位；重新核对原图，"
                        + "继续其他清理或放置。目标消失不证明由本身体挖除。", data);
    }

    public static BlockAim.MiningPlan plan(CompanionPlayer body, BlockPos target) {
        return plan(body, target, body.getEyePosition(), StandingGeometry.feetCell(body));
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
        var foot = sampler.geometry() == null ? null : sampler.geometry().foot(
                feet.getX(), feet.getY(), feet.getZ(), com.neko.mcbot.path.TerrainEdits.EMPTY);
        if (sampler.geometry() != null && foot == null) return false;
        boolean support = foot == null ? pos.equals(feet.below())
                : foot.support() == com.neko.mcbot.path.DigAStar.pack(pos.getX(), pos.getY(), pos.getZ());
        if (support || !level.isLoaded(pos)
                || !DigSampler.feasibleDig(sampler.digSeconds(pos.getX(), pos.getY(), pos.getZ()))
                || !level.mayInteract(body, pos)
                || body.blockActionRestricted(level, pos, body.gameMode.getGameModeForPlayer())) return false;
        return MiningEnvironment.safe(MiningEnvironment.world(level), pos);
    }
}
