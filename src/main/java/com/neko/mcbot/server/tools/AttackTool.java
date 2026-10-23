package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.ActionPermissions;
import com.neko.mcbot.server.EntityAttack;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.task.CompanionScheduler;
import com.neko.mcbot.task.ResourceLocks;
import com.neko.mcbot.task.TickTask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Shared native melee; optional bounded approach uses the same path and strike implementations. */
public final class AttackTool implements ServerTool {
    public static final int CAP_TICKS = 400;
    public static final double LOCAL_RADIUS = 6.5;

    public record Input(Integer entityId, UUID uuid, int maxHits, String authorizationId, boolean approach) {
        public boolean nearby() { return entityId == null; }
    }

    private enum ArgumentError {
        ARGUMENTS_REQUIRED("args", "JSON 对象", "传入 attack 参数对象。"),
        UNKNOWN_FIELD("args", "仅 entity_id、target_uuid、max_hits、authorization_id、approach",
                "移除其他字段，不用模型字段代替任务权限。"),
        ENTITY_ID_REQUIRED("entity_id", "扫描所得正整数编号，或字符串 hostile_nearby",
                "先 scan_area 取得目标身份；不要猜编号。"),
        ENTITY_ID_TYPE("entity_id", "JSON 数字形式的正整数，或字符串 hostile_nearby",
                "显式编号不能加引号；hostile_nearby 是唯一合法字符串选择器。"),
        ENTITY_ID_VALUE("entity_id", "1-2147483647 的整数",
                "使用 scan_area 返回的正整数编号，不用小数或溢出数值。"),
        TARGET_UUID_REQUIRED("target_uuid", "显式编号对应的扫描所得 UUID",
                "重新 scan_area，携带同一目标的编号和 UUID。"),
        TARGET_UUID_TYPE("target_uuid", "UUID 字符串",
                "使用 scan_area 返回的 target_uuid 字符串，不转换类型。"),
        TARGET_UUID_FORMAT("target_uuid", "36 字符的标准小写 UUID",
                "原样使用 scan_area 返回的 target_uuid，不缩写、不改大小写或加空格。"),
        NEARBY_TARGET_UUID("target_uuid", "hostile_nearby 模式不得携带此字段",
                "移除此字段；如要指定目标，改用该目标的数字编号和 UUID。"),
        MAX_HITS_TYPE("max_hits", "JSON 数字形式的整数",
                "用数字填写挥击上限，不加引号；省略时默认 1。"),
        MAX_HITS_VALUE("max_hits", "1-10 的整数",
                "将挥击上限设为 1-10，不用小数或溢出数值。"),
        APPROACH_TYPE("approach", "JSON 布尔值",
                "true允许原范围内有限只行走接近，false或省略保持原地。"),
        AUTHORIZATION_ID_TYPE("authorization_id", "服务端授权编号字符串",
                "原样携带服务端清单经 ask_owner 确认的编号，不转换类型或伪造。"),
        AUTHORIZATION_ID_VALUE("authorization_id", "非空且不超过 64 字符的字符串",
                "先取得本任务具体目标清单并经 ask_owner 确认；不要提交空白或截断编号。"),
        NEARBY_AUTHORIZATION("authorization_id", "hostile_nearby 模式不得携带授权编号",
                "授权只绑定具体目标；改用清单中的数字编号、UUID、max_hits 和授权编号。");

        private final String field;
        private final String expected;
        private final String hint;

        ArgumentError(String field, String expected, String hint) {
            this.field = field;
            this.expected = expected;
            this.hint = hint;
        }

        Result result() {
            var error = new JsonObject();
            error.addProperty("code", name());
            error.addProperty("field", field);
            error.addProperty("expected", expected);
            var data = new JsonObject();
            data.add("argument_error", error);
            return new Result(false, "DENIED:attack 参数无效 [" + name() + "]："
                    + field + " 需要 " + expected + "。" + hint, data);
        }
    }

    private record ParsedArguments(Input input, ArgumentError error) {
        static ParsedArguments invalid(ArgumentError error) { return new ParsedArguments(null, error); }
    }

    @Override public String name() { return "attack"; }
    @Override public Acceptance acceptanceMode() { return Acceptance.ACCEPT; }
    @Override public int capTicks(JsonObject args) { return CAP_TICKS; }
    @Override public String acceptSubject(JsonObject args) {
        Input input = arguments(args);
        return input == null ? "有界近战" : "有界近战，最多挥击 " + input.maxHits() + " 次";
    }

    public static Input arguments(JsonObject args) {
        return parseArguments(args).input();
    }

    private static ParsedArguments parseArguments(JsonObject args) {
        if (args == null) return ParsedArguments.invalid(ArgumentError.ARGUMENTS_REQUIRED);
        // Only fixed schema fields enter diagnostics; an unknown key may itself contain private text.
        for (String key : args.keySet()) {
            if (!List.of("entity_id", "target_uuid", "max_hits", "authorization_id", "approach").contains(key)) {
                return ParsedArguments.invalid(ArgumentError.UNKNOWN_FIELD);
            }
        }
        var entity = args.get("entity_id");
        if (entity == null) return ParsedArguments.invalid(ArgumentError.ENTITY_ID_REQUIRED);
        if (!entity.isJsonPrimitive()) return ParsedArguments.invalid(ArgumentError.ENTITY_ID_TYPE);
        Integer id = null;
        UUID uuid = null;
        if (entity.getAsJsonPrimitive().isNumber()) {
            try {
                id = entity.getAsBigDecimal().intValueExact();
            } catch (RuntimeException invalid) {
                return ParsedArguments.invalid(ArgumentError.ENTITY_ID_VALUE);
            }
            if (id <= 0) return ParsedArguments.invalid(ArgumentError.ENTITY_ID_VALUE);
            var target = args.get("target_uuid");
            if (target == null) return ParsedArguments.invalid(ArgumentError.TARGET_UUID_REQUIRED);
            if (!target.isJsonPrimitive() || !target.getAsJsonPrimitive().isString()) {
                return ParsedArguments.invalid(ArgumentError.TARGET_UUID_TYPE);
            }
            String raw = target.getAsString();
            if (raw.length() != 36) return ParsedArguments.invalid(ArgumentError.TARGET_UUID_FORMAT);
            try {
                uuid = UUID.fromString(raw);
            } catch (IllegalArgumentException invalid) {
                return ParsedArguments.invalid(ArgumentError.TARGET_UUID_FORMAT);
            }
            if (!uuid.toString().equals(raw)) return ParsedArguments.invalid(ArgumentError.TARGET_UUID_FORMAT);
        } else {
            if (!entity.getAsJsonPrimitive().isString() || !"hostile_nearby".equals(entity.getAsString())) {
                return ParsedArguments.invalid(ArgumentError.ENTITY_ID_TYPE);
            }
            if (args.has("target_uuid")) return ParsedArguments.invalid(ArgumentError.NEARBY_TARGET_UUID);
        }
        int hits = 1;
        if (args.has("max_hits")) {
            var value = args.get("max_hits");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                return ParsedArguments.invalid(ArgumentError.MAX_HITS_TYPE);
            }
            try {
                hits = value.getAsBigDecimal().intValueExact();
            } catch (RuntimeException invalid) {
                return ParsedArguments.invalid(ArgumentError.MAX_HITS_VALUE);
            }
        }
        if (hits < 1 || hits > 10) return ParsedArguments.invalid(ArgumentError.MAX_HITS_VALUE);
        String authorization = null;
        if (args.has("authorization_id")) {
            var value = args.get("authorization_id");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                return ParsedArguments.invalid(ArgumentError.AUTHORIZATION_ID_TYPE);
            }
            authorization = value.getAsString();
            if (authorization.isBlank() || authorization.length() > 64) {
                return ParsedArguments.invalid(ArgumentError.AUTHORIZATION_ID_VALUE);
            }
        }
        // An approval is for one concrete identity, never another nearby selection.
        if (id == null && authorization != null) return ParsedArguments.invalid(ArgumentError.NEARBY_AUTHORIZATION);
        boolean approach = false;
        if (args.has("approach")) {
            var value = args.get("approach");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
                return ParsedArguments.invalid(ArgumentError.APPROACH_TYPE);
            approach = value.getAsBoolean();
        }
        return new ParsedArguments(new Input(id, uuid, hits, authorization, approach), null);
    }

    @Override public CompletableFuture<Result> runAsync(CompanionPlayer player, JsonObject args,
                                                        CompanionScheduler scheduler) {
        return runAuthorized(player, args, scheduler, null, null);
    }

    @Override public CompletableFuture<Result> runAuthorized(CompanionPlayer player, JsonObject args,
            CompanionScheduler scheduler, ActionPermissions permissions, ActionPermissions.Context context) {
        var parsed = parseArguments(args);
        if (parsed.error() != null) return CompletableFuture.completedFuture(parsed.error().result());
        Input input = parsed.input();
        if (scheduler.busy(player.getUUID())) return completed("BUSY:身体正忙，先等终态或取消。");
        LivingEntity entity;
        if (input.nearby()) {
            // The selector runs once. Every later tick keeps this exact object/UUID.
            entity = player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(LOCAL_RADIUS),
                            mob -> mob instanceof Enemy && mob.isAlive() && !mob.hasCustomName() && !protectedTarget(player, mob))
                    .stream().sorted(Comparator.comparingDouble((Mob mob) -> player.distanceToSqr(mob))
                            .thenComparingInt(Mob::getId)).limit(64)
                    .filter(mob -> {
                        var failure = guard(player, player.level(), mob);
                        return failure == null || input.approach() && EntityAttack.geometricFailure(failure);
                    }).findFirst()
                    .orElse(null);
        } else {
            var found = player.level().getEntity(input.entityId());
            entity = found instanceof LivingEntity living && input.uuid().equals(living.getUUID()) ? living : null;
        }
        if (entity == null) return completed("TARGET_LOST:没有可用的指定目标或近身敌对目标；先扫描，不追击或自动改目标。");
        var approved = permissions == null || input.authorizationId() == null
                ? null : permissions.take(context, input.authorizationId(), entity.getId());
        var anchor = com.neko.mcbot.server.StandingGeometry.feetCell(player);
        if (input.approach() && approved != null) {
            var change = approved.changes().get("attack:" + entity.getId());
            if (change != null && change.expectedState() instanceof EntityAttack.Approval permit && permit.approach())
                anchor = BlockPos.of(permit.anchor());
        }
        var execution = new ActionPermissions.Execution(approved);
        var attack = new EntityAttack(new PlayerAccess(player, player.level(), entity, scheduler,
                permissions, context, execution, input.approach(), anchor), input.maxHits());
        Result failure = attack.preflight();
        if (failure != null) return CompletableFuture.completedFuture(failure);
        var future = new CompletableFuture<Result>();
        if (!scheduler.submit(player, new Task(attack), future, capTicks(args))) {
            return completed("BUSY:身体任务槽已占用，先等待或取消。");
        }
        return future;
    }

    public static final class Task extends TickTask {
        private final EntityAttack attack;
        public Task(EntityAttack attack) { this.attack = attack; }
        @Override public Progress tick(CompanionPlayer companion) {
            Result result = attack.tick();
            return result == null ? running() : new Progress.Done(result);
        }
        @Override public Result interruptedResult(Result reason) { return attack.interrupt(reason.feedback()); }
        @Override public JsonObject observation() { return attack.observation(); }
        @Override public Result onResume(CompanionPlayer c) { return attack.preflight(); }
        @Override public void onSuspend() { attack.suspend(); }
        @Override public void onAbort() { attack.interrupt("CANCELLED:攻击已中止，等待新指令。"); }
        @Override public com.neko.mcbot.path.PathSpec movementScope() { return attack.movementScope(); }
    }

    /** Same native action as an explicit call, with frozen passive authority and a scoped lease. */
    public static TickTask defensive(CompanionPlayer player, LivingEntity entity,
            CompanionScheduler scheduler, ActionPermissions.BodyPermit permit,
            CompanionScheduler.ReflexLease lease, BlockPos anchor, com.neko.mcbot.path.PathSpec outerScope,
            int maxHits, boolean approach, com.neko.mcbot.path.NavigationBudget budget) {
        return new Task(new EntityAttack(new PlayerAccess(player, player.level(), entity, scheduler,
                permit.source(), permit.context(), new ActionPermissions.Execution(null), approach, anchor,
                permit, lease, outerScope, budget), maxHits));
    }

    public static boolean protectedTarget(CompanionPlayer player, LivingEntity target) {
        return !(target instanceof Mob) || target instanceof Player || target == player
                || player.isAlliedTo(target) || target.isAlliedTo(player)
                || target instanceof TamableAnimal tame && tame.isTame()
                || target instanceof AbstractHorse horse && horse.isTamed()
                || target instanceof OwnableEntity ownable && ownable.getOwnerReference() != null;
    }

    public static EntityAttack.Target describe(CompanionPlayer player, LivingEntity entity) {
        return new EntityAttack.Target(entity.getId(), entity.getUUID(),
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                entity instanceof Enemy, entity.hasCustomName(), protectedTarget(player, entity),
                entity.isAlive(), entity.getHealth(), entity.getAbsorptionAmount());
    }

    public static Result guard(CompanionPlayer player, ServerLevel level, LivingEntity entity) {
        if (player.level() != level || entity.level() != level || entity.isRemoved()
                || level.getEntity(entity.getId()) != entity) return failed("TARGET_LOST:目标已离场、换维度或被替换，重新扫描。");
        if (!player.isAlive() || !player.gameMode.isSurvival() || player.isSleeping()
                || player.isUsingItem() || player.isAutoSpinAttack()) return failed("DENIED:当前身体状态不能进行普通生存近战。");
        if (protectedTarget(player, entity)) return failed("DENIED:不攻击玩家、同伴、宠物或同队目标。");
        if (!entity.isAlive()) return null;
        if (!entity.isAttackable() || entity.isInvulnerable()) return failed("DENIED:目标当前不可攻击或无敌，换目标。");
        if (!supportedWeapon(player.getMainHandItem()))
            return failed("WRONG_TOOL:当前工具不支持穿刺、动能武器或重锤，先 equip 普通近战工具。");
        if (!loadedArea(level, player.getBoundingBox().minmax(entity.getBoundingBox()).inflate(1))) {
            return failed("TARGET_LOST:攻击及视线邻域未加载或超出边界，不强制加载。");
        }
        if (!level.mayInteract(player, entity.blockPosition())) return failed("DENIED:世界规则不允许操作目标位置。");
        ItemStack held = player.getMainHandItem();
        // This is conservative even when current motion would prevent a vanilla sweep.
        if (held.is(ItemTags.SWORDS) && !level.getEntitiesOfClass(LivingEntity.class,
                entity.getBoundingBox().inflate(1, .25, 1),
                other -> other != player && other != entity && other.isAlive()).isEmpty()) {
            return failed("UNSAFE_SWEEP:剑的横扫范围有其他活物，先更换非横扫工具或等待目标分开。");
        }
        if (player.distanceToSqr(entity) > LOCAL_RADIUS * LOCAL_RADIUS
                || !player.isWithinAttackRange(entity.getBoundingBox(), 0)) {
            return failed("OUT_OF_REACH:超出当前原版攻击距离；仅approach=true允许原范围内只行走接近。");
        }
        if (!player.hasLineOfSight(entity)) return failed("OCCLUDED:目标被遮挡，不隔墙攻击。");
        return null;
    }

    public static boolean supportedWeapon(ItemStack held) {
        return !held.has(DataComponents.PIERCING_WEAPON) && !held.has(DataComponents.KINETIC_WEAPON)
                && !(held.getItem() instanceof MaceItem);
    }

    public static boolean ready(float charge, int invulnerableTicks, boolean itemBlocked) {
        return Float.isFinite(charge) && charge >= 1 && invulnerableTicks <= 10 && !itemBlocked;
    }

    public static boolean withinApproachScope(BlockPos anchor, net.minecraft.world.phys.Vec3 body,
                                              net.minecraft.world.phys.Vec3 target) {
        return withinApproachScope(anchor, body, target, LOCAL_RADIUS);
    }

    private static boolean withinApproachScope(BlockPos anchor, net.minecraft.world.phys.Vec3 body,
                                               net.minecraft.world.phys.Vec3 target, double radius) {
        var origin = new net.minecraft.world.phys.Vec3(anchor.getX() + .5, anchor.getY(), anchor.getZ() + .5);
        return body.distanceToSqr(origin) <= radius * radius && target.distanceToSqr(origin) <= radius * radius;
    }

    private static boolean loadedArea(ServerLevel level, AABB box) {
        double cells = (Math.ceil(box.maxX) - Math.floor(box.minX) + 1)
                * (Math.ceil(box.maxY) - Math.floor(box.minY) + 1)
                * (Math.ceil(box.maxZ) - Math.floor(box.minZ) + 1);
        if (!Double.isFinite(cells) || cells <= 0 || cells > 4096) return false;
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            if (!level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos) || !level.isLoaded(pos)) return false;
        }
        return true;
    }

    private static final class PlayerAccess implements EntityAttack.Access {
        private final CompanionPlayer player;
        private final ServerLevel level;
        private final LivingEntity entity;
        private final CompanionScheduler scheduler;
        private final ActionPermissions permissions;
        private final ActionPermissions.Context context;
        private final ActionPermissions.Execution execution;
        private final boolean approach;
        private final BlockPos anchor;
        private final com.neko.mcbot.server.EntityApproach movement;
        private final ActionPermissions.BodyPermit bodyPermit;
        private final CompanionScheduler.ReflexLease reflexLease;
        private final com.neko.mcbot.path.PathSpec outerScope;

        PlayerAccess(CompanionPlayer player, ServerLevel level, LivingEntity entity,
                     CompanionScheduler scheduler, ActionPermissions permissions, ActionPermissions.Context context,
                     ActionPermissions.Execution execution, boolean approach, BlockPos anchor) {
            this(player, level, entity, scheduler, permissions, context, execution, approach, anchor,
                    null, null, com.neko.mcbot.path.PathSpec.DEFAULT, new com.neko.mcbot.path.NavigationBudget());
        }

        PlayerAccess(CompanionPlayer player, ServerLevel level, LivingEntity entity,
                     CompanionScheduler scheduler, ActionPermissions permissions, ActionPermissions.Context context,
                     ActionPermissions.Execution execution, boolean approach, BlockPos anchor,
                     ActionPermissions.BodyPermit bodyPermit, CompanionScheduler.ReflexLease reflexLease,
                     com.neko.mcbot.path.PathSpec outerScope, com.neko.mcbot.path.NavigationBudget budget) {
            this.player = player; this.level = level; this.entity = entity; this.scheduler = scheduler;
            this.permissions = permissions; this.context = context; this.execution = execution;
            this.approach = approach; this.anchor = anchor.immutable();
            this.bodyPermit = bodyPermit; this.reflexLease = reflexLease; this.outerScope = outerScope;
            this.movement = approach ? new com.neko.mcbot.server.EntityApproach(new com.neko.mcbot.server.EntityApproach.Access() {
                @Override public net.minecraft.world.phys.Vec3 targetPosition() { return entity.position(); }
                @Override public boolean canStrike() { return AttackTool.guard(player, level, entity) == null; }
                @Override public TickTask route(com.neko.mcbot.path.NavigationBudget budget) {
                    return com.neko.mcbot.path.PathTask.approach(level, entity.blockPosition(), permissions, context,
                            scheduler, approachScope(),
                            com.neko.mcbot.server.MeleeWork.goal(player, entity), budget).limitTo(outerScope);
                }
                @Override public boolean routeUseful(TickTask route) {
                    return route instanceof com.neko.mcbot.path.PathTask path
                            && path.usefulFor(com.neko.mcbot.server.MeleeWork.goal(player, entity));
                }
                @Override public TickTask.Progress advance(TickTask route) { return route.tickSafe(player); }
                @Override public void stopInput() { player.releaseMovement(); }
            }, budget) : null;
        }
        @Override public EntityAttack.Target target() { return describe(player, entity); }
        @Override public Result guard() {
            if (bodyPermit != null ? !bodyPermit.valid() : context == null || permissions == null || !context.equals(permissions.current(context.owner(),
                    player.getUUID(), context.dimension(), context.taskId(), player))
                    || !ActionPermissions.allowed(context, "attack", new JsonObject())
                    || approach && !ActionPermissions.allowed(context, "move_to", new JsonObject()))
                return failed("DENIED:原任务/身体许可已失效，未移动或攻击。");
            if (bodyPermit != null && (!AttackTool.describe(player, entity).hostile() || entity.hasCustomName()
                    || protectedTarget(player, entity)))
                return failed("DENIED:身体自保不取得中立、命名或保护目标的额外攻击许可。");
            if (bodyPermit != null && (!reflexLease.valid() || !withinApproachScope(anchor, player.position(), entity.position())))
                return failed("SCOPE_LIMIT:身体自保原范围或资源租约已失效。");
            if (!outerScope.contains(player.blockPosition().getX(), player.blockPosition().getY(), player.blockPosition().getZ()))
                return failed("SCOPE_LIMIT:自保不能离开原工作范围。");
            var current = AttackTool.guard(player, level, entity);
            if (current != null && !EntityAttack.geometricFailure(current)) return current;
            if (!entity.isAlive()) return current;
            if (approach) {
                if (!withinApproachScope(anchor, player.position(), entity.position(), bodyPermit == null ? 64 : LOCAL_RADIUS))
                    return failed("SCOPE_LIMIT:身体或原目标已离开本次接近范围。");
                var environment = com.neko.mcbot.server.MiningEnvironment.bodyFailure(player);
                if (environment != null) return environment;
            }
            return current;
        }
        @Override public Result permission(EntityAttack.Target target, int maxHits) {
            return AttackTool.permission(permissions, context, execution, target, maxHits, approach, anchor.asLong());
        }
        @Override public com.neko.mcbot.path.PathSpec movementScope() {
            return approach ? approachScope() : outerScope;
        }
        private com.neko.mcbot.path.PathSpec approachScope() {
            return new com.neko.mcbot.path.PathSpec(false, false, outerScope.underground(), 0, anchor, bodyPermit == null ? 64 : 6);
        }
        @Override public boolean canApproach() { return approach; }
        @Override public Result approach() { return movement == null ? null : movement.tick(); }
        @Override public void stopMoving() { if (movement != null) movement.stop(); }
        @Override public JsonObject movement() {
            if (movement == null) return null;
            var data = movement.observation();
            var point = new JsonObject();
            point.addProperty("x", anchor.getX());
            point.addProperty("y", anchor.getY());
            point.addProperty("z", anchor.getZ());
            data.add("anchor", point);
            data.addProperty("radius", bodyPermit == null ? 64 : LOCAL_RADIUS);
            return data;
        }
        @Override public int selectedSlot() { return player.getInventory().getSelectedSlot(); }
        @Override public ItemStack held() { return player.getMainHandItem(); }
        @Override public boolean ready() {
            float charge = player.getAttackStrengthScale(0);
            return AttackTool.ready(charge, entity.invulnerableTime, player.cannotAttackWithItem(held(), 0));
        }
        @Override public boolean reserve() {
            String dimension = bodyPermit == null ? context.dimension() : bodyPermit.dimension();
            AABB box = entity.getBoundingBox().inflate(1);
            var region = new ResourceLocks.Region(dimension, (int) Math.floor(box.minX), (int) Math.floor(box.minY),
                    (int) Math.floor(box.minZ), (int) Math.floor(box.maxX), (int) Math.floor(box.maxY), (int) Math.floor(box.maxZ));
            if (reflexLease != null)
                return reflexLease.reserve(new ResourceLocks.Target(dimension, entity.getUUID()), region);
            return scheduler.reserveTarget(player.getUUID(), new ResourceLocks.Target(dimension, entity.getUUID()))
                    && scheduler.reserve(player.getUUID(), List.of(region));
        }
        @Override public void strike() {
            player.facePoint(entity.getEyePosition());
            player.attack(entity);
            player.swing(InteractionHand.MAIN_HAND);
        }
    }

    static Result permission(ActionPermissions permissions, ActionPermissions.Context context,
            ActionPermissions.Execution execution, EntityAttack.Target target, int maxHits) {
        return permission(permissions, context, execution, target, maxHits, false, 0);
    }

    static Result permission(ActionPermissions permissions, ActionPermissions.Context context,
            ActionPermissions.Execution execution, EntityAttack.Target target, int maxHits, boolean approach, long anchor) {
        if (target.protectedTarget()) return failed("DENIED:不攻击玩家、同伴、宠物或同队目标。");
        if (target.hostile() && !target.named()) return null;
        var change = EntityAttack.change(target, maxHits, approach, anchor);
        if (permissions != null && permissions.allowsChange(context, execution, change)) return null;
        String summary = "确认对实体 " + target.type() + "（entity_id=" + target.id() + "，target_uuid="
                + target.uuid() + "）" + (approach ? "以" + BlockPos.of(anchor).toShortString()
                + "为固定脚格锚点，在64格范围内只行走接近并执行" : "在当前位置执行")
                + "原版近战，最多挥击 " + maxHits
                + " 次、最多20秒；可能伤害或杀死该目标，已完成攻击不能撤销。";
        if (com.neko.mcbot.common.WireSize.utf8Bytes(summary) > 12000) {
            return failed("DENIED:目标确认清单超出长度限制，不能批准截断清单。");
        }
        String id = permissions == null ? null : permissions.propose(new ActionPermissions.Scope(context,
                target.id(), Map.of(ActionPermissions.key(change), change)));
        var data = new JsonObject();
        if (id != null) {
            data.addProperty("authorization_id", id);
            data.addProperty("authorization_summary", summary);
        }
        return new Result(false, "NEED_CONFIRM:中立或命名目标须主人明确确认。" + summary
                + (id == null ? " 当前没有可用任务授权。" : "用 ask_owner 携带 authorization_id=" + id
                + " 确认；再携带相同实体编号/UUID/max_hits/approach和授权编号调用 attack。"), data);
    }

    private static Result failed(String feedback) { return new Result(false, feedback, null); }
    private static CompletableFuture<Result> completed(String feedback) {
        return CompletableFuture.completedFuture(failed(feedback));
    }
}
