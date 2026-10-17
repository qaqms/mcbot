package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.neko.mcbot.path.NavigationBudget;
import com.neko.mcbot.task.TickTask;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Trigger and lifecycle only. Native strikes and movement remain ordinary shared tasks. */
public final class BodyDefense {
    public static final int CAP_TICKS = 400, CALM_TICKS = 20, SOURCE_LIMIT = 64;
    public record Frame(Object world, boolean allowed, boolean higherPriority, boolean meleeActive,
                        Vec3 position, AABB bounds, float health, float maxHealth, boolean armed,
                        ThreatFacts.Snapshot facts) { }
    public interface Access {
        boolean acquire(BlockPos anchor);
        boolean valid();
        TickTask attack(ThreatFacts.Threat threat, int maxHits, boolean approach, NavigationBudget budget);
        BlockPos haven(Frame frame, BlockPos anchor);
        TickTask retreat(BlockPos haven, NavigationBudget budget);
        TickTask.Progress advance(TickTask task);
        ServerTool.Result resume(TickTask task);
        void release();
        void stopInput();
    }

    private final Access access;
    // No eviction buys a failed identity another attack, route, permission or time allowance.
    private final Set<Object> handled = Collections.newSetFromMap(new IdentityHashMap<>());
    private ThreatFacts.Threat target;
    private BlockPos anchor, haven;
    private Object world;
    private NavigationBudget budget;
    private TickTask work;
    private boolean active, suspended, blocked, retreating, cornered, retreatDone;
    private int elapsed, calm, strikes, lastPlan;
    private float healthLoss, absorptionLoss;
    private boolean observationKnown = true;
    private String state = "idle";
    private ServerTool.Result last;

    public BodyDefense(Access access) { this.access = access; }
    public boolean active() { return active; }
    public String state() { return state; }

    public void tick(Frame frame) {
        if (!active) {
            if (blocked || !frame.allowed() || frame.higherPriority() || !frame.facts().complete()) return;
            ThreatFacts.Threat candidate = frame.facts().threats().stream()
                    .filter(t -> !handled.contains(t.identity()))
                    .filter(t -> t.dangerous(frame.bounds(), .75) || t.recentAttacker() || vulnerable(frame))
                    .findFirst().orElse(null);
            if (candidate == null) return;
            boolean flee = mustRetreat(frame, candidate) || candidate.recentAttacker()
                    && !candidate.dangerous(frame.bounds(), .75);
            if (frame.meleeActive() && !flee) return;
            if (handled.size() >= SOURCE_LIMIT) { state = "source_limit"; return; }
            handled.add(candidate.identity());
            anchor = BlockPos.containing(frame.position());
            if (!access.acquire(anchor)) { state = "busy"; return; }
            target = candidate;
            world = frame.world();
            elapsed = calm = strikes = 0;
            healthLoss = absorptionLoss = 0;
            observationKnown = true;
            budget = new NavigationBudget();
            retreating = flee;
            cornered = suspended = false;
            retreatDone = false;
            haven = null;
            lastPlan = -10;
            active = true;
            state = flee ? "retreat" : "defend";
        }
        if (!frame.allowed() || frame.world() != world || !access.valid()) {
            finish("DENIED:自保原身体、世界或许可已失效，未续旧动作。");
            return;
        }
        if (++elapsed >= CAP_TICKS) {
            finish("TIMEOUT:自保达到原400刻总帽（含水中避险暂停），未重开。");
            return;
        }
        if (frame.higherPriority()) {
            if (!suspended && work != null) work.onSuspend();
            suspended = true;
            access.stopInput();
            return;
        }
        if (!frame.facts().complete()) {
            finish("UNKNOWN:威胁扫描不完整，不能把未见来源当作安全或换目标。");
            return;
        }
        ThreatFacts.Threat fresh = frame.facts().threats().stream()
                .filter(t -> t.identity() == target.identity() && t.id() == target.id() && t.uuid().equals(target.uuid()))
                .findFirst().orElse(null);
        boolean unsafe = frame.facts().threats().stream().anyMatch(t -> t.dangerous(frame.bounds(), 1.5)
                || t.recentAttacker());
        if (!unsafe) {
            stopWork("INTERRUPTED:当前局部已拉开，等待连续新鲜观察。");
            state = "calm";
            if (++calm >= CALM_TICKS) finish("LOCAL_CLEAR:连续20刻未见局部危险；不保证已甩掉追兵或整项目标完成。");
            return;
        }
        calm = 0;
        if (fresh == null && !retreating) {
            finish("TARGET_LOST:原攻击来源已消失或换身份，未沿用编号改打其他目标。");
            return;
        }
        if (fresh != null) target = fresh;
        if (!retreating && (mustRetreat(frame, target)
                || frame.facts().threats().stream().anyMatch(t -> t.explosive() && t.dangerous(frame.bounds(), 1.5)))) {
            stopWork("INTERRUPTED:真实风险改变，转为原范围内撤退。");
            retreating = true;
        }
        if (suspended) {
            suspended = false;
            if (work != null) {
                var failure = access.resume(work);
                if (failure != null) { finish(failure.feedback()); return; }
            }
        }
        if (work == null) {
            if (retreating && !cornered) {
                if (retreatDone) {
                    finish("LOCAL_BLOCKED:原撤退落点已到，新的实际观察仍有危险；不重发路线或续开范围。");
                    return;
                }
                if (haven == null) haven = access.haven(frame, anchor);
                if (haven == null) { noRetreat(frame); return; }
                if (lastPlan >= 0 && !budget.takeReplan() || budget.exhausted()) {
                    finish("ACTION_LIMIT:自保原累计导航账已耗尽，未重新选范围或目标。");
                    return;
                }
                lastPlan = elapsed;
                work = access.retreat(haven, budget);
                state = "retreat";
            } else {
                if (!target.attackAllowed() || target.explosive() || strikes >= 10
                        || frame.facts().threats().stream().anyMatch(t -> t.explosive() && t.dangerous(frame.bounds(), 1.5))) {
                    finish("DENIED:当前来源不能自动攻击，未以撤退失败扩大许可。");
                    return;
                }
                if (!cornered && lastPlan >= 0 && !budget.takeReplan()) {
                    finish("ACTION_LIMIT:自保原换位账已耗尽，未重新接近。");
                    return;
                }
                if (!cornered) lastPlan = elapsed;
                work = access.attack(target, 10 - strikes, !cornered, budget);
                state = cornered ? "cornered" : "defend";
            }
        }
        var progress = access.advance(work);
        if (progress instanceof TickTask.Progress.Done done) {
            ServerTool.Result result = done.result();
            boolean wasRetreat = retreating && !cornered;
            record(result);
            work.onFinish();
            work = null;
            if (wasRetreat) {
                // A route terminates once; a failed haven is never issued again every tick.
                if (!result.ok()) {
                    if (result.feedback().startsWith("NO_PATH:") || result.feedback().startsWith("NO_PROGRESS:"))
                        noRetreat(frame);
                    else finish(result.feedback());
                }
                else retreatDone = true;
            } else finish(result.feedback());
        }
    }

    private static boolean vulnerable(Frame frame) {
        return !Float.isFinite(frame.health()) || !Float.isFinite(frame.maxHealth()) || frame.maxHealth() <= 0
                || frame.health() <= frame.maxHealth() * .4f || !frame.armed();
    }
    private static boolean mustRetreat(Frame frame, ThreatFacts.Threat threat) {
        return vulnerable(frame) || !threat.attackAllowed() || threat.explosive();
    }

    private void noRetreat(Frame frame) {
        access.stopInput();
        if (target.attackAllowed() && !target.explosive() && strikes < 10
                && frame.facts().threats().stream().noneMatch(t -> t.explosive() && t.dangerous(frame.bounds(), 1.5))) {
            cornered = true;
            state = "cornered";
        } else finish("NO_PATH:原范围内没有可用撤退；保护或爆炸来源不允许自动攻击。");
    }

    private void record(ServerTool.Result result) {
        if (result != null) {
            var summary = new JsonObject();
            if (result.data() != null) {
                var data = result.data();
                if (data.has("strikes")) strikes += data.get("strikes").getAsInt();
                if (data.has("observed_health_loss")) healthLoss += data.get("observed_health_loss").getAsFloat();
                if (data.has("observed_absorption_loss")) absorptionLoss += data.get("observed_absorption_loss").getAsFloat();
                if (data.has("observation_known")) observationKnown &= data.get("observation_known").getAsBoolean();
                for (String key : java.util.List.of("entity_id", "target_uuid", "target_dead", "strikes",
                        "observed_health_loss", "observed_absorption_loss", "observation_known",
                        "removed_blocks", "placed_blocks", "x", "y", "z", "feet_y"))
                    if (data.has(key)) summary.add(key, data.get(key).deepCopy());
            }
            last = new ServerTool.Result(result.ok(),
                    com.neko.mcbot.common.WireSize.truncateToBytes(result.feedback(), 1024), summary);
        }
    }
    private void stopWork(String reason) {
        if (work == null) return;
        try { record(work.interruptedResult(new ServerTool.Result(false, reason, null))); }
        finally {
            try { work.onAbort(); }
            finally { work = null; }
        }
    }
    private void finish(String reason) {
        try { stopWork(reason); }
        finally {
            active = suspended = false;
            int separator = reason.indexOf(':');
            state = separator < 0 ? "finished" : reason.substring(0, separator).toLowerCase(java.util.Locale.ROOT);
            var data = last == null || last.data() == null ? new JsonObject() : last.data().deepCopy();
            data.addProperty("episode_strikes", strikes);
            data.addProperty("episode_health_loss", healthLoss);
            data.addProperty("episode_absorption_loss", absorptionLoss);
            data.addProperty("observation_known", observationKnown);
            data.addProperty("elapsed_ticks", elapsed);
            last = new ServerTool.Result(reason.startsWith("LOCAL_CLEAR:"), reason, data);
            access.release();
        }
    }

    public void cancel() {
        blocked = true;
        if (active) finish("CANCELLED:主人已停止身体自保；新任务前不自动恢复。");
        else state = "cancelled";
    }
    public void stopCurrent() {
        if (active) finish("CANCELLED:原任务生命周期结束，自保原动作已停止。");
    }
    public void newTask() { blocked = false; }

    public JsonObject observation() {
        var data = new JsonObject();
        data.addProperty("active", active);
        data.addProperty("state", state);
        data.addProperty("elapsed_ticks", elapsed);
        data.addProperty("cap_ticks", CAP_TICKS);
        data.addProperty("handled_sources", handled.size());
        data.addProperty("strikes", strikes);
        data.addProperty("observed_health_loss", healthLoss);
        data.addProperty("observed_absorption_loss", absorptionLoss);
        data.addProperty("observation_known", observationKnown);
        data.addProperty("suspended", suspended);
        data.addProperty("automatic_resume_blocked", blocked);
        if (target != null) {
            data.addProperty("entity_id", target.id());
            data.addProperty("target_uuid", target.uuid().toString());
        }
        if (anchor != null) {
            data.addProperty("anchor", anchor.asLong());
            data.addProperty("radius", 6);
        }
        if (haven != null) {
            var point = new JsonObject();
            point.addProperty("x", haven.getX()); point.addProperty("y", haven.getY()); point.addProperty("z", haven.getZ());
            data.add("haven", point);
        }
        if (budget != null) {
            data.addProperty("search_nodes", budget.nodes());
            data.addProperty("search_records", budget.records());
            data.addProperty("search_nanos", budget.nanos());
            data.addProperty("replans", budget.replans());
        }
        if (work != null) data.add("work", work.observation());
        if (last != null) {
            data.addProperty("last_reason", com.neko.mcbot.common.WireSize.truncateToBytes(last.feedback(), 1024));
            if (last.data() != null) data.add("last_result", last.data().deepCopy());
        }
        return data;
    }
}
