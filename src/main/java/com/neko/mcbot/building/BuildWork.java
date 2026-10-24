package com.neko.mcbot.building;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.BlockPlacement;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.task.TickTask;
import net.minecraft.core.Direction;

import java.util.function.LongSupplier;

/** One native action per tick, with explicit-page or automatic whole-target selection. */
public final class BuildWork extends TickTask {
    public static final int MAX_ACTIONS = 12, CAP_TICKS = 200;
    public static final int MAX_PREFLIGHTS = MAX_ACTIONS * BuildSurvey.MAX_PREFLIGHTS;
    public static final long MAX_NANOS = 400_000_000L;

    public interface Access {
        BuildSurvey.Access survey();
        ServerTool.Result valid();
        boolean reserve(BuildTarget.Cell cell);
        default void recorded(BuildTarget.Cell cell, ServerTool.Result result) { }
    }

    private final BuildTarget target;
    private final Access access;
    private final int offset, limit;
    private final LongSupplier clock;
    private final JsonArray results = new JsonArray();
    private final java.util.Set<net.minecraft.core.BlockPos> attempted = new java.util.HashSet<>();
    private int attempts, placed, consumed, checks, surveys, settle;
    private int pageOffset, emptyPages;
    private long nanos;
    private boolean changed, stopped, finishing;
    private ServerTool.Result failure, terminal;
    private JsonObject observed;

    public BuildWork(BuildTarget target, Access access, int offset, int limit, LongSupplier clock) {
        if (offset < -1 || offset >= target.cells().size() || offset >= 0 && offset % BuildSurvey.PAGE != 0
                || limit < 1 || limit > MAX_ACTIONS) throw new IllegalArgumentException("bounded work");
        this.target = target;
        this.access = access;
        this.offset = offset;
        this.pageOffset = Math.max(0, offset);
        this.limit = limit;
        this.clock = clock;
    }

    @Override public Progress tick(CompanionPlayer body) {
        if (terminal != null) return new Progress.Done(terminal);
        if (stopped) return new Progress.Done(interruptedResult(
                new ServerTool.Result(false, "CANCELLED:施工已停止，不自动续接。", null)));
        var invalid = access.valid();
        if (invalid != null) return done(invalid, false);
        if (finishing) {
            if (++settle < 2) return running();
            observed = BuildSurvey.verify(target, access.survey(), pageOffset, clock).data();
            return done(failure, true);
        }
        int pages = (target.cells().size() + BuildSurvey.PAGE - 1) / BuildSurvey.PAGE;
        int surveyLimit = offset < 0 ? (MAX_ACTIONS + 1) * pages : MAX_ACTIONS;
        if (nanos >= MAX_NANOS || surveys >= surveyLimit
                || checks >= surveyLimit * BuildSurvey.MAX_PREFLIGHTS) {
            failure = new ServerTool.Result(false, "ACTION_LIMIT:本批施工工作预算已到，未扩大预算或重试。", null);
            finishing = true;
            return running();
        }
        long start = clock.getAsLong();
        try {
            surveys++;
            var survey = BuildSurvey.queryForWork(target, access.survey(), pageOffset, clock);
            observed = survey.data();
            checks += observed.get("work_preflights").getAsInt();
            if (observed.get("budget_exhausted").getAsBoolean()
                    || clock.getAsLong() - start + nanos >= MAX_NANOS) {
                failure = new ServerTool.Result(false, "ACTION_LIMIT:本批观察预算已到，未按过时预检施工。", null);
                finishing = true;
                return running();
            }
            JsonObject ready = null;
            for (var value : observed.getAsJsonArray("cells")) {
                var cell = value.getAsJsonObject();
                var pos = new net.minecraft.core.BlockPos(cell.get("x").getAsInt(),
                        cell.get("y").getAsInt(), cell.get("z").getAsInt());
                if ("ready".equals(cell.get("state").getAsString()) && !attempted.contains(pos)) { ready = cell; break; }
            }
            if (ready == null && offset < 0 && ++emptyPages < pages && attempts < limit) {
                pageOffset = pageOffset + BuildSurvey.PAGE < target.cells().size()
                        ? pageOffset + BuildSurvey.PAGE : 0;
                return running();
            }
            if (ready == null || attempts >= limit) {
                finishing = true;
                return running();
            }
            var pos = new net.minecraft.core.BlockPos(ready.get("x").getAsInt(),
                    ready.get("y").getAsInt(), ready.get("z").getAsInt());
            var cell = target.cells().stream().filter(c -> c.pos().equals(pos)).findFirst().orElseThrow();
            invalid = access.valid();
            if (invalid != null) return done(invalid, false);
            if (!access.reserve(cell)) {
                failure = new ServerTool.Result(false, "BUSY:本批目标保护或区域租约不足，未放置该格。", null);
                finishing = true;
                return running();
            }
            invalid = access.valid();
            if (invalid != null) return done(invalid, false);
            var fresh = access.survey();
            var actual = fresh.inBounds(pos) ? fresh.state(pos) : null;
            String state = BuildSurvey.cellState(target, cell, fresh, actual);
            if ("satisfied".equals(state)) return running();
            if (!"not_assessed".equals(state)) {
                failure = new ServerTool.Result(false, "TARGET_BLOCKED:执行前目标条件变化：" + state + "；未放置。", null);
                finishing = true;
                return running();
            }
            int slot = BuildInventory.slot(fresh.placement().inventory(), cell.material().item());
            // Selection is advisory. The native context, stack and authored state are checked again here.
            attempts++;
            attempted.add(pos);
            var result = BlockPlacement.placeDesired(fresh.placement(), pos, slot,
                    Direction.byName(ready.get("face").getAsString()), target, cell);
            var row = cell.describe();
            row.addProperty("ok", result.ok());
            if (result.data() != null) {
                row.add("effect", result.data().deepCopy());
                if (result.data().has("consumed_count")) consumed += result.data().get("consumed_count").getAsInt();
                changed |= result.data().has("changed") && result.data().get("changed").getAsBoolean();
            }
            results.add(row);
            emptyPages = 0;
            access.recorded(cell, result);
            if (result.ok()) placed++;
            else { failure = result; finishing = true; }
            if (attempts >= limit) finishing = true;
            return running();
        } finally {
            nanos += Math.max(0, clock.getAsLong() - start);
        }
    }

    private Progress done(ServerTool.Result reason, boolean verified) {
        var data = observation();
        if (observed != null) data.add("survey", observed.deepCopy());
        boolean satisfied = verified && observed != null && observed.get("goal_satisfied").getAsBoolean();
        data.addProperty("goal_satisfied", satisfied);
        data.addProperty("final_verified", verified && observed.get("observation_complete").getAsBoolean());
        data.addProperty("batch_finished", verified && reason == null);
        data.addProperty("whole_remaining", observed == null ? target.cells().size()
                : observed.get("remaining").getAsInt());
        data.addProperty("page_satisfied", observed != null && observed.get("page_satisfied").getAsBoolean());
        data.addProperty("no_work_here", placed == 0 && !satisfied);
        if (com.neko.mcbot.common.WireSize.utf8Bytes(data.toString()) > 20_000 && data.has("survey")) {
            var summary = data.getAsJsonObject("survey");
            summary.remove("cells");
            summary.remove("materials");
            summary.addProperty("details_complete", false);
            summary.addProperty("report_details_omitted", true);
            summary.addProperty("query_details_with_build_diff", true);
        }
        boolean ok = reason == null && (placed > 0 || satisfied);
        terminal = new ServerTool.Result(ok, reason != null ? reason.feedback()
                : ok ? "近身施工本批结束：实际放置" + placed + "格，消耗" + consumed
                + "个；整目标满足=" + satisfied + "。未施工页及剩余缺口须读同修订新鲜差异。"
                : "TARGET_BLOCKED:" + (offset < 0 ? "全目标在当前站位没有可施工格" : "指定页在当前站位没有可施工格")
                + "；整目标仍缺" + data.get("whole_remaining").getAsInt()
                + "格。本页/本批结束不代表整栋完成；按同修订差异换工作站位、清障或补材料。", data);
        return new Progress.Done(terminal);
    }

    @Override public JsonObject observation() {
        var data = new JsonObject();
        data.addProperty("target_revision", target.revision());
        data.addProperty("offset", pageOffset);
        data.addProperty("work_scope", offset < 0 ? "whole_target_nearby" : "current_page");
        data.addProperty("next_offset", pageOffset + BuildSurvey.PAGE < target.cells().size()
                ? pageOffset + BuildSurvey.PAGE : -1);
        data.addProperty("max_actions", limit);
        data.addProperty("action_attempts", attempts);
        data.addProperty("placed_blocks", placed);
        data.addProperty("consumed_count", consumed);
        data.addProperty("changed", changed);
        data.addProperty("work_preflights", checks);
        data.addProperty("work_surveys", surveys);
        data.add("placement_results", results.deepCopy());
        boolean complete = true;
        for (var row : data.getAsJsonArray("placement_results")) {
            if (com.neko.mcbot.common.WireSize.utf8Bytes(data.toString()) <= 8192) break;
            var effect = row.getAsJsonObject().getAsJsonObject("effect");
            if (effect != null) {
                effect.remove("affected_cells");
                effect.addProperty("affected_cells_complete", false);
                complete = false;
            }
        }
        data.addProperty("placement_results_complete", complete);
        data.addProperty("goal_satisfied", false);
        data.addProperty("final_verified", false);
        return data;
    }

    @Override public void onAbort() { stopped = true; }
    @Override public ServerTool.Result onResume(CompanionPlayer body) {
        // Keep the original page, action count and CPU budget. tick performs all fresh checks.
        return access.valid();
    }
    @Override public ServerTool.Result interruptedResult(ServerTool.Result reason) {
        stopped = true;
        var progress = (Progress.Done) done(reason, false);
        return progress.result();
    }
}
