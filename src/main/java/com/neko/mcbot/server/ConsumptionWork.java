package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.tools.InventoryTool;
import com.neko.mcbot.task.TickTask;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** One native held consumption. No second use, manual healing, duration formula or automatic retry. */
public final class ConsumptionWork extends TickTask {
    public static final int CAP_TICKS = 120, MAX_USE_TICKS = 100;
    public interface Access {
        ServerTool.Result guard();
        Inventory inventory();
        int food();
        float health();
        boolean using();
        boolean usingMainHand();
        boolean start();
        void advanceNativeUse();
        void stop();
    }

    private final Access access;
    private final int source;
    private final ItemStack initial;
    private EquipmentActions.HandLease hand;
    private ItemStack food, expected;
    private int beforeCount, beforeFood, nativeTicks, consumed;
    private float beforeHealth;
    private boolean started, attempted, interrupted, closed, restored, known = true;
    private ServerTool.Result terminal;

    public ConsumptionWork(Access access, int source) { this(access, source, null); }
    public ConsumptionWork(Access access, int source, ItemStack initial) {
        this.access = access; this.source = source;
        this.initial = initial == null ? null : initial.copy();
    }

    public static boolean supported(ItemStack stack) {
        var consumable = stack.get(DataComponents.CONSUMABLE);
        var food = stack.get(DataComponents.FOOD);
        return !stack.isEmpty() && food != null && food.nutrition() > 0 && consumable != null
                && Float.isFinite(consumable.consumeSeconds()) && consumable.consumeTicks() > 0
                && consumable.consumeTicks() <= MAX_USE_TICKS;
    }

    @Override public Progress tick(CompanionPlayer body) {
        if (terminal != null) return new Progress.Done(terminal);
        if (interrupted) return done(false, "INTERRUPTED:原食用已打断，未重新按下用物。");
        var failure = access.guard();
        if (failure != null) return done(false, failure.feedback());
        if (!attempted) {
            if (access.using()) return done(false, "BUSY:手部已有持续用物，未替换。");
            ItemStack selected = access.inventory().getItem(source);
            if (initial != null && !ItemStack.matches(initial, selected))
                return done(false, "WRONG_TOOL:受理后的来源堆栈已改变，未改吃另一件。");
            if (!supported(selected)) return done(false, "NO_MATERIAL:来源不是支持的有界食物，未用物。");
            beforeFood = access.food();
            beforeHealth = access.health();
            food = selected.copy();
            beforeCount = count(food);
            hand = new EquipmentActions.HandLease(access.inventory(), source);
            expected = access.inventory().getSelectedItem().copy();
            attempted = true;
            try {
                started = access.start();
            } finally {
                sample();
            }
            if (!started && consumed == 0) return done(false, "NO_PROGRESS:原版未开始食用，可能已饱或正在冷却；未重试。");
            if (!access.using()) return finishConsumption();
            return running();
        }
        if (!access.using()) {
            sample();
            return finishConsumption();
        }
        if (!access.usingMainHand() || access.inventory().getSelectedSlot() != hand.usedSlot()
                || !ItemStack.matches(expected, access.inventory().getSelectedItem()))
            return done(false, "WRONG_TOOL:持续用物的原主手或组件已改变，未继续消费。");
        if (++nativeTicks > MAX_USE_TICKS) return done(false, "TIMEOUT:原生用物达到固定100刻推进帽，未重开。");
        try {
            access.advanceNativeUse();
        } finally {
            sample();
        }
        if (!access.using()) return finishConsumption();
        if (consumed > 0) return done(false, "PARTIAL:仍在用物时库存已变化，停止核对，不重复消费。");
        return running();
    }

    private Progress finishConsumption() {
        return consumed == 1 && known ? done(true, "已完成一次原生食用；实际饥饿和生命见回执，外层目标另行验收。")
                : done(false, "NO_PROGRESS:用物已结束但无法确认恰好消费一件原食物；先核对真实库存，不自动重试。");
    }
    private int count(ItemStack match) {
        int total = 0;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = access.inventory().getItem(slot);
            if (ItemStack.isSameItemSameComponents(stack, match)) total += stack.getCount();
        }
        return total;
    }
    private void sample() {
        if (!attempted) return;
        consumed = Math.max(0, beforeCount - count(food));
        known &= consumed <= 1;
        expected = access.inventory().getItem(hand.usedSlot()).copy();
    }
    private Progress done(boolean ok, String feedback) {
        close();
        terminal = new ServerTool.Result(ok, feedback, observation());
        return new Progress.Done(terminal);
    }
    private void close() {
        if (closed) return;
        closed = true;
        if (attempted) {
            // A different hand/item can belong to a newer action; cleanup must not move it.
            boolean ownHand = access.inventory().getSelectedSlot() == hand.usedSlot()
                    && ItemStack.matches(expected, access.inventory().getSelectedItem());
            try {
                if (ownHand && access.using() && access.usingMainHand()) access.stop();
            } finally {
                restored = hand.restore(expected);
            }
        }
    }
    @Override public void onAbort() { interrupted = true; close(); }
    @Override public void onSuspend() { interrupted = true; close(); }
    @Override public ServerTool.Result onResume(CompanionPlayer body) {
        return new ServerTool.Result(false, "INTERRUPTED:食用被抢占，原用物已停止，未自动重新消费。", observation());
    }
    @Override public ServerTool.Result interruptedResult(ServerTool.Result reason) {
        close();
        return new ServerTool.Result(false, reason.feedback(), observation());
    }
    @Override public JsonObject observation() {
        var data = new JsonObject();
        data.addProperty("source_slot", source);
        data.addProperty("use_attempted", attempted);
        data.addProperty("use_started", started);
        data.addProperty("native_use_ticks", nativeTicks);
        data.addProperty("consumed_count", consumed);
        data.addProperty("observation_known", known);
        data.addProperty("hand_restored", restored);
        data.addProperty("food", access.food());
        data.addProperty("hp", access.health());
        if (attempted) {
            data.addProperty("food_before", beforeFood);
            data.addProperty("hp_before", beforeHealth);
            data.add("source_after", InventoryTool.stackData(access.inventory().getItem(source)));
        }
        data.addProperty("goal_satisfied", false);
        data.addProperty("automatic_resume", false);
        return data;
    }
}
