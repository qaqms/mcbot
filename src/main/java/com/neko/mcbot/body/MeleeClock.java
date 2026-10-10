package com.neko.mcbot.body;

import net.minecraft.world.item.ItemStack;

/** The two Player.tick melee counters, without starting full player physics or pickup. */
public final class MeleeClock {
    public record State(int attackTicks, int swapTicks) {
    }

    private ItemStack previous = ItemStack.EMPTY;

    public State tick(ItemStack held, int attackTicks, int swapTicks) {
        boolean changedItem = !ItemStack.isSameItem(previous, held);
        if (changedItem) previous = held.copy();
        return changedItem ? new State(0, 0) : new State(attackTicks + 1, swapTicks + 1);
    }
}
