package com.neko.mcbot.server.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.common.WireSize;
import com.neko.mcbot.server.ServerTool;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Slot numbers identify the actual stacks, including tools with different damage/components. */
public final class InventoryTool implements ServerTool {
    private static final int MAX_ITEM_ID_BYTES = 128;

    @Override
    public String name() {
        return "inventory";
    }

    @Override
    public Result run(CompanionPlayer companion, JsonObject args) {
        return report(companion.getInventory());
    }

    public static Result report(Inventory inventory) {
        int selected = inventory.getSelectedSlot();
        JsonObject data = new JsonObject();
        data.addProperty("selected_slot", selected);
        data.addProperty("storage_size", Inventory.INVENTORY_SIZE);
        JsonArray slots = new JsonArray();
        JsonArray equipment = new JsonArray();
        StringBuilder feedback = new StringBuilder("背包槽位 0-35（快捷栏 0-8），当前主手槽 ")
                .append(selected).append("：").append(describe(inventory.getSelectedItem())).append("。");
        int used = 0;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            JsonObject entry = stackData(stack);
            entry.addProperty("slot", slot);
            slots.add(entry);
            if (!stack.isEmpty()) {
                used++;
                feedback.append("\n槽 ").append(slot).append(": ").append(describe(stack));
            }
        }
        feedback.append("\n占用 ").append(used).append("/36，未列出的背包槽为空。装备栏：");
        for (int slot = Inventory.INVENTORY_SIZE; slot < inventory.getContainerSize(); slot++) {
            var equipmentSlot = Inventory.EQUIPMENT_SLOT_MAPPING.get(slot);
            if (equipmentSlot == null) continue;
            ItemStack stack = inventory.getItem(slot);
            JsonObject entry = stackData(stack);
            entry.addProperty("slot", equipmentSlot.getSerializedName());
            entry.addProperty("inventory_slot", slot);
            equipment.add(entry);
            feedback.append("\n").append(equipmentSlot.getSerializedName()).append(": ")
                    .append(describe(stack));
        }
        data.addProperty("slots_used", used);
        data.add("slots", slots);
        data.add("equipment", equipment);
        return new Result(true, feedback.toString(), data);
    }

    static JsonObject stackData(ItemStack stack) {
        JsonObject data = new JsonObject();
        String id = itemId(stack);
        data.addProperty("item", id);
        data.addProperty("count", stack.isEmpty() ? 0 : stack.getCount());
        if (!stack.isEmpty()) {
            data.addProperty("item_id_truncated", idTruncated(stack));
            if (stack.isDamageableItem()) {
                data.addProperty("damage", stack.getDamageValue());
                data.addProperty("max_damage", stack.getMaxDamage());
            }
        }
        return data;
    }

    public static String describe(ItemStack stack) {
        if (stack.isEmpty()) return "空";
        String text = itemId(stack) + (idTruncated(stack) ? "...[ID已截短]" : "")
                + " × " + stack.getCount();
        if (stack.isDamageableItem()) {
            text += "，耐久 " + (stack.getMaxDamage() - stack.getDamageValue()) + "/" + stack.getMaxDamage();
        }
        return text;
    }

    private static String itemId(ItemStack stack) {
        if (stack.isEmpty()) return "";
        // Never serialize raw components or custom text: one named item must not erase the whole receipt.
        return WireSize.truncateToBytes(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                MAX_ITEM_ID_BYTES);
    }

    private static boolean idTruncated(ItemStack stack) {
        return WireSize.utf8Bytes(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()) > MAX_ITEM_ID_BYTES;
    }
}
