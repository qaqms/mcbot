package com.neko.mcbot.server;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EquipmentActionsTest {
    @BeforeAll static void bootstrap() { BlockActionFixtures.bootstrap(); }
    static class Access implements EquipmentActions.Access, EquipmentActions.Armor {
        final Inventory inventory = BlockActionFixtures.inventory();
        ItemStack worn = ItemStack.EMPTY;
        boolean accepts = true;
        int puts;
        @Override public Inventory inventory() { return inventory; }
        @Override public EquipmentActions.Armor armor(String target) { return target.equals("head") ? this : null; }
        @Override public ItemStack worn() { return worn; }
        @Override public boolean accepts(ItemStack stack) { return accepts; }
        @Override public void put(ItemStack stack) { puts++; worn = stack; }
    }
    @Test void oldMainHandSelectionStillMovesWholeStacksAndTheirComponents() {
        var a = new Access();
        var tool = new ItemStack(Items.IRON_PICKAXE);
        tool.setDamageValue(17);
        var source = new ItemStack(Items.BREAD, 12);
        source.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("kept"));
        a.inventory.setItem(0, tool.copy());
        a.inventory.setItem(13, source.copy());
        assertTrue(EquipmentActions.equip(a, 13, "mainhand").ok());
        assertTrue(ItemStack.matches(tool, a.inventory.getItem(13)));
        assertTrue(ItemStack.matches(source, a.inventory.getSelectedItem()));
        assertEquals(0, a.inventory.getSelectedSlot());
    }
    @Test void fillingEmptyArmorUsesOneRealItemWithFullInventoryAndLeavesSelectedHand() {
        var a = new Access();
        for (int i = 0; i < 36; i++) a.inventory.setItem(i, new ItemStack(Items.STONE, 64));
        a.inventory.setItem(13, new ItemStack(Items.IRON_HELMET));
        var result = EquipmentActions.equip(a, 13, "head");
        assertTrue(result.ok(), result.feedback());
        assertEquals(1, a.worn.getCount());
        assertTrue(a.worn.is(Items.IRON_HELMET));
        assertTrue(a.inventory.getItem(13).isEmpty());
        assertTrue(a.inventory.getSelectedItem().is(Items.STONE));
        assertEquals(0, result.data().get("consumed_count").getAsInt());
    }
    @Test void wrongNativeQualificationOccupiedSlotAndMissingStockAreZeroActionRefusals() {
        for (int mode = 0; mode < 3; mode++) {
            var a = new Access();
            if (mode != 2) a.inventory.setItem(13, new ItemStack(Items.IRON_HELMET));
            if (mode == 0) a.accepts = false;
            if (mode == 1) a.worn = new ItemStack(Items.LEATHER_HELMET);
            var before = a.inventory.getItem(13).copy();
            var result = EquipmentActions.equip(a, 13, "head");
            assertFalse(result.ok());
            assertFalse(result.data().get("changed").getAsBoolean());
            assertEquals(0, a.puts);
            assertTrue(ItemStack.matches(before, a.inventory.getItem(13)));
        }
    }
    @Test void restorationNeverOverwritesAChangedStashedToolOrDifferentSelectedSlot() {
        for (boolean changedSelection : new boolean[]{true, false}) {
            var inventory = BlockActionFixtures.inventory();
            inventory.setItem(0, new ItemStack(Items.IRON_PICKAXE));
            inventory.setItem(13, new ItemStack(Items.BREAD, 3));
            var lease = new EquipmentActions.HandLease(inventory, 13);
            var expected = inventory.getSelectedItem().copy();
            if (changedSelection) inventory.setSelectedSlot(2);
            else inventory.setItem(13, new ItemStack(Items.DIAMOND));
            assertFalse(lease.restore(expected));
            assertFalse(lease.restore(expected));
            assertEquals(changedSelection ? 2 : 0, inventory.getSelectedSlot());
            if (!changedSelection) assertTrue(inventory.getItem(13).is(Items.DIAMOND));
        }
    }
    @Test void automaticStockDoesNotTreatNearlyBrokenEquipmentAsAnUpgradeCandidate() {
        var spare = new ItemStack(Items.IRON_HELMET);
        spare.setDamageValue(20);
        assertTrue(MaintenanceDriver.ordinary(spare));
        spare.setDamageValue(spare.getMaxDamage() - 1);
        assertFalse(MaintenanceDriver.ordinary(spare));
    }
}
