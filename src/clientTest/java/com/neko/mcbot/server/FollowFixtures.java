package com.neko.mcbot.server;

import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.path.NavigationBudget;
import com.neko.mcbot.path.PathSpec;
import com.neko.mcbot.task.TickTask;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public final class FollowFixtures {
    public static final class Access implements FollowWork.Access {
        public final UUID id = UUID.randomUUID();
        public Vec3 body = new Vec3(.5, 0, .5), position = new Vec3(1.5, 0, .5);
        public boolean present = true, grounded = true, landOnAdvance, throwsAdvance;
        public int plans, advances, aborts, stops;
        public ServerTool.Result guardFailure, targetFailure;
        public TickTask.Progress progress = TickTask.running();
        public NavigationBudget original;
        public UUID replacement;
        public java.util.List<ThreatFacts.Threat> threats = java.util.List.of();
        public boolean threatsComplete = true;
        public int scans, fights;
        public java.util.function.Function<ThreatFacts.Threat, TickTask> combat;
        public boolean usefulRoute, keepRoute;
        public int retargets;

        @Override public ServerTool.Result guard() { return guardFailure; }
        @Override public FollowWork.Target target() {
            return present ? new FollowWork.Target(replacement == null ? id : replacement, position,
                    new AABB(position.x - .3, position.y, position.z - .3,
                            position.x + .3, position.y + 1.8, position.z + .3)) : null;
        }
        @Override public ServerTool.Result guard(FollowWork.Target target) { return targetFailure; }
        @Override public Vec3 position() { return body; }
        @Override public boolean arrived(FollowWork.Target target, int distance) {
            return grounded && body.distanceToSqr(target.position()) <= distance * distance
                    && !new AABB(body.x - .3, body.y, body.z - .3, body.x + .3, body.y + 1.8, body.z + .3)
                    .intersects(target.bounds());
        }
        @Override public TickTask route(FollowWork.Target target, int distance, NavigationBudget budget) {
            if (original != null && original != budget) throw new AssertionError("original account changed");
            original = budget;
            plans++;
            return new TickTask() {
                @Override public Progress tick(CompanionPlayer companion) { return progress; }
                @Override public void onAbort() { aborts++; }
            };
        }
        @Override public TickTask.Progress advance(TickTask route) {
            advances++;
            if (throwsAdvance) throw new IllegalStateException("controlled native motion boundary");
            if (landOnAdvance) body = position.add(-2, 0, 0);
            return route.tickSafe(null);
        }
        @Override public boolean routeUseful(TickTask route, FollowWork.Target target, int distance) { return usefulRoute; }
        @Override public boolean retarget(TickTask route, FollowWork.Target target, int distance) {
            retargets++;
            return keepRoute;
        }
        @Override public void stopInput() { stops++; }
        @Override public PathSpec scope() { return new PathSpec(false, false, false, 0, BlockPos.ZERO, 32); }
        @Override public ThreatFacts.Snapshot threats(FollowWork.Target target) {
            scans++;
            return new ThreatFacts.Snapshot(threats, threatsComplete);
        }
        @Override public TickTask attack(ThreatFacts.Threat enemy, NavigationBudget budget) {
            if (original != null && original != budget) throw new AssertionError("combat replaced navigation account");
            original = budget;
            fights++;
            return combat.apply(enemy);
        }
        public FollowWork work(boolean owner, int duration) {
            return new FollowWork(this, id, owner, 3, duration, new NavigationBudget());
        }
        public FollowWork protection(int duration) {
            return new FollowWork(this, id, true, 3, duration, new NavigationBudget(), true);
        }
    }
    private FollowFixtures() { }
}
