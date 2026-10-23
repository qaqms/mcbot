package com.neko.mcbot.server;

import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** Attack one identity until its outcome is known; native damage stays behind Access.strike. */
public final class EntityAttack {
    public record Target(int id, UUID uuid, String type, boolean hostile, boolean named,
                         boolean protectedTarget, boolean alive, float health, float absorption) {
    }

    public record Approval(UUID uuid, String type, boolean hostile, boolean named, int maxHits,
                           boolean approach, long anchor) {
    }

    public interface Access {
        Target target();
        ServerTool.Result guard();
        ServerTool.Result permission(Target target, int maxHits);
        int selectedSlot();
        ItemStack held();
        default HandSnapshot.Pickups handPickups() { return null; }
        boolean ready();
        boolean reserve();
        void strike();
        default boolean canApproach() { return false; }
        default ServerTool.Result approach() { return null; }
        default void stopMoving() { }
        default com.google.gson.JsonObject movement() { return null; }
        default com.neko.mcbot.path.PathSpec movementScope() { return com.neko.mcbot.path.PathSpec.DEFAULT; }
    }

    private final Access access;
    private final int maxHits;
    private Target target;
    private HandSnapshot held;
    private int strikes;
    private float healthLoss, absorptionLoss;
    private boolean observationKnown = true;
    private ServerTool.Result terminal;
    private ServerTool.Result strikeGuard;

    public EntityAttack(Access access, int maxHits) {
        if (maxHits < 0) throw new IllegalArgumentException("maxHits");
        this.access = access;
        this.maxHits = maxHits;
    }

    public static Approval approval(Target target, int maxHits) {
        return approval(target, maxHits, false, 0);
    }

    public static Approval approval(Target target, int maxHits, boolean approach, long anchor) {
        return new Approval(target.uuid(), target.type(), target.hostile(), target.named(), maxHits,
                approach, approach ? anchor : 0);
    }

    public static ActionPermissions.Change change(Target target, int maxHits) {
        return new ActionPermissions.Change("attack", target.id(), approval(target, maxHits));
    }

    public static ActionPermissions.Change change(Target target, int maxHits, boolean approach, long anchor) {
        return new ActionPermissions.Change("attack", target.id(), approval(target, maxHits, approach, anchor));
    }

    public static boolean geometricFailure(ServerTool.Result result) {
        return result != null && (result.feedback().startsWith("OUT_OF_REACH:")
                || result.feedback().startsWith("OCCLUDED:"));
    }

    public ServerTool.Result preflight() {
        if (terminal != null) return terminal;
        ServerTool.Result failure = access.guard();
        strikeGuard = failure;
        if (failure != null && !(access.canApproach() && geometricFailure(failure)))
            return finish(false, failure.feedback(), failure.data());
        Target fresh = access.target();
        if (target != null && (fresh.id() != target.id() || !fresh.uuid().equals(target.uuid()))) {
            return finish(false, "TARGET_LOST:实体身份已改变，先重新扫描，不能沿用旧编号。", null);
        }
        target = fresh;
        if (fresh.protectedTarget()) return finish(false, "DENIED:不攻击玩家、同伴、宠物或同队目标。", null);
        if (!fresh.alive()) {
            return finish(strikes > 0, strikes > 0 ? "目标已观察为死亡，本次停止。"
                    : "TARGET_LOST:目标已死亡，本次没有出手。", null);
        }
        failure = access.permission(fresh, maxHits);
        if (failure != null) return finish(false, failure.feedback(), failure.data());
        if (held == null) {
            held = new HandSnapshot(access.selectedSlot(), access.held(), access.handPickups());
        } else if (!held.matches(access.selectedSlot(), access.held())) {
            return finish(false, "WRONG_TOOL:攻击期间主手或选中槽改变，已停手；核对 inventory/equip。", null);
        }
        if (!access.reserve()) return finish(false, "BUSY:目标实体或附近区域已被其他动作占用，已停手。", null);
        return null;
    }

    /** Null means waiting. A terminal result is stable and never repeats a strike. */
    public ServerTool.Result tick() {
        ServerTool.Result failure = preflight();
        if (failure != null) return failure;
        if (access.canApproach()) {
            failure = access.approach();
            if (failure != null) return finish(false, failure.feedback(), failure.data());
            failure = preflight();
            if (failure != null) return failure;
            if (strikeGuard != null) return null;
        }
        if (!access.ready()) return null;
        Target before = access.target();
        strikes++;
        observationKnown = false;
        access.strike();
        Target after = access.target();
        if (before.id() != after.id() || !before.uuid().equals(after.uuid())) {
            return finish(false, "TARGET_LOST:出手后实体身份改变，无法确认伤害；不自动重试。", null);
        }
        healthLoss += Math.max(0, before.health() - after.health());
        absorptionLoss += Math.max(0, before.absorption() - after.absorption());
        observationKnown = true;
        // Native durability/breakage is accepted only after our own strike.
        held = new HandSnapshot(access.selectedSlot(), access.held(), access.handPickups());
        target = after;
        if (!after.alive()) return finish(true, "目标已观察为死亡，本次停止。", null);
        if (before.health() <= after.health() && before.absorption() <= after.absorption()) {
            return finish(false, "ATTACK_FAILED:出手后没有观察到生命或吸收值减少，已停手；核对目标，勿自动重试。", null);
        }
        if (maxHits > 0 && strikes >= maxHits)
            return finish(true, "已完成明确指定的挥击次数，目标仍存活。", null);
        return null;
    }

    public ServerTool.Result interrupt(String feedback) {
        if (terminal == null) return finish(false, feedback, null);
        return terminal;
    }

    public void suspend() { access.stopMoving(); }

    public com.neko.mcbot.path.PathSpec movementScope() { return access.movementScope(); }

    public com.google.gson.JsonObject observation() {
        var data = new com.google.gson.JsonObject();
        data.addProperty("action", "attack");
        data.addProperty("strikes", strikes);
        data.addProperty("max_hits", maxHits);
        data.addProperty("observation_known", observationKnown);
        var movement = access.movement();
        if (movement != null) data.add("approach", movement);
        return data;
    }

    private ServerTool.Result finish(boolean ok, String feedback, com.google.gson.JsonObject extra) {
        access.stopMoving();
        var data = extra == null ? new com.google.gson.JsonObject() : extra.deepCopy();
        data.addProperty("strikes", strikes);
        data.addProperty("max_hits", maxHits);
        data.addProperty("observed_health_loss", healthLoss);
        data.addProperty("observed_absorption_loss", absorptionLoss);
        data.addProperty("observation_known", observationKnown);
        var movement = access.movement();
        if (movement != null) data.add("approach", movement);
        if (target != null) {
            data.addProperty("entity_id", target.id());
            data.addProperty("target_uuid", target.uuid().toString());
            data.addProperty("target_type", com.neko.mcbot.common.WireSize.truncateToBytes(target.type(), 128));
            data.addProperty("target_type_truncated", com.neko.mcbot.common.WireSize.utf8Bytes(target.type()) > 128);
            data.addProperty("target_dead", !target.alive());
            data.addProperty("health", target.health());
            data.addProperty("absorption", target.absorption());
        }
        terminal = new ServerTool.Result(ok, feedback + " 本次调用原版攻击 " + strikes
                + (maxHits > 0 ? "/" + maxHits : "")
                + " 次，已观察生命减少 " + healthLoss + "、吸收减少 " + absorptionLoss
                + "；死亡不等于本次独占击杀归因，未主动追捡掉落，身体触碰仍按原规则处理。"
                + (observationKnown ? "" : " 最后一次出手结果未知，可能已有副作用，不能重投。"), data);
        return terminal;
    }
}
