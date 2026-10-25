package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.path.NavigationBudget;
import com.neko.mcbot.path.NavigationGoal;
import com.neko.mcbot.path.PathSpec;
import com.neko.mcbot.path.TerrainEdits;
import com.neko.mcbot.task.TickTask;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Continuous work in the original scheduler. Arrival parks the task without settling its call. */
public final class FollowWork extends TickTask {
    public record Target(UUID uuid, Vec3 position, AABB bounds) { }
    public interface Access {
        ServerTool.Result guard();
        Target target();
        ServerTool.Result guard(Target target);
        Vec3 position();
        boolean arrived(Target target, int distance);
        TickTask route(Target target, int distance, NavigationBudget budget);
        default boolean routeUseful(TickTask route, Target target, int distance) { return false; }
        default boolean retarget(TickTask route, Target target, int distance) { return false; }
        TickTask.Progress advance(TickTask route);
        void stopInput();
        PathSpec scope();
        default ThreatFacts.Snapshot threats(Target owner) {
            return new ThreatFacts.Snapshot(java.util.List.of(), true);
        }
        default TickTask attack(ThreatFacts.Threat enemy, NavigationBudget budget) {
            throw new IllegalStateException("protection unavailable");
        }
    }

    private final Access access;
    private final UUID targetId;
    private final boolean owner;
    private final int distance, duration;
    private final boolean protectOwner;
    private final NavigationBudget budget;
    private final EntityApproach movement;
    private Target target;
    private String phase = "sleeping";
    private int elapsed, wakes, sleepingTicks, absentTicks;
    private boolean inBand;
    private boolean inBandKnown;
    private ServerTool.Result terminal;
    private ThreatFacts.Threat enemy;
    private TickTask fight;
    private ThreatFacts.Snapshot threats = new ThreatFacts.Snapshot(java.util.List.of(), false);
    private int threatScanElapsed = -1;
    private Vec3 threatScanOwner;
    private int engagements, strikes, disengagements;
    private JsonObject lastCombat;
    private final java.util.ArrayDeque<JsonObject> combatHistory = new java.util.ArrayDeque<>();
    private int combatHistoryOmitted, observedDeaths;
    private float observedHealthLoss, observedAbsorptionLoss;
    private boolean combatObservationKnown = true;

    public FollowWork(Access access, UUID targetId, boolean owner, int distance, int duration,
                      NavigationBudget budget) {
        this(access, targetId, owner, distance, duration, budget, false);
    }

    public FollowWork(Access access, UUID targetId, boolean owner, int distance, int duration,
                      NavigationBudget budget, boolean protectOwner) {
        if (targetId == null || distance < 2 || distance > 6 || duration < 1 || duration > 1200)
            throw new IllegalArgumentException("follow bounds");
        this.access = java.util.Objects.requireNonNull(access);
        this.targetId = targetId;
        this.owner = owner;
        this.distance = distance;
        this.duration = duration;
        this.protectOwner = protectOwner;
        this.budget = budget;
        movement = new EntityApproach(new EntityApproach.Access() {
            @Override public Vec3 targetPosition() { return target.position(); }
            @Override public boolean canStrike() { return access.arrived(target, distance); }
            @Override public TickTask route(NavigationBudget account) { return access.route(target, distance, account); }
            @Override public boolean routeUseful(TickTask route) { return access.routeUseful(route, target, distance); }
            @Override public boolean retarget(TickTask route) { return access.retarget(route, target, distance); }
            @Override public Progress advance(TickTask route) { return access.advance(route); }
            @Override public void stopInput() { access.stopInput(); }
        }, budget);
    }

    @Override public Progress poll(CompanionPlayer companion, int elapsedTicks) {
        if (terminal != null) return new Progress.Done(terminal);
        elapsed = elapsedTicks;
        var failure = access.guard();
        if (failure != null) return end(failure);
        target = access.target();
        if (target != null && !targetId.equals(target.uuid()))
            return end(new ServerTool.Result(false, "TARGET_LOST:原跟随实体身份已变化，未选择替代目标。", null));
        if (target != null && (failure = access.guard(target)) != null) return end(failure);
        if (target == null) {
            stopFight("TARGET_LOST:主人暂不在场，停止护卫攻击。");
            inBand = false;
            inBandKnown = false;
            if (!owner) return end(new ServerTool.Result(false,
                    "TARGET_LOST:指定跟随实体已死亡、卸载或离开原维度，未自动换目标。", null));
            phase = "waiting_owner";
            absentTicks++;
            if (elapsed >= duration) return end(new ServerTool.Result(false,
                    "TARGET_LOST:原期限已到，主人仍不在原维度；未延长期限或自动重开。", null));
            return running();
        }
        inBand = access.arrived(target, distance);
        inBandKnown = true;
        if (elapsed >= duration) return end(new ServerTool.Result(true,
                "跟随的原期限已到，持续行为已结束；当前距离另见回执，不表示外层目标完成。", null));
        if (protectOwner) {
            threats = access.threats(target);
            threatScanElapsed = elapsed;
            threatScanOwner = target.position();
            if (!threats.complete()) return end(new ServerTool.Result(false,
                    "UNKNOWN:主人附近威胁观察不完整，已停止护卫，不能宣称附近安全。", null));
            if (threats.threats().stream().anyMatch(t -> t.explosive() && t.dangerous(target.bounds(), .75)))
                return end(new ServerTool.Result(false,
                        "UNSAFE_THREAT:主人附近有正在引爆的威胁，当前护卫只有普通近战，不能安全贴近；先处理爆炸风险。", null));
            if (enemy != null) {
                var fresh = threats.threats().stream().filter(t -> t.identity() == enemy.identity()
                        && t.id() == enemy.id() && t.uuid().equals(enemy.uuid())).findFirst().orElse(null);
                if (fresh == null || !fresh.attackAllowed() || fresh.explosive()
                        || access.position().distanceToSqr(target.position()) > ThreatFacts.RADIUS * ThreatFacts.RADIUS) {
                    stopFight("INTERRUPTED:原敌人已离开护卫范围、失效或主人走远，重新跟随主人。");
                } else enemy = fresh;
            }
            if (enemy == null && access.position().distanceToSqr(target.position())
                    <= ThreatFacts.RADIUS * ThreatFacts.RADIUS) {
                enemy = threats.threats().stream().filter(t -> t.attackAllowed() && !t.explosive())
                        .min(java.util.Comparator.comparingInt((ThreatFacts.Threat t) ->
                                        t.targeting() || t.recentAttacker() ? 0 : 1)
                                .thenComparingDouble(t -> t.position().distanceToSqr(target.position()))
                                .thenComparingInt(ThreatFacts.Threat::id)).orElse(null);
            }
            if (enemy != null) {
                phase = "protecting";
                return running();
            }
        }
        if ("following".equals(phase)) {
            if (inBand) phase = "sleeping";
        } else if (!inBand && !access.arrived(target, distance + 2)) {
            phase = "following";
            wakes++;
        } else phase = "sleeping";
        if ("sleeping".equals(phase)) sleepingTicks++;
        return running();
    }

    @Override public boolean wantsBody() {
        return terminal == null && ("following".equals(phase) || "protecting".equals(phase));
    }
    public boolean fighting() { return terminal == null && "protecting".equals(phase); }
    @Override public Activity activity() {
        if (fighting()) return Activity.ATTACK;
        if ("following".equals(phase)) return Activity.MOVE;
        return protectOwner ? Activity.GUARD : Activity.FOLLOW;
    }

    @Override public Progress tick(CompanionPlayer companion) {
        if (terminal != null) return new Progress.Done(terminal);
        if (enemy != null) {
            movement.stop();
            if (fight == null) {
                fight = access.attack(enemy, budget);
                engagements++;
            }
            var progress = access.advance(fight);
            if (progress instanceof Progress.Done done) {
                var result = done.result();
                recordCombat(result);
                fight.onFinish();
                fight = null;
                enemy = null;
                if (!result.ok()) return end(result);
                phase = "following";
            }
            return running();
        }
        var failure = movement.tick();
        if (failure != null) return end(failure);
        if (access.arrived(target, distance)) {
            inBand = true;
            phase = "sleeping";
        }
        return running();
    }

    private Progress end(ServerTool.Result result) {
        stopFight("INTERRUPTED:原跟随护卫行为结束，停止当前战斗。");
        terminal = receipt(result);
        return new Progress.Done(terminal);
    }

    private ServerTool.Result receipt(ServerTool.Result result) {
        // Cancellation may happen between ticks, after either body or target has actually moved.
        target = access.target();
        inBandKnown = target != null && targetId.equals(target.uuid())
                && access.guard() == null && access.guard(target) == null;
        inBand = inBandKnown && access.arrived(target, distance);
        var data = result.data() == null ? new JsonObject() : result.data().deepCopy();
        data.add("follow", observation());
        data.addProperty("behavior_ended", true);
        data.addProperty("goal_satisfied", false);
        data.addProperty("automatic_resume", false);
        return new ServerTool.Result(result.ok(), result.feedback(), data);
    }

    @Override public JsonObject observation() {
        var data = new JsonObject();
        data.addProperty("phase", phase);
        data.addProperty("target_uuid", targetId.toString());
        data.addProperty("owner_target", owner);
        data.addProperty("protect_owner", protectOwner);
        if (protectOwner) {
            var protection = new JsonObject();
            protection.addProperty("radius", ThreatFacts.RADIUS);
            protection.addProperty("scan_complete", threats.complete());
            protection.addProperty("observed_elapsed_ticks", threatScanElapsed);
            protection.addProperty("observed_threats", threats.threats().size());
            protection.addProperty("engagements", engagements);
            protection.addProperty("strikes", strikes);
            protection.addProperty("observed_deaths", observedDeaths);
            protection.addProperty("observed_health_loss", observedHealthLoss);
            protection.addProperty("observed_absorption_loss", observedAbsorptionLoss);
            protection.addProperty("observation_known", combatObservationKnown);
            protection.addProperty("disengagements", disengagements);
            protection.addProperty("local_clear", threats.complete() && threats.threats().isEmpty()
                    && target != null && inBandKnown && elapsed == threatScanElapsed
                    && target.position().equals(threatScanOwner));
            if (target != null) protection.add("threats", threats.reportGuard(target.bounds()));
            if (enemy != null) {
                protection.addProperty("entity_id", enemy.id());
                protection.addProperty("target_uuid", enemy.uuid().toString());
            }
            if (fight != null) protection.add("combat", fight.observation());
            if (lastCombat != null) protection.add("last_combat", lastCombat.deepCopy());
            var history = new com.google.gson.JsonArray();
            combatHistory.forEach(entry -> history.add(entry.deepCopy()));
            protection.add("combat_history", history);
            protection.addProperty("combat_history_omitted", combatHistoryOmitted);
            data.add("protection", protection);
        }
        data.addProperty("keep_distance", distance);
        data.addProperty("wake_distance", distance + 2);
        data.addProperty("in_band", inBand);
        data.addProperty("in_band_known", inBandKnown);
        data.addProperty("target_present", target != null && targetId.equals(target.uuid()));
        data.addProperty("elapsed_ticks", elapsed);
        data.addProperty("duration_ticks", duration);
        data.addProperty("wake_count", wakes);
        data.addProperty("sleeping_ticks", sleepingTicks);
        data.addProperty("owner_absent_ticks", absentTicks);
        var pos = access.position();
        var point = new JsonObject();
        point.addProperty("x", pos.x); point.addProperty("y", pos.y); point.addProperty("z", pos.z);
        data.add("position", point);
        var scope = access.scope();
        var anchor = new JsonObject();
        if (scope.anchor() != null) {
            anchor.addProperty("x", scope.anchor().getX());
            anchor.addProperty("y", scope.anchor().getY());
            anchor.addProperty("z", scope.anchor().getZ());
        }
        data.add("anchor", anchor);
        data.addProperty("radius", scope.radius());
        data.add("navigation", movement.observation());
        return data;
    }

    private void recordCombat(ServerTool.Result result) {
        var data = result.data() == null ? new JsonObject() : result.data();
        if (data.has("strikes")) strikes += data.get("strikes").getAsInt();
        if (data.has("target_dead") && data.get("target_dead").getAsBoolean()) observedDeaths++;
        if (data.has("observed_health_loss")) observedHealthLoss += data.get("observed_health_loss").getAsFloat();
        if (data.has("observed_absorption_loss")) observedAbsorptionLoss += data.get("observed_absorption_loss").getAsFloat();
        if (data.has("observation_known")) combatObservationKnown &= data.get("observation_known").getAsBoolean();
        lastCombat = new JsonObject();
        lastCombat.addProperty("ok", result.ok());
        lastCombat.addProperty("reason", com.neko.mcbot.common.WireSize.truncateToBytes(result.feedback(), 1024));
        for (var key : java.util.List.of("entity_id", "target_uuid", "target_dead", "strikes",
                "observed_health_loss", "observed_absorption_loss", "observation_known", "equipment"))
            if (data.has(key)) lastCombat.add(key, data.get(key).deepCopy());
        if (enemy != null) {
            if (!lastCombat.has("entity_id")) lastCombat.addProperty("entity_id", enemy.id());
            if (!lastCombat.has("target_uuid")) lastCombat.addProperty("target_uuid", enemy.uuid().toString());
        }
        if (combatHistory.size() == 8) {
            combatHistory.removeFirst();
            combatHistoryOmitted++;
        }
        combatHistory.addLast(lastCombat.deepCopy());
    }
    private void stopFight(String reason) {
        if (fight != null) {
            try { recordCombat(fight.interruptedResult(new ServerTool.Result(false, reason, null))); }
            finally {
                try { fight.onAbort(); }
                finally { fight = null; }
            }
            disengagements++;
        }
        enemy = null;
    }
    @Override public ServerTool.Result interruptedResult(ServerTool.Result reason) {
        stopFight(reason.feedback());
        return receipt(reason);
    }
    @Override public void onSuspend() {
        movement.stop();
        if (fight != null) fight.onSuspend();
    }
    @Override public ServerTool.Result onResume(CompanionPlayer companion) {
        var failure = access.guard();
        if (failure == null && target != null) failure = access.guard(target);
        return failure != null || fight == null ? failure : fight.onResume(companion);
    }
    @Override public void onAbort() {
        try { stopFight("CANCELLED:原跟随护卫已停止。"); }
        finally { movement.stop(); }
    }
    @Override public PathSpec movementScope() { return access.scope(); }

    /** Snapshot goal; real ground/clearance and entity separation are checked, no guessed ground projection. */
    public static NavigationGoal goal(StandingGeometry geometry, Target target, int distance) {
        return new NavigationGoal() {
            @Override public double remaining(int x, int y, int z) {
                return Math.max(0, new Vec3(x + .5, y, z + .5).distanceTo(target.position()) - distance - 1);
            }
            @Override public boolean reached(int x, int y, int z) {
                var foot = geometry.standing(x, y, z, TerrainEdits.EMPTY);
                if (foot == null) return false;
                return new Vec3(x + .5, foot.height(), z + .5).distanceToSqr(target.position()) <= distance * distance
                        && !geometry.body(x, foot.height(), z).intersects(target.bounds());
            }
        };
    }
}
