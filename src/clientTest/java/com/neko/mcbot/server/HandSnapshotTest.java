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

    @Test void onlyAnObservedNativePickupCanFillAnEmptyMiningHand() {
        var pickups = new HandSnapshot.Pickups();
        var hand = new HandSnapshot(0, ItemStack.EMPTY, pickups);
        var logs = new ItemStack(Items.BIRCH_LOG, 2);
        assertFalse(hand.matches(0, logs));
        pickups.recorded(0, ItemStack.EMPTY, 0, logs, 2);
        assertTrue(hand.matches(0, logs));
        logs.grow(1);
        assertTrue(hand.matches(0, logs));
        assertFalse(hand.matches(1, logs));
        assertFalse(hand.matches(0, new ItemStack(Items.IRON_AXE)));
        logs.shrink(1);
        assertFalse(hand.matches(0, logs));
    }

    @Test void staleOtherBodyAndUnsuccessfulPickupEvidenceCannotAuthorizeAHandChange() {
        var pickups = new HandSnapshot.Pickups();
        var logs = new ItemStack(Items.ACACIA_LOG);
        pickups.recorded(0, ItemStack.EMPTY, 0, logs, 1);
        var later = new HandSnapshot(0, ItemStack.EMPTY, pickups);
        assertFalse(later.matches(0, logs));
        var otherBody = new HandSnapshot(0, ItemStack.EMPTY, new HandSnapshot.Pickups());
        assertFalse(otherBody.matches(0, logs));
        for (int scenario = 0; scenario < 3; scenario++) {
            var fresh = new HandSnapshot.Pickups();
            var hand = new HandSnapshot(0, ItemStack.EMPTY, fresh);
            switch (scenario) {
                case 0 -> fresh.recorded(0, ItemStack.EMPTY, 0, logs, 0);
                case 1 -> fresh.recorded(1, ItemStack.EMPTY, 0, logs, 1);
                case 2 -> fresh.recorded(0, new ItemStack(Items.DIRT), 0, logs, 1);
            }
            assertFalse(hand.matches(0, logs));
        }
    }

    @Test void severalNativeMergesBeforeTheNextMiningTickKeepFreshEvidenceButNotReplacement() {
        var pickups = new HandSnapshot.Pickups();
        var hand = new HandSnapshot(0, ItemStack.EMPTY, pickups);
        var logs = new ItemStack(Items.ACACIA_LOG, 2);
        pickups.recorded(0, ItemStack.EMPTY, 0, logs, 2);
        var before = logs.copy();
        logs.grow(3);
        pickups.recorded(0, before, 0, logs, 3);
        assertFalse(hand.matches(0, logs.copy()));
        logs.set(DataComponents.CUSTOM_NAME, Component.literal("changed after pickup"));
        assertFalse(hand.matches(0, logs));
        logs.remove(DataComponents.CUSTOM_NAME);
        assertTrue(hand.matches(0, logs));
    }
}
