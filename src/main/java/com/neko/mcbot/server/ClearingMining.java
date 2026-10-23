package com.neko.mcbot.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;

/** A concrete, bounded sequence. The requested target and incidental clears have separate receipts. */
public final class ClearingMining {
    public interface Access {
        BlockAim.MiningPlan plan();
        BlockState state(BlockPos pos);
        boolean safe(BlockPos pos);
        BlockMining mining(BlockPos pos);
        boolean reserve(Map<String, ActionPermissions.Change> changes);
        boolean sameHand();
        default void completed() { }
        default ServerTool.Result targetGuard() { return null; }
    }

    private final Access access;
    private final BlockPos target;
    private final String expectedBlock;
    private final ActionPermissions permissions;
    private final ActionPermissions.Context context;
    private final ActionPermissions.Execution approval;
    private final int clearLimit;
    private final Map<String, ActionPermissions.Change> planned = new LinkedHashMap<>();
    private final JsonArray clears = new JsonArray();
    private BlockMining mining;
    private BlockPos active;
    private ServerTool.Result terminal;
    private boolean initialized;
    private boolean requiresApproval;
    private final java.util.List<BlockMining> completedMining = new java.util.ArrayList<>();
    private BlockMining targetMining;

    public ClearingMining(Access access, BlockPos target, String expectedBlock,
                          ActionPermissions permissions, ActionPermissions.Context context,
                          ActionPermissions.Scope approved, int clearLimit) {
        this.access = access;
        this.target = target;
        this.expectedBlock = expectedBlock;
        this.permissions = permissions;
        this.context = context;
        this.approval = new ActionPermissions.Execution(approved);
        this.clearLimit = clearLimit;
    }

    /** Null means an approved plan is ready. No world effects occur here. */
    public ServerTool.Result prepare() {
        if (initialized) return terminal;
        initialized = true;
        if (!BlockMining.matchesExpected(access.state(target), expectedBlock))
            return finish(false, "TARGET_LOST:目标被替换，未清理遮挡或挖替换物。", null);
        var targetFailure = access.targetGuard();
        if (targetFailure != null && !targetFailure.feedback().startsWith("TARGET_BLOCKED:"))
            return finish(false, targetFailure.feedback(), null);
        var plan = access.plan();
        if (!plan.ok()) return finish(false, plan.aim().failure(), null);
        if (plan.blockers().size() > clearLimit)
            return finish(false, "BLOCK_LIMIT:所需遮挡清理超过本次剩余挖掘预算，未动世界。", null);
        for (BlockPos pos : plan.blockers()) add(pos);
        add(target);
        requiresApproval = !plan.blockers().isEmpty();
        if (!plan.blockers().isEmpty() && !permissions.allowsChanges(context, approval, planned)) {
            String id = permissions.propose(new ActionPermissions.Scope(context, target.asLong(), planned));
            var data = new JsonObject();
            StringBuilder summary = new StringBuilder("挖掘目标 ").append(target.toShortString())
                    .append("；射线遮挡 ").append(plan.blockers().size()).append(" 格。具体清单：");
            for (var change : planned.values()) summary.append(BlockPos.of(change.position()).toShortString())
                    .append(' ').append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(
                            ((BlockState) change.expectedState()).getBlock())).append(';');
            if (id != null) {
                data.addProperty("authorization_id", id);
                data.addProperty("authorization_summary", summary.toString());
            }
            return finish(false, "NEED_CONFIRM:" + summary
                    + (id == null ? " 当前没有可用任务授权。"
                    : " 用ask_owner携带authorization_id=" + id
                    + "确认后，以相同目标、clear_obstructions=true和批准编号重发break_block。"),
                    data);
        }
        if (!access.reserve(planned)) return finish(false, "BUSY:局部清理区域被其他动作占用。", null);
        return null;
    }

    private void add(BlockPos pos) {
        var change = new ActionPermissions.Change("dig", pos.asLong(), access.state(pos));
        planned.put(ActionPermissions.key(change), change);
    }

    public ServerTool.Result tick() {
        if (terminal != null) return terminal;
        if (!initialized) {
            var failure = prepare();
            if (failure != null) return failure;
        }
        if (!access.sameHand()) return finish(false, "WRONG_TOOL:局部清理期间主手已改变，已停手。", null);
        if (!BlockMining.matchesExpected(access.state(target), expectedBlock))
            return finish(false, "TARGET_LOST:清理期间目标被替换，未挖替换物。", null);
        if (mining == null) {
            var live = access.plan();
            if (!live.ok()) return finish(false, live.aim().failure(), null);
            active = live.blockers().isEmpty() ? target : live.blockers().getFirst();
            if (!active.equals(target) && clears.size() >= clearLimit)
                return finish(false, "BLOCK_LIMIT:本次局部清理预算已到。", null);
            mining = access.mining(active);
        }
        var change = new ActionPermissions.Change("dig", active.asLong(), access.state(active));
        if (!change.equals(planned.get(ActionPermissions.key(change))))
            return finish(false, "TARGET_BLOCKED:出现原清单外遮挡或状态变化，未扩大清理范围。", null);
        if (!active.equals(target) && !access.safe(active)
                || requiresApproval && !permissions.allowsChange(context, approval, change))
            return finish(false, "DENIED:遮挡清理许可或安全条件已变化，已停手。", null);
        var result = mining.tick();
        if (result == null) return null;
        access.completed();
        boolean removed = result.data().get("removed").getAsBoolean();
        if (active.equals(target)) {
            targetMining = mining;
            if (removed) approval.observed("dig", active.asLong());
            return finish(result.ok(), result.feedback(), result.data());
        }
        if (removed) {
            approval.observed("dig", active.asLong());
            var record = new JsonObject();
            record.addProperty("x", active.getX()); record.addProperty("y", active.getY());
            record.addProperty("z", active.getZ());
            record.add("result", result.data().deepCopy());
            clears.add(record);
            completedMining.add(mining);
        }
        mining = null;
        if (!result.ok()) return finish(false, result.feedback(), null);
        return null;
    }

    private ServerTool.Result finish(boolean ok, String feedback, JsonObject targetData) {
        abort();
        var data = targetData == null ? new JsonObject() : targetData.deepCopy();
        if (!data.has("removed")) data.addProperty("removed", false);
        data.addProperty("cleared_blocks", clears.size());
        data.add("clearing_results", clears.deepCopy());
        terminal = new ServerTool.Result(ok, feedback + (clears.isEmpty() ? ""
                : " 本次另外实际清理遮挡 " + clears.size() + " 格；这不计为目标资源或入包成果。"), data);
        return terminal;
    }

    public void abort() { if (mining != null) mining.abort(); }

    public void suspend() { if (mining != null) mining.suspend(); }

    public JsonObject observation() {
        var data = mining == null ? new JsonObject() : mining.observation();
        data.addProperty("cleared_blocks", clears.size());
        return data;
    }

    public ServerTool.Result interrupted(String feedback) {
        return refreshDrops(finish(false, feedback, terminal == null ? null : terminal.data()));
    }

    public ServerTool.Result refreshDrops(ServerTool.Result result) {
        var data = result.data() == null ? new JsonObject() : result.data().deepCopy();
        String feedback = result.feedback();
        var observed = new DropObservations();
        var reports = new java.util.HashMap<String, JsonObject>();
        for (int i = 0; i < completedMining.size(); i++) {
            var clear = clears.get(i).getAsJsonObject();
            var prior = new ServerTool.Result(true, "", clear.getAsJsonObject("result"));
            clear.add("result", completedMining.get(i).refreshDrops(prior).data());
            var refreshed = clear.getAsJsonObject("result");
            observed.record(refreshed);
            if (refreshed.has("drop_accounting")) {
                var report = refreshed.getAsJsonObject("drop_accounting");
                reports.put(report.get("ledger_id").getAsString() + ":" + report.get("origin_id").getAsString(), report);
            }
        }
        if (targetMining != null) {
            var refreshed = targetMining.refreshDrops(new ServerTool.Result(result.ok(), feedback, data));
            data = refreshed.data();
            feedback = refreshed.feedback();
        }
        else if (mining != null && active != null) {
            var partial = mining.refreshDrops(new ServerTool.Result(false, "", null));
            if (partial.data() != null) data.add("active_mining_result", partial.data());
        }
        data.add("clearing_results", clears.deepCopy());
        if (!completedMining.isEmpty()) {
            var summary = observed.snapshotSources(token -> reports.get(
                    token.get("ledger_id").getAsString() + ":" + token.get("origin_id").getAsString()));
            data.add("clearing_drop_summary", summary);
            feedback += " 清障产物：" + DropObservations.sentence(summary);
        }
        return new ServerTool.Result(result.ok(), feedback, data);
    }
}
