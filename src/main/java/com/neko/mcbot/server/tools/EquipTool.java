package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.neko.mcbot.McbotMod;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.task.CompanionScheduler;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.concurrent.CompletableFuture;

/** Main-hand selection only; exchange whole stacks without merging or requiring a free slot. */
public final class EquipTool implements ServerTool {
    @Override
    public String name() {
        return "equip";
    }

    @Override
    public Result run(CompanionPlayer companion, JsonObject args) {
        return equip(companion, args, McbotMod.scheduler());
    }

    @Override
    public CompletableFuture<Result> runAsync(CompanionPlayer companion, JsonObject args,
                                               CompanionScheduler scheduler) {
        return CompletableFuture.completedFuture(equip(companion, args, scheduler));
    }

    static Integer sourceSlot(JsonObject args) {
        var value = args == null ? null : args.get("slot");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        try {
            int slot = value.getAsBigDecimal().intValueExact();
            return slot >= 0 && slot < Inventory.INVENTORY_SIZE ? slot : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static Result equip(CompanionPlayer companion, JsonObject args, CompanionScheduler scheduler) {
        Integer source = sourceSlot(args);
        if (source == null) {
            return new Result(false, "DENIED:slot 必须是 0-35 的整数；先用 inventory 查看实际槽位。", null);
        }
        if (scheduler.busy(companion.getUUID())) {
            return new Result(false, "BUSY:我正忙着上一件事，等它结束或取消后再切换主手。", null);
        }
        Inventory inventory = companion.getInventory();
        ItemStack stack = inventory.getItem(source);
        if (stack.isEmpty()) {
            return new Result(false, "DENIED:槽 " + source + " 为空；先用 inventory 查看可用物品。", null);
        }
        int selected = inventory.getSelectedSlot();
        boolean swapped = !Inventory.isHotbarSlot(source);
        if (swapped) {
            ItemStack previous = inventory.getItem(selected);
            inventory.setItem(selected, stack);
            inventory.setItem(source, previous);
        } else {
            inventory.setSelectedSlot(source);
        }
        inventory.setChanged();
        JsonObject data = new JsonObject();
        data.addProperty("source_slot", source);
        data.addProperty("selected_slot", inventory.getSelectedSlot());
        data.addProperty("swapped", swapped);
        data.add("held", InventoryTool.stackData(inventory.getSelectedItem()));
        if (swapped) data.add("source_after", InventoryTool.stackData(inventory.getItem(source)));
        String feedback = "主手已切换为 " + InventoryTool.describe(inventory.getSelectedItem())
                + "，快捷栏槽 " + inventory.getSelectedSlot() + "。";
        if (swapped) {
            feedback += "原主手移到槽 " + source + "：" + InventoryTool.describe(inventory.getItem(source)) + "。";
        }
        return new Result(true, feedback, data);
    }
}
