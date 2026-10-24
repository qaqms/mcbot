package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.path.PathTask;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.server.ActionPermissions;
import com.neko.mcbot.server.BlockAim;
import com.neko.mcbot.server.BlockPlacement;
import com.neko.mcbot.server.WorkPositions;
import com.neko.mcbot.server.MiningWork;
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

    /** Progress and per-search resource checks govern movement, without a fixed total deadline. */
    public static final int CAP_TICKS = com.neko.mcbot.agentcore.loop.PendingJobs.UNLIMITED_CAP_TICKS;

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
        com.neko.mcbot.path.PathSpec spec;
        try { spec = com.neko.mcbot.path.PathSpec.parse(args); }
        catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture(new Result(false,
                    "DENIED:arrival仅支持work并需要work_target；地下/范围/挖掘参数无效。", null));
        }
        boolean clear;
        try { clear = BreakBlockTool.clearObstructions(args); }
        catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture(new Result(false, "DENIED:clear_obstructions须为布尔。", null));
        }
        com.neko.mcbot.server.PlacementWork placement;
        try { placement = placement(args, workTarget); }
        catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture(new Result(false,
                    "DENIED:place_item须为方块物品ID并提供exact=true、arrival=work及work_target；"
                            + "face仅up/down/north/south/east/west，不与clear_obstructions=true组合。", null));
        }
        if (args.has("exact") && (!args.get("exact").isJsonPrimitive()
                || !args.get("exact").getAsJsonPrimitive().isBoolean())) {
            return CompletableFuture.completedFuture(new Result(false, "DENIED:exact 须为布尔。", null));
        }
        boolean walkOnly;
        try { walkOnly = walkOnly(args); }
        catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture(new Result(false, "DENIED:walk_only须为布尔。", null));
        }
        Long returnId;
        try { returnId = returnWaypoint(args); }
        catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture(new Result(false,
                    "DENIED:return_waypoint须正整数ID，并使用exact=true、walk_only=true且不带工作目标。", null));
        }
        java.util.UUID pickup;
        try { pickup = pickupTarget(args); }
        catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture(new Result(false,
                    "DENIED:pickup_target_uuid须为标准UUID，不能与工作/放置/返程目标组合。", null));
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
        c.bindSpatialTask(context);
        if (returnId != null) {
            var failure = returnGuard(c.navigationTrail(), returnId, to, spec, () -> {
                var foot = com.neko.mcbot.server.StandingGeometry.forPlayer(c, c.level()::isLoaded)
                        .foot(to.getX(), to.getY(), to.getZ(), com.neko.mcbot.path.TerrainEdits.EMPTY);
                return foot == null ? Double.NaN : foot.height();
            });
            if (failure != null) return CompletableFuture.completedFuture(failure);
        }
        if (workTarget != null && !c.buildSite().protects(workTarget) && !c.workTargets().add(workTarget))
            return CompletableFuture.completedFuture(new Result(false,
                    "ACTION_LIMIT:当前任务工作目标保护集合已满，未淘汰旧保护或继续开路。", null));
        BlockPos from = com.neko.mcbot.server.StandingGeometry.feetCell(c);
        if (!spec.contains(to.getX(), to.getY(), to.getZ())
                || !spec.contains(from.getX(), from.getY(), from.getZ()))
            return CompletableFuture.completedFuture(new Result(false, "SCOPE_CHANGED:起点或目的地超出路线范围。", null));
        var level = c.level();
        if (!level.hasChunkAt(to) || !level.isLoaded(to)) {
            return CompletableFuture.completedFuture(new Result(false,
                    "TARGET_LOST:(" + to.toShortString() + ") 不在已加载区域，先往那个方向走一段。",
                    null));
        }
        if (placement != null) {
            var failure = placement.ready(c);
            if (failure != null) return CompletableFuture.completedFuture(failure);
        }
        var endpoint = walkEndpoint(to, walkOnly, args.has("exact") && args.get("exact").getAsBoolean(),
                workTarget != null || pickup != null, com.neko.mcbot.server.StandingGeometry.forPlayer(c),
                pos -> spec.contains(pos.getX(), pos.getY(), pos.getZ()) && c.buildSite().inScope(pos));
        if (endpoint != null) return CompletableFuture.completedFuture(endpoint);
        ActionPermissions.Scope scope = permissions.take(context, id, to.asLong());
        var path = new PathTask(level, to, permissions, context, scope, sched,
                args.has("exact") && args.get("exact").getAsBoolean(), walkOnly, workTarget, spec, placement);
        if (pickup != null) path.pickingUp(pickup);
        if (c.buildSite().active()) path.limitTo(c.buildSite().scope()).usingBudget(c.buildSite().navigation());
        if (!sched.submit(c, path, f, capTicks(args))) {
            return CompletableFuture.completedFuture(new Result(false,
                    "BUSY:我正忙着上一件事，等它结束或让我取消。", null));
        }
        return f.thenApply(moved -> placement != null
                ? com.neko.mcbot.server.PlacementWork.finish(moved, () -> placement.assess(c, workTarget))
                : finishWork(moved, workTarget == null ? null
                : () -> clear ? MiningWork.plan(c, workTarget).aim() : WorkPositions.miningAim(c, workTarget)));
    }

    static Result walkEndpoint(BlockPos target, boolean walkOnly, boolean exact, boolean semantic,
                               com.neko.mcbot.server.StandingGeometry geometry,
                               java.util.function.Predicate<BlockPos> allowed) {
        if (!walkOnly || !exact || semantic) return null;
        if (geometry.standing(target.getX(), target.getY(), target.getZ(),
                com.neko.mcbot.path.TerrainEdits.EMPTY) != null) return null;
        var data = new JsonObject();
        data.addProperty("endpoint_unstandable", true);
        data.add("requested_feet", com.neko.mcbot.building.BuildTarget.coordinates(target));
        for (var alternative : java.util.List.of(target.below(), target.above())) {
            if (!allowed.test(alternative)) continue;
            var foot = geometry.standing(alternative.getX(), alternative.getY(), alternative.getZ(),
                    com.neko.mcbot.path.TerrainEdits.EMPTY);
            if (foot == null) continue;
            var point = com.neko.mcbot.building.BuildTarget.coordinates(alternative);
            point.addProperty("feet_y", foot.height());
            data.add("alternative_feet", point);
            break;
        }
        return new Result(false, "TARGET_BLOCKED:只行走无法精确站到请求脚格 " + target.toShortString()
                + "：当前支撑、净空或已加载几何未确认；未启动整片寻路。"
                + (data.has("alternative_feet") ? " 同列实际可站脚格为 " + data.get("alternative_feet")
                + "，仅为现场建议，不证明路可达，未替换原目标；核对需要的脚格高度后再决定。"
                : " 先核对真实脚格和地板高度，或在原许可内选择可挖放的路线。"), data);
    }

    static com.neko.mcbot.server.PlacementWork placement(JsonObject args, BlockPos target) {
        if (!args.has("place_item")) {
            if (args.has("face") || args.has("temporary_purpose")) throw new IllegalArgumentException();
            return null;
        }
        if (target == null || !args.has("arrival") || !"work".equals(args.get("arrival").getAsString())
                || !target.equals(BreakBlockTool.readPos(args)) || BreakBlockTool.clearObstructions(args))
            throw new IllegalArgumentException();
        var input = new JsonObject();
        input.addProperty("x", target.getX()); input.addProperty("y", target.getY()); input.addProperty("z", target.getZ());
        input.add("item", args.get("place_item"));
        if (args.has("face")) input.add("face", args.get("face"));
        if (args.has("temporary_purpose")) input.add("temporary_purpose", args.get("temporary_purpose"));
        var parsed = PlaceBlockTool.arguments(input);
        if (parsed == null || !BlockPlacement.supported(parsed.item())) throw new IllegalArgumentException();
        return new com.neko.mcbot.server.PlacementWork(parsed.item(), args.has("face") ? parsed.face() : null,
                PlaceBlockTool.temporaryPurpose(input));
    }

    static boolean walkOnly(JsonObject args) {
        if (!args.has("walk_only")) return false;
        var value = args.get("walk_only");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException();
        return value.getAsBoolean();
    }

    static java.util.UUID pickupTarget(JsonObject args) {
        if (!args.has("pickup_target_uuid")) return null;
        if (args.has("work_target") || args.has("arrival") || args.has("place_item")
                || args.has("return_waypoint")) throw new IllegalArgumentException();
        var value = args.get("pickup_target_uuid");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
        String raw = value.getAsString();
        var id = java.util.UUID.fromString(raw);
        if (!id.toString().equals(raw)) throw new IllegalArgumentException();
        return id;
    }

    static Long returnWaypoint(JsonObject args) {
        if (!args.has("return_waypoint")) return null;
        var value = args.get("return_waypoint");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
        long id = value.getAsBigDecimal().longValueExact();
        if (id <= 0 || !args.has("exact") || !args.get("exact").getAsBoolean()
                || !walkOnly(args) || args.has("work_target") || args.has("arrival"))
            throw new IllegalArgumentException();
        return id;
    }

    static Result returnGuard(com.neko.mcbot.path.NavigationTrail trail, long id, BlockPos to,
            com.neko.mcbot.path.PathSpec spec, java.util.function.DoubleSupplier currentFoot) {
        var recorded = trail.point(id);
        if (recorded == null || !to.equals(new BlockPos(recorded.x(), recorded.y(), recorded.z())))
            return new Result(false, "NO_ROUTE:当前任务/身体没有该实际路标或坐标不匹配，未猜测返程。", null);
        if (!java.util.Objects.equals(spec.anchor(), recorded.scope().anchor())
                || spec.radius() != recorded.scope().radius()
                || spec.underground() != recorded.scope().underground())
            return new Result(false, "SCOPE_CHANGED:返程须保留路标原bounds及underground约束，未放宽原范围。", null);
        double height = currentFoot.getAsDouble();
        if (!Double.isFinite(height) || Math.abs(height - recorded.feetY()) > 1e-5)
            return new Result(false, "NO_ROUTE:路标的当前支撑或脚高已经变化，先重新观察。", null);
        return null;
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
