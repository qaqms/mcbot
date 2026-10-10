package com.neko.mcbot.server;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.neko.mcbot.server.BlockActionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class PathMaterialsTest {
    @BeforeAll static void registriesOnly() { bootstrap(); }

    @Test void onlyFourPlainSupportMaterialsAreAllowed() {
        for (var item : new net.minecraft.world.item.Item[]{
                Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DIRT, Items.NETHERRACK}) {
            assertTrue(PathMaterials.allowed(new ItemStack(item)));
        }
        for (var item : new net.minecraft.world.item.Item[]{Items.SAND, Items.GRAVEL, Items.OAK_LOG,
                Items.CHEST, Items.CRAFTING_TABLE, Items.DIAMOND_BLOCK, Items.TORCH, Items.IRON_PICKAXE}) {
            assertFalse(PathMaterials.allowed(new ItemStack(item)));
        }
        assertFalse(PathMaterials.allowed(ItemStack.EMPTY));
    }

    @Test void namedAddedAndRemovedComponentsCannotBeSpentByPath() {
        ItemStack named = new ItemStack(Items.DIRT);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("keep"));
        ItemStack added = new ItemStack(Items.COBBLESTONE);
        added.set(DataComponents.MAX_STACK_SIZE, 16);
        ItemStack removed = new ItemStack(Items.NETHERRACK);
        removed.remove(DataComponents.MAX_STACK_SIZE);
        for (var stack : new ItemStack[]{named, added, removed}) assertFalse(PathMaterials.allowed(stack));
    }

    @Test void countAndSelectionUseExactlySameStoragePredicateWithoutEquipment() {
        var inventory = inventory();
        inventory.setSelectedSlot(4);
        inventory.setItem(0, new ItemStack(Items.SAND, 32));
        inventory.setItem(1, new ItemStack(Items.COBBLESTONE, 5));
        inventory.setItem(20, new ItemStack(Items.DIRT, 11));
        inventory.setItem(35, new ItemStack(Items.NETHERRACK, 2));
        inventory.setItem(40, new ItemStack(Items.COBBLESTONE, 64));
        assertEquals(18, PathMaterials.count(inventory));
        assertEquals(1, PathMaterials.find(inventory));
        assertEquals(4, inventory.getSelectedSlot());
        int consumed = 0;
        while (PathMaterials.find(inventory) >= 0) {
            inventory.getItem(PathMaterials.find(inventory)).shrink(1);
            consumed++;
            assertEquals(18 - consumed, PathMaterials.count(inventory));
        }
        assertEquals(18, consumed);
        assertEquals(64, inventory.getItem(40).getCount());
        assertEquals(32, inventory.getItem(0).getCount());
    }

    @Test void componentBearingStacksDoNotInflatePlannerBudget() {
        var inventory = inventory();
        ItemStack stack = new ItemStack(Items.COBBLESTONE, 64);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("keep"));
        inventory.setItem(0, stack);
        inventory.setItem(40, new ItemStack(Items.DIRT, 64));
        assertEquals(0, PathMaterials.count(inventory));
        assertEquals(-1, PathMaterials.find(inventory));
    }
}
