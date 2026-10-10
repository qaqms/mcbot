package com.neko.mcbot.server;

import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** A bounded attack against one identity; native damage remains behind Access.strike. */
public final class EntityAttack {
    public record Target(int id, UUID uuid, String type, boolean hostile, boolean named,
                         boolean protectedTarget, boolean alive, float health, float absorption) {
    }

    public record Approval(UUID uuid, String type, boolean hostile, boolean named, int maxHits) {
    }

    public interface Access {
        Target target();
        ServerTool.Result guard();
        ServerTool.Result permission(Target target, int maxHits);
        int selectedSlot();
        ItemStack held();
        boolean ready();
        boolean reserve();
        void strike();
    }

    private final Access access;
    private final int maxHits;
    private Target target;
    private ItemStack held;
    private int selected;
    private int strikes;
    private float healthLoss, absorptionLoss;
    private boolean observationKnown = true;
    private ServerTool.Result terminal;

    public EntityAttack(Access access, int maxHits) {
        if (maxHits < 1 || maxHits > 10) throw new IllegalArgumentException("maxHits");
        this.access = access;
        this.maxHits = maxHits;
    }

    public static Approval approval(Target target, int maxHits) {
        return new Approval(target.uuid(), target.type(), target.hostile(), target.named(), maxHits);
    }

    public static ActionPermissions.Change change(Target target, int maxHits) {
        return new ActionPermissions.Change("attack", target.id(), approval(target, maxHits));
    }

    public ServerTool.Result preflight() {
        if (terminal != null) return terminal;
        ServerTool.Result failure = access.guard();
        if (failure != null) return finish(false, failure.feedback(), failure.data());
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
            held = access.held().copy();
            selected = access.selectedSlot();
        } else if (selected != access.selectedSlot() || !ItemStack.matches(held, access.held())) {
            return finish(false, "WRONG_TOOL:攻击期间主手或选中槽改变，已停手；核对 inventory/equip。", null);
        }
        if (!access.reserve()) return finish(false, "BUSY:目标实体或附近区域已被其他动作占用，已停手。", null);
        return null;
    }

    /** Null means waiting. A terminal result is stable and never repeats a strike. */
    public ServerTool.Result tick() {
        ServerTool.Result failure = preflight();
        if (failure != null) return failure;
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
        held = access.held().copy();
        selected = access.selectedSlot();
        target = after;
        if (!after.alive()) return finish(true, "目标已观察为死亡，本次停止。", null);
        if (before.health() <= after.health() && before.absorption() <= after.absorption()) {
            return finish(false, "ATTACK_FAILED:出手后没有观察到生命或吸收值减少，已停手；核对目标，勿自动重试。", null);
        }
        if (strikes >= maxHits) return finish(true, "已达到本次挥击上限，目标仍存活。", null);
        return null;
    }

    public ServerTool.Result interrupt(String feedback) {
        if (terminal == null) return finish(false, feedback, null);
        return terminal;
    }

    private ServerTool.Result finish(boolean ok, String feedback, com.google.gson.JsonObject extra) {
        var data = extra == null ? new com.google.gson.JsonObject() : extra.deepCopy();
        data.addProperty("strikes", strikes);
        data.addProperty("max_hits", maxHits);
        data.addProperty("observed_health_loss", healthLoss);
        data.addProperty("observed_absorption_loss", absorptionLoss);
        data.addProperty("observation_known", observationKnown);
        if (target != null) {
            data.addProperty("entity_id", target.id());
            data.addProperty("target_uuid", target.uuid().toString());
            data.addProperty("target_type", com.neko.mcbot.common.WireSize.truncateToBytes(target.type(), 128));
            data.addProperty("target_type_truncated", com.neko.mcbot.common.WireSize.utf8Bytes(target.type()) > 128);
            data.addProperty("target_dead", !target.alive());
            data.addProperty("health", target.health());
            data.addProperty("absorption", target.absorption());
        }
        terminal = new ServerTool.Result(ok, feedback + " 本次调用原版攻击 " + strikes + "/" + maxHits
                + " 次，已观察生命减少 " + healthLoss + "、吸收减少 " + absorptionLoss
                + "；死亡不等于本次独占击杀归因，未自动拾取掉落。"
                + (observationKnown ? "" : " 最后一次出手结果未知，可能已有副作用，不能重投。"), data);
        return terminal;
    }
}
