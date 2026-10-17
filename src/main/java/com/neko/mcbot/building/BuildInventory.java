package com.neko.mcbot.building;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Shared plain-material policy; never strip components or borrow armour/offhand slots. */
public final class BuildInventory {
    private BuildInventory() { }

    public static boolean accepts(ItemStack stack, Item item) {
        return !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, new ItemStack(item));
    }
    public static int available(Container inventory, Item item) {
        long total = 0;
        for (int slot = 0; slot < Math.min(Inventory.INVENTORY_SIZE, inventory.getContainerSize()); slot++) {
            var stack = inventory.getItem(slot);
            if (accepts(stack, item)) total += stack.getCount();
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }
    public static int slot(Container inventory, Item item) {
        for (int slot = 0; slot < Math.min(Inventory.INVENTORY_SIZE, inventory.getContainerSize()); slot++)
            if (accepts(inventory.getItem(slot), item)) return slot;
        return -1;
    }
}
