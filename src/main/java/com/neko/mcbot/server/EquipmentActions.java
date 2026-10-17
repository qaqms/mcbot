package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.neko.mcbot.server.tools.InventoryTool;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Inventory moves shared by explicit equipment, timed consumption and body maintenance. */
public final class EquipmentActions {
    private EquipmentActions() { }
    public static final java.util.List<String> ARMOR = java.util.List.of("head", "chest", "legs", "feet");

    public interface Armor {
        ItemStack worn();
        boolean accepts(ItemStack stack);
        void put(ItemStack stack);
    }
    public interface Access {
        Inventory inventory();
        Armor armor(String target);
    }

    public static void hold(Inventory inventory, int source) {
        if (Inventory.isHotbarSlot(source)) inventory.setSelectedSlot(source);
        else {
            int selected = inventory.getSelectedSlot();
            ItemStack previous = inventory.getItem(selected);
            inventory.setItem(selected, inventory.getItem(source));
            inventory.setItem(source, previous);
        }
        inventory.setChanged();
    }

    public static ServerTool.Result equip(Access access, int source, String target) {
        Inventory inventory = access.inventory();
        ItemStack stack = inventory.getItem(source);
        if (stack.isEmpty()) return failed("NO_MATERIAL:指定来源槽为空，未换装。");
        var data = new JsonObject();
        data.addProperty("source_slot", source);
        data.addProperty("target", target);
        data.addProperty("consumed_count", 0);
        if (target.equals("mainhand")) {
            boolean changed = source != inventory.getSelectedSlot();
            hold(inventory, source);
            data.addProperty("changed", changed);
            data.addProperty("selected_slot", inventory.getSelectedSlot());
            data.addProperty("swapped", !Inventory.isHotbarSlot(source));
            data.add("held", InventoryTool.stackData(inventory.getSelectedItem()));
            if (!Inventory.isHotbarSlot(source)) data.add("source_after", InventoryTool.stackData(inventory.getItem(source)));
            return new ServerTool.Result(true, "主手已切换为 " + InventoryTool.describe(inventory.getSelectedItem())
                    + "，快捷栏槽 " + inventory.getSelectedSlot() + "。", data);
        }
        Armor armor = access.armor(target);
        if (armor == null || !armor.worn().isEmpty())
            return failed("TARGET_BLOCKED:只补空的原版盔甲槽；已有装备保留，不隐式升级或卸装。");
        ItemStack one = stack.copyWithCount(1);
        if (!armor.accepts(one)) return failed("WRONG_TOOL:原版装备槽不接受此物品，未移动库存。");
        ItemStack original = stack.copy();
        try {
            armor.put(inventory.removeItem(source, 1));
        } finally {
            inventory.setChanged();
            data.addProperty("changed", !ItemStack.matches(original, inventory.getItem(source))
                    || !armor.worn().isEmpty());
            data.addProperty("equipped", ItemStack.matches(one, armor.worn()));
            data.add("source_after", InventoryTool.stackData(inventory.getItem(source)));
            data.add("worn", InventoryTool.stackData(armor.worn()));
        }
        boolean equipped = data.get("equipped").getAsBoolean()
                && ItemStack.matches(original.copyWithCount(original.getCount() - 1), inventory.getItem(source));
        return new ServerTool.Result(equipped, equipped ? "已从真实背包穿戴到空的 " + target + " 槽。"
                : "PARTIAL:原版装备动作后的实际结果不匹配，先核对库存和装备；未回滚或重放。", data);
    }

    /** Restore only the two still-owned locations. Native bowls/remainders stay in inventory. */
    public static final class HandLease {
        private final Inventory inventory;
        private final int selected, source, used;
        private final ItemStack oldHand;
        private boolean closed, restored;

        public HandLease(Inventory inventory, int source) {
            this.inventory = inventory;
            this.source = source;
            selected = inventory.getSelectedSlot();
            oldHand = inventory.getSelectedItem().copy();
            hold(inventory, source);
            used = inventory.getSelectedSlot();
        }
        public int usedSlot() { return used; }
        public boolean restore(ItemStack expected) {
            if (closed) return restored;
            closed = true;
            if (source == selected) return restored = true;
            if (inventory.getSelectedSlot() != used || !ItemStack.matches(inventory.getItem(used), expected))
                return false;
            if (Inventory.isHotbarSlot(source)) {
                if (!ItemStack.matches(inventory.getItem(selected), oldHand)) return false;
                inventory.setSelectedSlot(selected);
            } else {
                if (!ItemStack.matches(inventory.getItem(source), oldHand)) return false;
                ItemStack remainder = inventory.getItem(used);
                inventory.setItem(used, inventory.getItem(source));
                inventory.setItem(source, remainder);
            }
            inventory.setChanged();
            return restored = true;
        }
    }

    private static ServerTool.Result failed(String feedback) {
        var data = new JsonObject();
        data.addProperty("changed", false);
        data.addProperty("consumed_count", 0);
        return new ServerTool.Result(false, feedback, data);
    }
}
