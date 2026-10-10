package com.neko.mcbot.server;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ItemTransfersTest {
    @BeforeAll
    static void registriesOnly() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static Inventory inventory() {
        // getItem/setItem/setChanged need no Player. No entity or world is constructed.
        return new Inventory(null, new EntityEquipment());
    }

    private static void fillStorage(Container inventory) {
        for (int slot = 0; slot < 36; slot++) inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
    }

    private static ItemStack named(int count, String name) {
        ItemStack stack = new ItemStack(Items.COBBLESTONE, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    private static List<ItemStack> snapshot(Container container) {
        List<ItemStack> stacks = new ArrayList<>();
        for (ItemStack stack : container) stacks.add(stack.copy());
        return stacks;
    }

    private static void unchanged(List<ItemStack> before, Container after) {
        for (int slot = 0; slot < before.size(); slot++) {
            assertTrue(ItemStack.matches(before.get(slot), after.getItem(slot)), "slot " + slot);
        }
    }

    @Test
    void partialMoveKeepsSixAtSourceAndRepeatedAttemptsCannotDuplicate() {
        SimpleContainer source = new SimpleContainer(new ItemStack(Items.COBBLESTONE, 10));
        SimpleContainer destination = new SimpleContainer(new ItemStack(Items.COBBLESTONE, 60));
        var movement = ItemTransfers.move(source, 0, destination, 1, null);
        assertEquals(4, movement.moved());
        assertEquals(6, source.getItem(0).getCount());
        assertEquals(64, destination.getItem(0).getCount());
        assertEquals(70, source.countItem(Items.COBBLESTONE) + destination.countItem(Items.COBBLESTONE));
        assertEquals(0, ItemTransfers.move(source, 0, destination, 1, null).moved());
        assertEquals(70, source.countItem(Items.COBBLESTONE) + destination.countItem(Items.COBBLESTONE));
    }

    @Test
    void capacityMatrixConservesCountsAndCompleteComponents() {
        for (int limit : new int[]{1, 8, 64}) {
            for (int occupied : new int[]{0, 1, limit}) {
                for (int count : new int[]{1, 10, 64}) {
                    SimpleContainer source = new SimpleContainer(named(count, "sample"));
                    SimpleContainer destination = new SimpleContainer(1) {
                        @Override public int getMaxStackSize() { return limit; }
                    };
                    if (occupied > 0) destination.setItem(0, named(occupied, "sample"));
                    var moved = ItemTransfers.move(source, 0, destination, 1, null);
                    int expected = Math.min(count, limit - occupied);
                    assertEquals(expected, moved.moved());
                    assertEquals(count - expected, source.getItem(0).getCount());
                    assertEquals(occupied + expected, destination.getItem(0).getCount());
                    assertEquals(count + occupied, source.countItem(Items.COBBLESTONE) + destination.countItem(Items.COBBLESTONE));
                    for (ItemStack stack : List.of(source.getItem(0), destination.getItem(0))) {
                        if (!stack.isEmpty()) assertTrue(ItemStack.isSameItemSameComponents(named(1, "sample"), stack));
                    }
                }
            }
        }
    }

    @Test
    void slotRejectionAndExtractionRejectionLeaveBothEndpointsUntouched() {
        SimpleContainer source = new SimpleContainer(new ItemStack(Items.COBBLESTONE, 10));
        SimpleContainer destination = new SimpleContainer(2) {
            @Override public boolean canPlaceItem(int slot, ItemStack stack) { return false; }
        };
        var beforeSource = snapshot(source);
        var beforeDestination = snapshot(destination);
        assertEquals(0, ItemTransfers.move(source, 0, destination, 2, null).moved());
        unchanged(beforeSource, source);
        unchanged(beforeDestination, destination);
        SimpleContainer protectedSource = new SimpleContainer(new ItemStack(Items.COBBLESTONE, 10)) {
            @Override public boolean canTakeItem(Container target, int slot, ItemStack stack) { return false; }
        };
        SimpleContainer empty = new SimpleContainer(1);
        assertEquals(0, ItemTransfers.move(protectedSource, 0, empty, 1, null).moved());
        assertEquals(10, protectedSource.getItem(0).getCount());
        assertTrue(empty.isEmpty());
    }

    @Test
    void existingCompatibleStackIsUsedBeforeAnEarlierEmptySlot() {
        SimpleContainer target = new SimpleContainer(3);
        target.setItem(1, new ItemStack(Items.COBBLESTONE, 60));
        var input = new ItemStack(Items.COBBLESTONE, 7);
        var result = ItemTransfers.insert(target, 3, input, null);
        assertEquals(7, result.moved());
        assertEquals(64, target.getItem(1).getCount());
        assertEquals(3, target.getItem(0).getCount());
        assertTrue(result.remainder().isEmpty());
        assertEquals(7, input.getCount());
    }

    @Test
    void differentComponentsStaySeparateAndDamageSurvivesMove() {
        SimpleContainer source = new SimpleContainer(named(10, "A"));
        SimpleContainer destination = new SimpleContainer(named(60, "B"));
        assertEquals(0, ItemTransfers.move(source, 0, destination, 1, null).moved());
        assertTrue(ItemStack.matches(named(10, "A"), source.getItem(0)));
        assertTrue(ItemStack.matches(named(60, "B"), destination.getItem(0)));
        var tool = new ItemStack(Items.IRON_PICKAXE);
        tool.setDamageValue(27);
        tool.set(DataComponents.CUSTOM_NAME, Component.literal("tool"));
        source.setItem(0, tool);
        destination.setItem(0, ItemStack.EMPTY);
        assertEquals(1, ItemTransfers.move(source, 0, destination, 1, null).moved());
        assertTrue(ItemStack.matches(tool, destination.getItem(0)));
        assertTrue(source.isEmpty());
    }

    @Test
    void itemComponentStackLimitIsRespectedEvenIfContainerAllowsMore() {
        ItemStack input = new ItemStack(Items.COBBLESTONE, 10);
        input.set(DataComponents.MAX_STACK_SIZE, 4);
        SimpleContainer target = new SimpleContainer(2);
        var result = ItemTransfers.insert(target, 2, input, null);
        assertEquals(8, result.moved());
        assertEquals(2, result.remainder().getCount());
        assertEquals(4, target.getItem(0).getCount());
        assertEquals(4, target.getItem(1).getCount());
        assertTrue(ItemStack.isSameItemSameComponents(input, result.remainder()));
    }

    @Test
    void oversizedExistingStackIsNeverShrunkOrUsedAsNegativeCapacity() {
        Inventory target = inventory();
        fillStorage(target);
        target.setItem(0, new ItemStack(Items.COBBLESTONE, 70));
        var before = snapshot(target);
        var result = ItemTransfers.insert(target, 36, new ItemStack(Items.COBBLESTONE, 10), null);
        assertEquals(0, result.moved());
        assertEquals(10, result.remainder().getCount());
        unchanged(before, target);
    }

    @Test
    void receivingPartialDropsSettlesOnlyTheRemainderAndCountsActualInventoryGain() {
        for (int room : new int[]{0, 4, 10}) {
            Inventory target = inventory();
            fillStorage(target);
            target.setItem(0, named(64 - room, "drop"));
            ItemStack drop = named(10, "drop");
            List<ItemStack> settled = new ArrayList<>();
            var movement = ItemTransfers.receive(target, drop, settled::add);
            assertEquals(room, movement.moved());
            assertEquals(1, settled.size());
            assertEquals(10 - room, settled.getFirst().getCount());
            assertEquals(74 - room, target.countItem(Items.COBBLESTONE) + settled.getFirst().getCount());
            assertTrue(ItemStack.matches(named(10, "drop"), drop), "loot input is not mutated");
            if (!settled.getFirst().isEmpty()) {
                assertTrue(ItemStack.isSameItemSameComponents(drop, settled.getFirst()));
            }
        }
    }

    @Test
    void receivingIntoRealInventoryKeepsSelectedSlotAndAllEquipment() {
        Inventory target = inventory();
        target.setSelectedSlot(7);
        for (int slot = 36; slot < target.getContainerSize(); slot++) target.setItem(slot, named(2, "gear" + slot));
        var before = snapshot(target);
        var input = new ItemStack(Items.COBBLESTONE, 10);
        var received = ItemTransfers.receive(target, input, remainder -> assertTrue(remainder.isEmpty()));
        assertEquals(10, received.moved());
        assertEquals(7, target.getSelectedSlot());
        assertTrue(target.getTimesChanged() > 0);
        for (int slot = 36; slot < target.getContainerSize(); slot++) {
            assertTrue(ItemStack.matches(before.get(slot), target.getItem(slot)));
        }
    }

    @Test
    void fullStorageNeverBorrowsEmptyEquipmentSlots() {
        Inventory target = inventory();
        fillStorage(target);
        var before = snapshot(target);
        var result = ItemTransfers.receive(target, new ItemStack(Items.COBBLESTONE, 10), rest -> assertEquals(10, rest.getCount()));
        assertEquals(0, result.moved());
        assertEquals(0, target.getTimesChanged());
        unchanged(before, target);
    }

    @Test
    void emptyInputAndSameContainerMoveAreNoops() {
        Inventory target = inventory();
        assertEquals(0, ItemTransfers.insert(target, 36, ItemStack.EMPTY, null).moved());
        target.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
        var before = snapshot(target);
        assertEquals(0, ItemTransfers.move(target, 0, target, 36, null).moved());
        unchanged(before, target);
    }

    private static class Sided extends SimpleContainer implements WorldlyContainer {
        boolean allowIn = true;
        boolean allowOut = true;
        Sided() { super(2); }
        @Override public int[] getSlotsForFace(Direction face) {
            return face == Direction.EAST ? new int[]{1} : new int[0];
        }
        @Override public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction face) { return allowIn; }
        @Override public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction face) { return allowOut; }
    }

    @Test
    void sidedInsertionRequiresExposedSlotAndPermissionOnTheChosenFace() {
        SimpleContainer source = new SimpleContainer(new ItemStack(Items.COBBLESTONE, 10));
        Sided target = new Sided();
        assertEquals(0, ItemTransfers.move(source, 0, target, 2, null).moved());
        assertEquals(0, ItemTransfers.move(source, 0, target, 2, Direction.WEST).moved());
        target.allowIn = false;
        assertEquals(0, ItemTransfers.move(source, 0, target, 2, Direction.EAST).moved());
        target.allowIn = true;
        assertEquals(10, ItemTransfers.move(source, 0, target, 2, Direction.EAST).moved());
        assertTrue(target.getItem(0).isEmpty());
        assertEquals(10, target.getItem(1).getCount());
    }

    @Test
    void sidedExtractionCannotTakeHiddenOrProtectedSlots() {
        Sided source = new Sided();
        source.setItem(0, new ItemStack(Items.COBBLESTONE, 5));
        source.setItem(1, new ItemStack(Items.COBBLESTONE, 10));
        SimpleContainer target = new SimpleContainer(1);
        assertEquals(0, ItemTransfers.move(source, 0, target, 1, Direction.EAST).moved());
        source.allowOut = false;
        assertEquals(0, ItemTransfers.move(source, 1, target, 1, Direction.EAST).moved());
        source.allowOut = true;
        assertEquals(10, ItemTransfers.move(source, 1, target, 1, Direction.EAST).moved());
        assertEquals(5, source.getItem(0).getCount());
    }
}
