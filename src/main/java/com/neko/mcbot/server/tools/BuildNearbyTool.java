package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.neko.mcbot.body.BodyControl;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.building.BuildSurvey;
import com.neko.mcbot.building.BuildTarget;
import com.neko.mcbot.building.BuildWork;
import com.neko.mcbot.server.ActionPermissions;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.task.CompanionScheduler;
import com.neko.mcbot.task.ResourceLocks;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class BuildNearbyTool implements ServerTool {
    record Arguments(BuildTarget target, int offset, int maxActions) { }

    static Arguments arguments(JsonObject args) {
        BuildTarget.keys(args, Set.of("target", "expected_revision", "offset", "max_actions"));
        var target = BuildTarget.parse(args.getAsJsonObject("target"));
        String expected = BuildTarget.string(args.get("expected_revision"), 64);
        if (!expected.equals(target.revision())) throw new IllegalArgumentException("revision");
        int offset = args.has("offset") ? BuildTarget.integer(args.get("offset"), 0, 112) : 0;
        if (offset >= target.cells().size() || offset % BuildSurvey.PAGE != 0) throw new IllegalArgumentException("page");
        int limit = args.has("max_actions") ? BuildTarget.integer(args.get("max_actions"), 1, BuildWork.MAX_ACTIONS) : 12;
        return new Arguments(target, offset, limit);
    }

    @Override public String name() { return "build_nearby"; }
    @Override public Acceptance acceptanceMode() { return Acceptance.ACCEPT; }
    @Override public int capTicks(JsonObject args) { return BuildWork.CAP_TICKS; }
    @Override public String acceptSubject(JsonObject args) { return "当前页近身施工"; }

    @Override public CompletableFuture<Result> runAuthorized(CompanionPlayer body, JsonObject args,
            CompanionScheduler scheduler, ActionPermissions permissions, ActionPermissions.Context context) {
        Arguments input;
        try {
            if (args.has("target") && args.getAsJsonObject("target").has("instance_id")
                    && !java.util.Objects.equals(body.buildSite().instanceId(),
                    args.getAsJsonObject("target").get("instance_id").getAsString()))
                throw new IllegalArgumentException("instance_site_required");
            input = arguments(com.neko.mcbot.server.BuildRecordAccess.expanded(body,args));
        }
        catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture(new Result(false,
                    "DENIED:须提供有效target和匹配的expected_revision；offset为16的倍数，max_actions为1-12。", null));
        }
        var level = body.level();
        if (body.buildSite().active() && !body.buildSite().same(input.target().revision()))
            return CompletableFuture.completedFuture(new Result(false, "TARGET_REVISION_CHANGED:不能用另一目标改动已登记工地。", null));
        var work = new BuildWork(input.target(), new BuildWork.Access() {
            @Override public BuildSurvey.Access survey() { return BuildDiffTool.access(body, input.target()); }
            @Override public Result valid() {
                var siteFailure = body.buildSite().active() ? body.buildSite().live(new com.neko.mcbot.server.BuildSiteAccess(body)) : null;
                if (siteFailure != null) return siteFailure;
                return context != null && context.equals(permissions.current(context.owner(), body.getUUID(),
                        context.dimension(), context.taskId(), body)) && body.level() == level
                        && body.bodyControl().owner() == BodyControl.Owner.WORK
                        && ActionPermissions.allowed(context, name(), args) ? null
                        : new Result(false, "DENIED:原身体、维度、任务许可或控制权已失效，未续施工。", null);
            }
            @Override public boolean reserve(BuildTarget.Cell cell) {
                var p = cell.pos();
                return (body.buildSite().same(input.target().revision()) || body.workTargets().canAdd(p))
                        && scheduler.reserve(body.getUUID(), java.util.List.of(new ResourceLocks.Region(
                        context.dimension(), p.getX() - 1, p.getY() - 1, p.getZ() - 1,
                        p.getX() + 1, p.getY() + 1, p.getZ() + 1)));
            }
            @Override public void recorded(BuildTarget.Cell cell, Result result) {
                if (result.data() != null && result.data().has("changed")
                        && result.data().get("changed").getAsBoolean()
                        && !body.buildSite().same(input.target().revision())) body.workTargets().add(cell.pos());
            }
        }, input.offset(), input.maxActions(), System::nanoTime);
        var future = new CompletableFuture<Result>();
        if (!scheduler.submit(body, work, future, capTicks(args)))
            return CompletableFuture.completedFuture(new Result(false, "BUSY:身体任务槽已占用，未开始施工。", null));
        return future;
    }
}
