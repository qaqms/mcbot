package com.neko.mcbot.server;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HandSnapshotTest {
    @BeforeAll static void registriesOnly() { BlockActionFixtures.bootstrap(); }

    @Test void ordinaryPickupMayMergeIntoTheSameStackButLaterRemovalStillStops() {
        var stack = new ItemStack(Items.DIRT, 3);
        var hand = new HandSnapshot(0, stack);
        stack.grow(2);
        assertTrue(hand.matches(0, stack));
        stack.shrink(1);
        assertFalse(hand.matches(0, stack));
    }

    @Test void replacementSlotComponentsAndIllegalOverstackRemainChanges() {
        var stack = new ItemStack(Items.DIRT, 3);
        var hand = new HandSnapshot(0, stack);
        assertFalse(hand.matches(0, stack.copyWithCount(5)));
        stack.grow(1);
        assertFalse(hand.matches(1, stack));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("different"));
        assertFalse(hand.matches(0, stack));
        var pick = new ItemStack(Items.IRON_PICKAXE);
        var tool = new HandSnapshot(0, pick);
        pick.grow(1);
        assertFalse(tool.matches(0, pick));
    }

    @Test void emptyHandBecomingAnItemStillRequiresANewActionObservation() {
        assertFalse(new HandSnapshot(0, ItemStack.EMPTY).matches(0, new ItemStack(Items.DIRT)));
    }
}
