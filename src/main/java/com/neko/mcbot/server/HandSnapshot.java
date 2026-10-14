package com.neko.mcbot.server;

import net.minecraft.world.item.ItemStack;

/** A pickup may grow the live hand stack without changing the item used by an action. */
final class HandSnapshot {
    private final int slot;
    private final ItemStack source;
    private ItemStack snapshot;

    HandSnapshot(int slot, ItemStack stack) {
        this.slot = slot;
        source = stack;
        snapshot = stack.copy();
    }

    boolean matches(int currentSlot, ItemStack stack) {
        if (slot != currentSlot) return false;
        if (ItemStack.matches(snapshot, stack)) return true;
        if (source != stack || snapshot.isEmpty() || stack.getCount() <= snapshot.getCount()
                || stack.getCount() > stack.getMaxStackSize()
                || !ItemStack.isSameItemSameComponents(snapshot, stack)) return false;
        snapshot = stack.copy();
        return true;
    }
}
