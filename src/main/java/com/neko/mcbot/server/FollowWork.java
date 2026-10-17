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
        TickTask.Progress advance(TickTask route);
        void stopInput();
        PathSpec scope();
    }

    private final Access access;
    private final UUID targetId;
    private final boolean owner;
    private final int distance, duration;
    private final EntityApproach movement;
    private Target target;
    private String phase = "sleeping";
    private int elapsed, wakes, sleepingTicks, absentTicks;
    private boolean inBand;
    private boolean inBandKnown;
    private ServerTool.Result terminal;

    public FollowWork(Access access, UUID targetId, boolean owner, int distance, int duration,
                      NavigationBudget budget) {
        if (targetId == null || distance < 2 || distance > 6 || duration < 1 || duration > 1200)
            throw new IllegalArgumentException("follow bounds");
        this.access = java.util.Objects.requireNonNull(access);
        this.targetId = targetId;
        this.owner = owner;
        this.distance = distance;
        this.duration = duration;
        movement = new EntityApproach(new EntityApproach.Access() {
            @Override public Vec3 targetPosition() { return target.position(); }
            @Override public boolean canStrike() { return access.arrived(target, distance); }
            @Override public TickTask route(NavigationBudget account) { return access.route(target, distance, account); }
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
        if ("following".equals(phase)) {
            if (inBand) phase = "sleeping";
        } else if (!inBand && !access.arrived(target, distance + 2)) {
            phase = "following";
            wakes++;
        } else phase = "sleeping";
        if ("sleeping".equals(phase)) sleepingTicks++;
        return running();
    }

    @Override public boolean wantsBody() { return terminal == null && "following".equals(phase); }

    @Override public Progress tick(CompanionPlayer companion) {
        if (terminal != null) return new Progress.Done(terminal);
        var failure = movement.tick();
        if (failure != null) return end(failure);
        if (access.arrived(target, distance)) {
            inBand = true;
            phase = "sleeping";
        }
        return running();
    }

    private Progress end(ServerTool.Result result) {
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

    @Override public ServerTool.Result interruptedResult(ServerTool.Result reason) { return receipt(reason); }
    @Override public void onSuspend() { movement.stop(); }
    @Override public ServerTool.Result onResume(CompanionPlayer companion) {
        var failure = access.guard();
        return failure != null || target == null ? failure : access.guard(target);
    }
    @Override public void onAbort() { movement.stop(); }
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
