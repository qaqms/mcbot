package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.building.BuildSite;
import com.neko.mcbot.building.BuildTarget;
import com.neko.mcbot.server.*;
import com.neko.mcbot.task.CompanionScheduler;
import net.minecraft.core.BlockPos;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class BuildSiteTool implements ServerTool {
    record Arguments(String operation, BuildTarget target, String revision, List<BlockPos> route,
                     com.neko.mcbot.path.PathSpec scope) { }
    static Arguments arguments(JsonObject args) {
        return arguments(args,BuildTarget::parse);
    }
    private static Arguments arguments(JsonObject args,java.util.function.Function<JsonObject,BuildTarget> resolve) {
        String operation = BuildTarget.string(args.get("operation"), 16);
        return switch (operation) {
            case "query" -> {
                BuildTarget.keys(args, Set.of("operation"));
                yield new Arguments(operation, null, null, List.of(), null);
            }
            case "begin" -> {
                BuildTarget.keys(args, Set.of("operation", "target", "access_route", "bounds", "expected_revision"));
                var target = resolve.apply(args.getAsJsonObject("target"));
                if (args.has("expected_revision")
                        && !target.revision().equals(BuildTarget.string(args.get("expected_revision"), 64)))
                    throw new IllegalArgumentException("revision");
                var values = args.has("access_route") ? args.getAsJsonArray("access_route") : new com.google.gson.JsonArray();
                if (values.size() == 1 || values.size() > BuildSite.ROUTE_LIMIT) throw new IllegalArgumentException();
                var route = new ArrayList<BlockPos>();
                for (var value : values) {
                    var p = value.getAsJsonObject();
                    BuildTarget.keys(p, Set.of("x", "y", "z"));
                    route.add(new BlockPos(BuildTarget.integer(p.get("x"), -29999984, 29999984),
                            BuildTarget.integer(p.get("y"), -2048, 2047),
                            BuildTarget.integer(p.get("z"), -29999984, 29999984)));
                }
                yield new Arguments(operation, target, target.revision(), List.copyOf(route),
                        args.has("bounds") ? com.neko.mcbot.path.PathSpec.parse(args) : null);
            }
            case "checkpoint" -> {
                BuildTarget.keys(args, Set.of("operation", "expected_revision"));
                String revision = BuildTarget.string(args.get("expected_revision"), 64);
                if (!revision.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
                yield new Arguments(operation, null, revision, List.of(), null);
            }
            default -> throw new IllegalArgumentException();
        };
    }
    @Override public String name() { return "build_site"; }
    @Override public CompletableFuture<Result> runAuthorized(CompanionPlayer body, JsonObject args,
            CompanionScheduler scheduler, ActionPermissions permissions, ActionPermissions.Context context) {
        Arguments input;
        com.neko.mcbot.building.BuildRecords.Instance instance=null;
        try {
            if (args.has("target") && args.getAsJsonObject("target").has("instance_id"))
                instance=BuildRecordAccess.instance(body,args.getAsJsonObject("target"));
            input = arguments(args,target->BuildRecordAccess.resolve(body,target));
        }
        catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture(new Result(false,
                    "DENIED:build_site query仅需operation；begin需target，可选access_route/bounds/expected_revision；"
                            + "checkpoint需expected_revision。无需猜写通道，其他字段不支持；target或修订须有效。", null));
        }
        if (!"query".equals(input.operation()) && (context == null
                || !context.equals(permissions.current(context.owner(), body.getUUID(), context.dimension(), context.taskId(), body))
                || !ActionPermissions.allowed(context, name(), args)))
            return CompletableFuture.completedFuture(new Result(false, "DENIED:工地登记须原任务、身体及动作许可。", null));
        body.bindSpatialTask(context);
        var site = body.buildSite();
        if ("begin".equals(input.operation()) && site.active()
                && !java.util.Objects.equals(site.instanceId(),instance==null ? null : instance.id()))
            return CompletableFuture.completedFuture(new Result(false,"TARGET_REVISION_CHANGED:工地实例绑定已冻结，未更换来源或重开预算。",null));
        var access = new BuildSiteAccess(body);
        Result failure = switch (input.operation()) {
            case "begin" -> site.begin(input.target(), input.route(), StandingGeometry.feetCell(body),
                    input.scope() != null ? input.scope() : site.active() ? site.scope()
                    : com.neko.mcbot.path.PathSpec.DEFAULT, access);
            case "checkpoint" -> site.checkpoint(input.revision(), StandingGeometry.feetCell(body), access);
            default -> null;
        };
        if (failure==null && instance!=null) site.attach(BuildRecordAccess.store(body),instance);
        var data = site.report(access);
        if (failure == null && site.active() && !"checkpoint".equals(input.operation()))
            BuildRelocation.describe(body, data, "query".equals(input.operation()));
        return CompletableFuture.completedFuture(new Result(failure == null, failure == null
                ? "工地事实已核对；登记不改世界。未声明通道时由正常导航选择真实工作站位；"
                        + "out_of_reach只表示当前手够不着，应换站位继续，不等于整个设计不可达。"
                : failure.feedback(), data));
    }
}
