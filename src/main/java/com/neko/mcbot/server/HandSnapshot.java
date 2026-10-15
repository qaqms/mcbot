package com.neko.mcbot.server;

import net.minecraft.world.item.ItemStack;

/** A pickup may grow the live hand stack without changing the item used by an action. */
public final class HandSnapshot {
    private final int slot;
    private ItemStack source;
    private ItemStack snapshot;
    private final Pickups pickups;
    private long pickupVersion;

    public HandSnapshot(int slot, ItemStack stack) {
        this(slot, stack, null);
    }

    public HandSnapshot(int slot, ItemStack stack, Pickups pickups) {
        this.slot = slot;
        source = stack;
        snapshot = stack.copy();
        this.pickups = pickups;
        pickupVersion = pickups == null ? 0 : pickups.version;
    }

    public boolean matches(int currentSlot, ItemStack stack) {
        if (slot != currentSlot) return false;
        if (ItemStack.matches(snapshot, stack)) return true;
        if (snapshot.isEmpty() && pickups != null && pickups.version > pickupVersion
                && pickups.slot == slot && pickups.after == stack && !stack.isEmpty()
                && stack.getCount() <= stack.getMaxStackSize()
                && ItemStack.matches(pickups.observed, stack)) {
            source = stack;
            snapshot = stack.copy();
            pickupVersion = pickups.version;
            return true;
        }
        if (source != stack || snapshot.isEmpty() || stack.getCount() <= snapshot.getCount()
                || stack.getCount() > stack.getMaxStackSize()
                || !ItemStack.isSameItemSameComponents(snapshot, stack)) return false;
        snapshot = stack.copy();
        return true;
    }

    /** Evidence is local to one body and is recorded only around its native playerTouch. */
    public static final class Pickups {
        private long version;
        private int slot;
        private ItemStack after = ItemStack.EMPTY;
        private ItemStack observed = ItemStack.EMPTY;

        public void recorded(int beforeSlot, ItemStack before, int afterSlot, ItemStack stack, int taken) {
            if (taken <= 0 || beforeSlot != afterSlot || stack.isEmpty()) return;
            if (!before.isEmpty()) {
                if (after == stack && slot == afterSlot && ItemStack.matches(observed, before))
                    observed = stack.copy();
                else {
                    after = ItemStack.EMPTY;
                    observed = ItemStack.EMPTY;
                }
                return;
            }
            version++;
            slot = afterSlot;
            after = stack;
            observed = stack.copy();
        }
    }
}
