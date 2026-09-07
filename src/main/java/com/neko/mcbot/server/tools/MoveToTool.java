package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.path.PathTask;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.task.CompanionScheduler;
import net.minecraft.core.BlockPos;

import java.util.concurrent.CompletableFuture;

/**
 * move_to（M8 版）：DigAStar 寻路——会走、会跳、会落，也会挖穿/垫脚/搭桥，
 * 但"改动世界"的路必须先经模型点头（may_alter_terrain 确认流，DESIGN §8）。
 * 被挡不再一律举白旗：绕得开就绕，绕不开要挖则先报清单征求确认。
 * 同层直路仍按 0.45 格/tick 滑步节奏，与 M4 行为视觉一致（回归不破）。
 */
public final class MoveToTool implements ServerTool {

    @Override
    public String name() {
        return "move_to";
    }

    @Override
    public CompletableFuture<Result> runAsync(CompanionPlayer c, JsonObject args,
                                              CompanionScheduler sched) {
        BlockPos to = BreakBlockTool.readPos(args);
        CompletableFuture<Result> f = new CompletableFuture<>();
        if (to == null) {
            return CompletableFuture.completedFuture(
                    new Result(false, "DENIED:需要整数坐标 x/y/z。", null));
        }
        boolean alter = args.has("may_alter_terrain") && args.get("may_alter_terrain").getAsBoolean();
        if (!PathTask.withinCap(c.blockPosition(), to)) {
            return CompletableFuture.completedFuture(new Result(false,
                    "PATH_BLOCKED:目标超出我一次能规划的盒子（水平 64 格/垂直 32 格）。"
                            + "分几段走过来。", null));
        }
        var level = c.level();
        if (!level.hasChunkAt(to) || !level.isLoaded(to)) {
            return CompletableFuture.completedFuture(new Result(false,
                    "TARGET_LOST:(" + to.toShortString() + ") 不在已加载区域，先往那个方向走一段。",
                    null));
        }
        if (!sched.submit(c, new PathTask(level, to, alter), f, 3600)) {
            return CompletableFuture.completedFuture(new Result(false,
                    "BUSY:我正忙着上一件事，等它结束或让我取消。", null));
        }
        return f;
    }
}
