package com.neko.mcbot.server;

import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.function.Consumer;

/** Server-thread moves: account for partial insertion rather than a success boolean. */
public final class ItemTransfers {
    private ItemTransfers() {
    }

    public record Insertion(int moved, ItemStack remainder) {
    }

    /** The input stays untouched; only the returned remainder may be returned to the world. */
    public static Insertion receive(Container inventory, ItemStack input, Consumer<ItemStack> settleRemainder) {
        Insertion result = insert(inventory, Inventory.INVENTORY_SIZE, input, null);
        settleRemainder.accept(result.remainder());
        return result;
    }

    public static Insertion move(Container source, int slot, Container target, int targetSlots, Direction face) {
        ItemStack input = source.getItem(slot);
        if (source == target || input.isEmpty() || !source.canTakeItem(target, slot, input)
                || !throughFace(source, slot, input, face, false)) {
            return new Insertion(0, input.copy());
        }
        Insertion result = insert(target, targetSlots, input, face);
        if (result.moved() > 0) {
            source.setItem(slot, result.remainder());
            source.setChanged();
        }
        return result;
    }

    /** Merge before using empty slots. Never use equipment slots as inventory overflow. */
    public static Insertion insert(Container target, int slotCount, ItemStack input, Direction face) {
        ItemStack need = input.copy();
        int slots = Math.min(slotCount, target.getContainerSize());
        for (int pass = 0; pass < 2 && !need.isEmpty(); pass++) {
            for (int slot = 0; slot < slots && !need.isEmpty(); slot++) {
                ItemStack at = target.getItem(slot);
                if ((pass == 0 && (at.isEmpty() || !ItemStack.isSameItemSameComponents(at, need)))
                        || (pass == 1 && !at.isEmpty())) continue;
                if (!target.canPlaceItem(slot, need) || !throughFace(target, slot, need, face, true)) continue;
                int limit = Math.min(need.getMaxStackSize(), target.getMaxStackSize(need));
                int give = Math.min(need.getCount(), Math.max(0, limit - at.getCount()));
                if (give == 0) continue;
                // Work on a copy: a container must not see an already-grown live stack.
                ItemStack combined = at.isEmpty() ? need.copyWithCount(give) : at.copyWithCount(at.getCount() + give);
                target.setItem(slot, combined);
                need.shrink(give);
            }
        }
        int moved = input.getCount() - need.getCount();
        if (moved > 0) target.setChanged();
        return new Insertion(moved, need.isEmpty() ? ItemStack.EMPTY : need);
    }

    private static boolean throughFace(Container container, int slot, ItemStack stack, Direction face,
                                       boolean placing) {
        if (!(container instanceof WorldlyContainer sided)) return true;
        if (face == null) return false;
        boolean exposed = false;
        for (int accessible : sided.getSlotsForFace(face)) {
            if (accessible == slot) {
                exposed = true;
                break;
            }
        }
        return exposed && (placing ? sided.canPlaceItemThroughFace(slot, stack, face)
                : sided.canTakeItemThroughFace(slot, stack, face));
    }
}
