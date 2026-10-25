package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.neko.mcbot.body.BodyControl;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.path.NavigationBudget;
import com.neko.mcbot.path.PathSpec;
import com.neko.mcbot.path.PathTask;
import com.neko.mcbot.path.TerrainEdits;
import com.neko.mcbot.server.ActionPermissions;
import com.neko.mcbot.server.FollowWork;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.server.StandingGeometry;
import com.neko.mcbot.server.ThreatFacts;
import com.neko.mcbot.task.CompanionScheduler;
import com.neko.mcbot.task.TickTask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Bounded dry-land following; native input and search stay in the shared path implementation. */
public final class FollowTool implements ServerTool {
    public record Input(UUID target, int distance, int seconds, boolean protectOwner) { }

    public static Input arguments(JsonObject args) {
        try {
            if (args == null || !java.util.Set.of("target_uuid", "distance", "seconds", "protect_owner").containsAll(args.keySet()))
                return null;
            UUID target = null;
            if (args.has("target_uuid")) {
                var value = args.get("target_uuid");
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
                var raw = value.getAsString();
                if (raw.length() != 36 || !(target = UUID.fromString(raw)).toString().equals(raw)) return null;
            }
            int distance = integer(args, "distance", 3), seconds = integer(args, "seconds", 30);
            if (args.has("protect_owner") && (!args.get("protect_owner").isJsonPrimitive()
                    || !args.getAsJsonPrimitive("protect_owner").isBoolean())) return null;
            boolean protect = args.has("protect_owner") && args.get("protect_owner").getAsBoolean();
            return distance >= 2 && distance <= 6 && seconds >= 1 && seconds <= 60
                    ? new Input(target, distance, seconds, protect) : null;
        } catch (RuntimeException invalid) { return null; }
    }

    private static int integer(JsonObject args, String name, int fallback) {
        if (!args.has(name)) return fallback;
        var value = args.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
        return value.getAsBigDecimal().intValueExact();
    }

    @Override public String name() { return "follow"; }
    @Override public Acceptance acceptanceMode() { return Acceptance.ACCEPT; }
    @Override public int capTicks(JsonObject args) {
        var input = arguments(args);
        return input == null ? 600 : input.seconds() * 20;
    }
    @Override public String acceptSubject(JsonObject args) { return "有界持续跟随，到位休眠、拉开再唤醒"; }

    @Override public CompletableFuture<Result> runAsync(CompanionPlayer body, JsonObject args, CompanionScheduler scheduler) {
        return runAuthorized(body, args, scheduler, null, null);
    }

    @Override public CompletableFuture<Result> runAuthorized(CompanionPlayer body, JsonObject args,
            CompanionScheduler scheduler, ActionPermissions permissions, ActionPermissions.Context context) {
        var input = arguments(args);
        if (input == null) return completed("DENIED:follow仅接受可选标准小写target_uuid、整数distance=2-6、seconds=1-60及布尔protect_owner；默认主人/3格/30秒/不主动护卫。");
        UUID id = input.target() == null ? body.ownerUuid() : input.target();
        if (id == null || id.equals(body.getUUID())) return completed("DENIED:跟随目标须为主人或其他扫描所得实体，不能跟随自身。");
        if (input.protectOwner() && !id.equals(body.ownerUuid()))
            return completed("DENIED:protect_owner只能用于跟随本身体的主人，不能将其他实体当作主人授权攻击。");
        body.bindSpatialTask(context);
        var outer = body.buildSite().active() ? body.buildSite().scope() : PathSpec.DEFAULT;
        var scope = scope(StandingGeometry.feetCell(body), outer);
        if (scope == null) return completed("SCOPE_LIMIT:原工地边界没有可用的跟随范围，未扩大原范围。");
        var access = new PlayerAccess(body, id, input.target() == null, input.protectOwner(),
                scope, outer, permissions, context, scheduler);
        var failure = access.guard();
        if (failure != null) return CompletableFuture.completedFuture(failure);
        var target = access.target();
        if (target == null && input.target() != null) return completed("TARGET_LOST:指定实体不在当前加载维度，先scan_area核对。");
        if (target != null && (failure = access.guard(target)) != null)
            return CompletableFuture.completedFuture(failure);
        NavigationBudget budget = body.buildSite().active() ? body.buildSite().navigation() : new NavigationBudget();
        var work = new FollowWork(access, id, input.target() == null, input.distance(), capTicks(args), budget,
                input.protectOwner());
        var future = new CompletableFuture<Result>();
        if (!scheduler.submit(body, work, future, capTicks(args))) return completed("BUSY:原身体任务槽仍被占用，先等终态或取消。");
        return future;
    }

    /** An inscribed sphere keeps reflexes as well as follow legs inside the original work area. */
    static PathSpec scope(BlockPos start, PathSpec outer) {
        int radius = 32;
        if (outer.anchor() != null)
            radius = Math.min(radius, (int) Math.floor(outer.radius() - Math.sqrt(start.distSqr(outer.anchor()))));
        return radius < 1 ? null : new PathSpec(false, false, outer.underground(), 0, start.immutable(), radius);
    }

    private static final class PlayerAccess implements FollowWork.Access {
        private final CompanionPlayer body;
        private final ServerLevel level;
        private final UUID id;
        private final boolean owner;
        private final boolean protect;
        private final PathSpec scope, outer;
        private final ActionPermissions permissions;
        private final ActionPermissions.Context context;
        private final CompanionScheduler scheduler;
        private Entity selected;

        PlayerAccess(CompanionPlayer body, UUID id, boolean owner, boolean protect, PathSpec scope, PathSpec outer,
                     ActionPermissions permissions, ActionPermissions.Context context, CompanionScheduler scheduler) {
            this.body = body; this.level = body.level(); this.id = id; this.owner = owner; this.protect = protect;
            this.scope = scope; this.outer = outer; this.permissions = permissions;
            this.context = context; this.scheduler = scheduler;
        }
        @Override public Result guard() {
            if (permissions == null || context == null || !context.equals(permissions.current(context.owner(),
                    body.getUUID(), context.dimension(), context.taskId(), body))
                    || !ActionPermissions.allowed(context, "follow", new JsonObject())
                    || !ActionPermissions.allowed(context, "move_to", new JsonObject())
                    || protect && !ActionPermissions.allowed(context, "attack", new JsonObject()))
                return failed("DENIED:原任务、身体或跟随/移动许可已失效，未自动续接。");
            if (body.level() != level || !body.isAlive() || body.isRemoved())
                return failed("TARGET_LOST:原身体已死亡、离场或换维度。");
            if (!body.gameMode.isSurvival() || body.isSleeping() || body.isPassenger())
                return failed("DENIED:当前身体状态不支持普通步行跟随。");
            if (!inside(body.position())) return failed("SCOPE_LIMIT:身体离开原跟随范围，未移动锚点。");
            if (!scheduler.reserve(body.getUUID(), List.of())) return failed("BUSY:原身体资源租约已失效。");
            return null;
        }
        private boolean inside(Vec3 position) {
            return Double.isFinite(position.x) && Double.isFinite(position.y) && Double.isFinite(position.z)
                    && scope.contains((int) Math.floor(position.x), (int) Math.floor(position.y), (int) Math.floor(position.z))
                    && outer.contains((int) Math.floor(position.x), (int) Math.floor(position.y), (int) Math.floor(position.z));
        }
        @Override public FollowWork.Target target() {
            selected = owner ? level.getServer().getPlayerList().getPlayer(id) : level.getEntity(id);
            if (selected == null || selected.level() != level || selected.isRemoved() || !selected.isAlive())
                return null;
            return new FollowWork.Target(selected.getUUID(), selected.position(), selected.getBoundingBox());
        }
        @Override public Result guard(FollowWork.Target target) {
            if (!id.equals(target.uuid()) || selected == null || selected.level() != level || selected.isRemoved()
                    || !selected.isAlive() || level.getEntity(selected.getId()) != selected)
                return failed("TARGET_LOST:原目标已消失或被替换，未自动选择其他实体。");
            if (!inside(target.position())) return failed("SCOPE_LIMIT:原跟随目标超出固定范围，未重开锚点或探索账。");
            BlockPos pos = BlockPos.containing(target.position());
            if (!level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos)
                    || level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null)
                return failed("TARGET_LOST:原目标邻域未加载或越界，不强制加载。");
            return null;
        }
        @Override public Vec3 position() { return body.position(); }
        @Override public boolean arrived(FollowWork.Target target, int distance) {
            if (target == null || !body.onGround()
                    || body.position().distanceToSqr(target.position()) > distance * distance
                    || body.getBoundingBox().intersects(target.bounds())) return false;
            var geometry = StandingGeometry.forPlayer(body);
            var at = StandingGeometry.feetCell(body);
            var foot = geometry.standing(at.getX(), at.getY(), at.getZ(), TerrainEdits.EMPTY);
            return foot != null && Math.abs(foot.height() - body.getY()) < .05;
        }
        @Override public TickTask route(FollowWork.Target target, int distance, NavigationBudget budget) {
            var geometry = StandingGeometry.forPlayer(body);
            return PathTask.approach(level, BlockPos.containing(target.position()), permissions, context, scheduler,
                    scope, FollowWork.goal(geometry, target, distance), budget).limitTo(outer)
                    .constrainSteps((from, to) -> {
                        var foot = geometry.standing(to.getX(), to.getY(), to.getZ(), TerrainEdits.EMPTY);
                        return foot != null && level.noCollision(body, geometry.body(to.getX(), foot.height(), to.getZ()));
                    });
        }
        @Override public boolean routeUseful(TickTask route, FollowWork.Target target, int distance) {
            return route instanceof PathTask path && path.usefulFor(FollowWork.goal(StandingGeometry.forPlayer(body), target, distance));
        }
        @Override public boolean retarget(TickTask route, FollowWork.Target target, int distance) {
            if (!(route instanceof PathTask path)) return false;
            path.retarget(BlockPos.containing(target.position()), FollowWork.goal(StandingGeometry.forPlayer(body), target, distance));
            return true;
        }
        @Override public TickTask.Progress advance(TickTask route) { return route.tickSafe(body); }
        @Override public void stopInput() {
            if (body.bodyControl().owner() != BodyControl.Owner.REFLEX) body.releaseMovement();
        }
        @Override public PathSpec scope() { return scope; }
        @Override public ThreatFacts.Snapshot threats(FollowWork.Target ownerTarget) {
            return selected instanceof LivingEntity living && id.equals(body.ownerUuid())
                    ? ThreatFacts.guardScan(body, living) : new ThreatFacts.Snapshot(List.of(), false);
        }
        @Override public TickTask attack(ThreatFacts.Threat enemy, NavigationBudget budget) {
            if (!(enemy.identity() instanceof LivingEntity living)) throw new IllegalArgumentException("enemy");
            return AttackTool.escort(body, living, scheduler, permissions, context, scope, budget, () -> {
                var failure = guard();
                if (failure != null) return failure;
                var ownerTarget = target();
                if (ownerTarget == null) return failed("TARGET_LOST:护卫主人已离场。");
                if ((failure = guard(ownerTarget)) != null) return failure;
                var described = AttackTool.describe(body, living);
                if (!described.hostile() || described.named() || described.protectedTarget())
                    return failed("DENIED:护卫不能攻击中立、命名或受保护目标。");
                if (!inside(living.position()) || living.position().distanceToSqr(ownerTarget.position())
                        > ThreatFacts.RADIUS * ThreatFacts.RADIUS
                        || body.position().distanceToSqr(ownerTarget.position()) > ThreatFacts.RADIUS * ThreatFacts.RADIUS)
                    return failed("SCOPE_LIMIT:原敌人或身体超出主人护卫范围。");
                return null;
            });
        }
    }
    private static Result failed(String message) { return new Result(false, message, null); }
    private static CompletableFuture<Result> completed(String message) {
        return CompletableFuture.completedFuture(failed(message));
    }
}
