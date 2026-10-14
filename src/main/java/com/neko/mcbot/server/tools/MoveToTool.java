package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.path.PathTask;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.server.ActionPermissions;
import com.neko.mcbot.server.BlockAim;
import com.neko.mcbot.server.WorkPositions;
import com.neko.mcbot.task.CompanionScheduler;
import net.minecraft.core.BlockPos;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * move_to（M8 版）：DigAStar 寻路——会走、会跳、会落，也会挖穿/垫脚/搭桥，
 * 逐次确认模式的地形改动须批准服务端具体清单，主人选择自主模式则免动作批准。
 * 两模式都保留规划/执行复核，旧 may_alter_terrain 布尔不切换模式或授予许可。
 * 路线逐刻续订行走/跳跃输入，位置与碰撞由原版 travel 推进。
 */
public final class MoveToTool implements ServerTool {

    /** 一次寻路+走完的能力帽：3600 tick = 180s（远路要挖要垫，短帽会把它砍在半路）。 */
    public static final int CAP_TICKS = 3600;

    @Override
    public String name() {
        return "move_to";
    }

    @Override
    public int capTicks(JsonObject args) {
        return CAP_TICKS;
    }

    /**
     * 受理即回执：移动是最典型的"几十秒才有结果"的长活，让模型干等毫无意义
     * （它既不能改目的地、也不能先干别的）。先回 ACCEPTED，走完再以 job_event 报结果。
     */
    @Override
    public Acceptance acceptanceMode() {
        return Acceptance.ACCEPT;
    }

    @Override
    public String acceptSubject(JsonObject args) {
        BlockPos to = BreakBlockTool.readPos(args);
        return to == null ? "移动" : "走到 " + to.toShortString();
    }

    @Override
    public CompletableFuture<Result> runAsync(CompanionPlayer c, JsonObject args,
                                              CompanionScheduler sched) {
        return runAuthorized(c, args, sched, new ActionPermissions(), null);
    }

    @Override
    public CompletableFuture<Result> runAuthorized(CompanionPlayer c, JsonObject args,
            CompanionScheduler sched, ActionPermissions permissions, ActionPermissions.Context context) {
        BlockPos to = BreakBlockTool.readPos(args);
        CompletableFuture<Result> f = new CompletableFuture<>();
        if (to == null) {
            return CompletableFuture.completedFuture(
                    new Result(false, "DENIED:需要整数坐标 x/y/z。", null));
        }
        BlockPos workTarget;
        try { workTarget = workTarget(args); }
        catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture(new Result(false,
                    "DENIED:work_target须为仅含整数x/y/z的目标对象，并使用exact=true。", null));
        }
        if (args.has("exact") && (!args.get("exact").isJsonPrimitive()
                || !args.get("exact").getAsJsonPrimitive().isBoolean())) {
            return CompletableFuture.completedFuture(new Result(false, "DENIED:exact 须为布尔。", null));
        }
        if (args.has("may_alter_terrain") && (!args.get("may_alter_terrain").isJsonPrimitive()
                || !args.get("may_alter_terrain").getAsJsonPrimitive().isBoolean())) {
            return CompletableFuture.completedFuture(new Result(false, "DENIED:may_alter_terrain 须为布尔。", null));
        }
        String id = "";
        if (args.has("authorization_id")) {
            var value = args.get("authorization_id");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                    || value.getAsString().length() > 64) {
                return CompletableFuture.completedFuture(new Result(false, "DENIED:authorization_id 须为短字符串。", null));
            }
            id = value.getAsString();
        }
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
        ActionPermissions.Scope scope = permissions.take(context, id, to.asLong());
        if (!sched.submit(c, new PathTask(level, to, permissions, context, scope, sched,
                args.has("exact") && args.get("exact").getAsBoolean()), f, capTicks(args))) {
            return CompletableFuture.completedFuture(new Result(false,
                    "BUSY:我正忙着上一件事，等它结束或让我取消。", null));
        }
        return f.thenApply(moved -> finishWork(moved, workTarget == null ? null
                : () -> WorkPositions.miningAim(c, workTarget)));
    }

    static BlockPos workTarget(JsonObject args) {
        if (!args.has("work_target")) return null;
        if (!args.has("exact") || !args.get("exact").isJsonPrimitive()
                || !args.get("exact").getAsJsonPrimitive().isBoolean() || !args.get("exact").getAsBoolean())
            throw new IllegalArgumentException();
        var target = args.getAsJsonObject("work_target");
        if (!target.keySet().equals(java.util.Set.of("x", "y", "z"))) throw new IllegalArgumentException();
        BlockPos pos = BreakBlockTool.readPos(target);
        if (pos == null) throw new IllegalArgumentException();
        return pos;
    }

    static Result finishWork(Result moved, Supplier<BlockAim.Aim> assess) {
        if (!moved.ok() || assess == null) return moved;
        var aim = assess.get();
        var data = moved.data() == null ? new JsonObject() : moved.data().deepCopy();
        data.addProperty("can_work_here", aim.ok());
        if (!aim.ok()) {
            data.addProperty("work_failure", aim.failure());
            return new Result(false, aim.failure() + " 已到站位，但最新视线/距离不满足工作条件；移动效果保留。", data);
        }
        return new Result(true, moved.feedback(), data);
    }
}
