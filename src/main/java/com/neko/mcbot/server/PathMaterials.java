package com.neko.mcbot.server;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Planning and execution use exactly the same plain, inexpensive support materials. */
public final class PathMaterials {
    private PathMaterials() {
    }

    public static boolean allowed(ItemStack stack) {
        return !stack.isEmpty() && stack.getComponentsPatch().isEmpty()
                && (stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE)
                || stack.is(Items.DIRT) || stack.is(Items.NETHERRACK));
    }

    public static int count(Container inventory) {
        int count = 0;
        for (int slot = 0; slot < Math.min(Inventory.INVENTORY_SIZE, inventory.getContainerSize()); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (allowed(stack)) count += stack.getCount();
        }
        return count;
    }

    public static int find(Container inventory) {
        for (int slot = 0; slot < Math.min(Inventory.INVENTORY_SIZE, inventory.getContainerSize()); slot++) {
            if (allowed(inventory.getItem(slot))) return slot;
        }
        return -1;
    }
}
