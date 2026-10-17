package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.neko.mcbot.McbotMod;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.task.CompanionScheduler;
import com.neko.mcbot.task.TickTask;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Set;

/** Automatic policy selects existing stock; execution uses the public tools' same native boundary. */
public final class MaintenanceDriver implements BodyMaintenance.Access {
    private final CompanionPlayer body;
    private final BodyMaintenance maintenance = new BodyMaintenance(this);
    private ActionPermissions.BodyPermit permit;
    private CompanionScheduler.ReflexLease lease;
    private final NativeSupplyAccess access;
    private boolean unavailable;
    private String logged = "idle";

    public MaintenanceDriver(CompanionPlayer body) {
        this.body = body;
        access = new NativeSupplyAccess(body, () -> valid() ? null
                : new ServerTool.Result(false, "DENIED:原补给身体、任务、世界或资源租约失效。", null));
    }
    public boolean active() { return maintenance.active(); }
    public void cancel() { maintenance.cancel(); }
    public void stopCurrent() { maintenance.stopCurrent(); }
    public void preempt() { maintenance.preempt(); }
    public void newTask() { unavailable = false; maintenance.newTask(); }
    public void equipmentBroken(String slot) { maintenance.equipmentBroken(slot); }
    public JsonObject observation() { return maintenance.observation(); }
    public void tick() {
        var dispatcher = McbotMod.dispatcher();
        boolean allowed = !unavailable && dispatcher != null && dispatcher.belongsTo(body.level().getServer())
                && body.isAlive() && !body.isRemoved() && body.gameMode.isSurvival()
                && !body.isPassenger() && !body.isSleeping() && body.onGround()
                && body.level().isLoaded(body.blockPosition())
                && body.level().getServer().getPlayerList().getPlayer(body.getUUID()) == body
                && body.level().getServer().getPlayerList().getPlayer(body.ownerUuid()) != null
                && dispatcher.passiveBodyActionAllowed(body);
        boolean higher = body.environmentFailure() != null || body.defense().active()
                || !body.defense().maintenanceSafe() || McbotMod.scheduler().consumptionActive(body.getUUID());
        try {
            maintenance.tick(new BodyMaintenance.Frame(body.level(), allowed, higher, body.getFoodData().getFoodLevel()));
        } catch (RuntimeException failure) {
            unavailable = true;
            maintenance.cancel();
            McbotMod.LOG.warn("[body-maintenance] stopped after boundary failure type={}", failure.getClass().getSimpleName());
        }
        String state = maintenance.observation().get("state").getAsString();
        if (!logged.equals(state)) {
            logged = state;
            McbotMod.LOG.info("[body-maintenance] body_id={} state={} active={}",
                    body.observationBodyId(), state, active());
        }
    }

    public static boolean ordinary(ItemStack stack) {
        if (stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals("minecraft")) return false;
        // Damage is real wear; other per-stack changes may encode curses, special effects or custom behavior.
        return stack.getComponentsPatch().entrySet().stream().allMatch(e -> e.getKey() == DataComponents.DAMAGE)
                && (!stack.isDamageableItem() || stack.getMaxDamage() - stack.getDamageValue() > 1);
    }
    public static boolean automaticFood(ItemStack stack) {
        if (!ordinary(stack) || !ConsumptionWork.supported(stack) || stack.getItem().getClass() != Item.class) return false;
        return !stack.get(DataComponents.FOOD).canAlwaysEat()
                && stack.get(DataComponents.CONSUMABLE).onConsumeEffects().isEmpty();
    }
    @Override public BodyMaintenance.Candidate candidate(boolean food, Set<String> tried) {
        if (food) {
            for (int source = 0; source < Inventory.INVENTORY_SIZE; source++)
                if (automaticFood(body.getInventory().getItem(source)))
                    return new BodyMaintenance.Candidate(source, "consume", body.getInventory().getItem(source));
            return null;
        }
        if (body.isUsingItem()) return null;
        for (String target : EquipmentActions.ARMOR) {
            if (tried.contains(target)) continue;
            var armor = access.armor(target);
            if (armor == null || !armor.worn().isEmpty()) continue;
            for (int source = 0; source < Inventory.INVENTORY_SIZE; source++) {
                ItemStack stack = body.getInventory().getItem(source);
                var wearable = stack.get(DataComponents.EQUIPPABLE);
                var attributes = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
                boolean protective = attributes != null && attributes.modifiers().stream().anyMatch(entry ->
                        entry.attribute().equals(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR)
                                && entry.modifier().amount() > 0);
                if (ordinary(stack) && wearable != null && wearable.slot().getSerializedName().equals(target)
                        && protective && armor.accepts(stack.copyWithCount(1)))
                    return new BodyMaintenance.Candidate(source, target, stack);
            }
        }
        return null;
    }
    @Override public boolean acquire() {
        var dispatcher = McbotMod.dispatcher();
        permit = dispatcher == null ? null : dispatcher.defensePermit(body);
        if (permit == null) return false;
        lease = McbotMod.scheduler().reflexLease(body);
        if (lease == null) return false;
        body.bodyControl().reflex(true);
        return true;
    }
    @Override public boolean valid() { return permit != null && permit.valid() && lease != null && lease.valid(); }
    @Override public TickTask consume(BodyMaintenance.Candidate candidate) {
        return new ConsumptionWork(access, candidate.source(), candidate.expected());
    }
    @Override public ServerTool.Result equip(BodyMaintenance.Candidate candidate) {
        var failure = access.guard();
        if (failure != null) return failure;
        if (!ItemStack.matches(candidate.expected(), body.getInventory().getItem(candidate.source())))
            return new ServerTool.Result(false, "WRONG_TOOL:原维护候选已变化，未改选或重试。", null);
        return EquipmentActions.equip(access, candidate.source(), candidate.target());
    }
    @Override public void release() {
        try { if (lease != null) lease.close(); }
        finally {
            lease = null;
            permit = null;
            body.releaseMovement();
            body.bodyControl().reflex(body.environmentActive() || body.defense().active());
        }
    }
}
