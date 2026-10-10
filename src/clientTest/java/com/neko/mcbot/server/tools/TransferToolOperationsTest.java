package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.LockCode;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TransferToolOperationsTest {
    private static final BlockPos POS = new BlockPos(2, 90, -3);

    @BeforeAll
    static void registriesOnly() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final class Fixture implements TransferTool.Access {
        final Inventory inventory = new Inventory(null, new EntityEquipment());
        Container container = new SimpleContainer(1);
        final List<String> reads = new ArrayList<>();
        boolean busy, locked, machine;
        boolean inBounds = true, loaded = true, valid = true, present = true;
        double distance;
        @Override public boolean busy() { reads.add("busy"); return busy; }
        @Override public boolean inBounds(BlockPos pos) { reads.add("bounds"); return inBounds; }
        @Override public double distanceSquared(BlockPos pos) { reads.add("distance"); return distance; }
        @Override public boolean loaded(BlockPos pos) { reads.add("loaded"); return loaded; }
        @Override public TransferTool.Target target(BlockPos pos) {
            reads.add("target");
            assertEquals(POS, pos);
            return present ? new TransferTool.Target(container, locked, machine, valid, Direction.EAST) : null;
        }
        @Override public Container inventory() { reads.add("inventory"); return inventory; }

        com.neko.mcbot.server.ServerTool.Result execute(String fields) {
            return TransferTool.execute(JsonParser.parseString("{\"x\":2,\"y\":90,\"z\":-3" + fields + "}").getAsJsonObject(), this);
        }

        void fullInventory() {
            for (int slot = 0; slot < 36; slot++) inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
        }

        List<ItemStack> snapshot() {
            List<ItemStack> before = new ArrayList<>();
            for (ItemStack stack : inventory) before.add(stack.copy());
            for (ItemStack stack : container) before.add(stack.copy());
            return before;
        }

        void unchanged(List<ItemStack> before) {
            List<ItemStack> after = snapshot();
            for (int slot = 0; slot < before.size(); slot++) assertTrue(ItemStack.matches(before.get(slot), after.get(slot)));
        }

        void failsUnchanged(String fields, String prefix) {
            var before = snapshot();
            var result = execute(fields);
            assertFalse(result.ok(), result.feedback());
            assertTrue(result.feedback().startsWith(prefix), result.feedback());
            unchanged(before);
        }
    }

    @Test
    void depositingIntoHalfFullContainerReportsOnlyActualMoveAndLeavesRemainder() {
        Fixture f = new Fixture();
        f.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
        f.container.setItem(0, new ItemStack(Items.COBBLESTONE, 60));
        var result = f.execute(",\"dir\":\"in\"");
        assertFalse(result.ok());
        assertEquals(4, result.data().get("moved_items").getAsInt());
        assertEquals(1, result.data().get("moved_stacks").getAsInt());
        assertEquals(6, result.data().get("remaining_items").getAsInt());
        assertTrue(result.data().get("partial").getAsBoolean());
        assertTrue(result.feedback().contains("4 个"));
        assertTrue(result.feedback().contains("6 个"));
        assertEquals(6, f.inventory.getItem(0).getCount());
        assertEquals(64, f.container.getItem(0).getCount());
        var again = f.execute(",\"dir\":\"in\"");
        assertEquals(0, again.data().get("moved_items").getAsInt());
        assertEquals(70, f.inventory.countItem(Items.COBBLESTONE) + f.container.countItem(Items.COBBLESTONE));
    }

    @Test
    void withdrawalIntoFullInventoryWritesPartialRemainderBackToContainer() {
        Fixture f = new Fixture();
        f.fullInventory();
        f.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 60));
        f.container.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
        var result = f.execute(",\"dir\":\"out\"");
        assertFalse(result.ok());
        assertEquals(4, result.data().get("moved_items").getAsInt());
        assertEquals(6, f.container.getItem(0).getCount());
        assertEquals(64, f.inventory.getItem(0).getCount());
        assertEquals(70, f.inventory.countItem(Items.COBBLESTONE) + f.container.countItem(Items.COBBLESTONE));
        assertEquals(0, f.execute("").data().get("moved_items").getAsInt());
    }

    @Test
    void oneItemContainerDoesNotSilentlyLoseNineItems() {
        Fixture f = new Fixture();
        f.container = new SimpleContainer(1) {
            @Override public int getMaxStackSize() { return 1; }
        };
        f.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
        var result = f.execute(",\"dir\":\"in\"");
        assertFalse(result.ok());
        assertEquals(1, result.data().get("moved_items").getAsInt());
        assertEquals(9, f.inventory.getItem(0).getCount());
        assertEquals(1, f.container.getItem(0).getCount());
    }

    @Test
    void rejectedMaterialDoesNotPreventLaterAllowedMaterialFromMoving() {
        Fixture f = new Fixture();
        f.container = new SimpleContainer(1) {
            @Override public boolean canPlaceItem(int slot, ItemStack stack) { return stack.is(Items.DIAMOND); }
        };
        f.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
        f.inventory.setItem(1, new ItemStack(Items.DIAMOND, 3));
        var result = f.execute(",\"dir\":\"in\"");
        assertFalse(result.ok());
        assertEquals(3, result.data().get("moved_items").getAsInt());
        assertEquals(10, result.data().get("remaining_items").getAsInt());
        assertTrue(f.inventory.getItem(1).isEmpty());
        assertEquals(10, f.inventory.getItem(0).getCount());
        assertTrue(f.container.getItem(0).is(Items.DIAMOND));
    }

    @Test
    void itemFilterKeepsOtherStacksAndAllEquipmentAndSelection() {
        Fixture f = new Fixture();
        f.inventory.setSelectedSlot(7);
        f.inventory.setItem(0, new ItemStack(Items.DIAMOND, 3));
        f.inventory.setItem(1, new ItemStack(Items.COBBLESTONE, 10));
        var equipped = new ItemStack(Items.IRON_PICKAXE);
        equipped.setDamageValue(19);
        f.inventory.setItem(40, equipped);
        var result = f.execute(",\"dir\":\"in\",\"item\":\"cobblestone\"");
        assertTrue(result.ok());
        assertEquals(10, result.data().get("moved_items").getAsInt());
        assertEquals(3, f.inventory.getItem(0).getCount());
        assertTrue(ItemStack.matches(equipped, f.inventory.getItem(40)));
        assertEquals(7, f.inventory.getSelectedSlot());
        assertEquals(0, f.execute(",\"dir\":\"in\",\"item\":\"cobblestone\"").data().get("moved_items").getAsInt());
    }

    @Test
    void fullInventoryWithFreeEquipmentStillRejectsWithdrawalWithoutAnyChange() {
        Fixture f = new Fixture();
        f.fullInventory();
        f.container.setItem(0, new ItemStack(Items.DIAMOND, 3));
        var before = f.snapshot();
        var result = f.execute("");
        assertFalse(result.ok());
        assertEquals(0, result.data().get("moved_items").getAsInt());
        assertFalse(result.data().get("partial").getAsBoolean());
        f.unchanged(before);
    }

    @Test
    void completeWithdrawalCountsOriginalQuantityRatherThanClearedSource() {
        Fixture f = new Fixture();
        f.container.setItem(0, new ItemStack(Items.DIAMOND, 3));
        var result = f.execute("");
        assertTrue(result.ok());
        assertEquals(3, result.data().get("moved_items").getAsInt());
        assertTrue(f.container.isEmpty());
        assertEquals(3, f.inventory.countItem(Items.DIAMOND));
    }

    @Test
    void guardsDoNotReadUnloadedTargetsOrTouchInventory() {
        Fixture f = new Fixture();
        f.busy = true;
        f.failsUnchanged("", "BUSY:");
        assertEquals(List.of("busy"), f.reads);
        f.busy = false;
        f.inBounds = false;
        f.reads.clear();
        f.failsUnchanged("", "DENIED:");
        assertEquals(List.of("busy", "bounds"), f.reads);
        f.inBounds = true;
        f.distance = 6.5 * 6.5 + 0.001;
        f.reads.clear();
        f.failsUnchanged("", "OUT_OF_REACH:");
        assertEquals(List.of("busy", "bounds", "distance"), f.reads);
        f.distance = 6.5 * 6.5;
        f.loaded = false;
        f.reads.clear();
        f.failsUnchanged("", "TARGET_LOST:");
        assertEquals(List.of("busy", "bounds", "distance", "loaded"), f.reads);
    }

    @Test
    void locksMachinesMissingAndInvalidContainersRejectBeforeInventoryAccess() {
        Fixture f = new Fixture();
        for (String dir : List.of("in", "out")) {
            f.locked = true;
            f.failsUnchanged(",\"dir\":\"" + dir + "\"", "DENIED:");
            f.locked = false;
            f.machine = true;
            f.failsUnchanged(",\"dir\":\"" + dir + "\"", "DENIED:");
            f.machine = false;
            f.valid = false;
            f.failsUnchanged(",\"dir\":\"" + dir + "\"", "DENIED:");
            f.valid = true;
            f.present = false;
            f.failsUnchanged(",\"dir\":\"" + dir + "\"", "TARGET_LOST:");
            f.present = true;
        }
        assertFalse(f.reads.contains("inventory"));
    }

    @Test
    void realChestLockDetectionUsesLockComponentWithoutWorld() {
        ChestBlockEntity chest = new ChestBlockEntity(POS, Blocks.CHEST.defaultBlockState());
        assertFalse(TransferTool.locked(chest));
        var components = net.minecraft.core.component.DataComponentMap.builder()
                .set(DataComponents.LOCK, new LockCode(net.minecraft.advancements.criterion.ItemPredicate.Builder.item()
                        .of(net.minecraft.core.registries.BuiltInRegistries.ITEM, Items.DIAMOND).build())).build();
        chest.applyComponents(components, net.minecraft.core.component.DataComponentPatch.EMPTY);
        assertTrue(TransferTool.locked(chest));
    }

    @Test
    void invalidTypesCoordinatesDirectionAndItemAreRejectedBeforeAccess() {
        Fixture f = new Fixture();
        for (String json : List.of(
                "{}", "{\"x\":2,\"y\":90}", "{\"x\":\"2\",\"y\":90,\"z\":-3}",
                "{\"x\":2.1,\"y\":90,\"z\":-3}", "{\"x\":2147483648,\"y\":90,\"z\":-3}",
                "{\"x\":true,\"y\":90,\"z\":-3}", "{\"x\":2,\"y\":90,\"z\":-3,\"dir\":\"sideways\"}",
                "{\"x\":2,\"y\":90,\"z\":-3,\"dir\":[]}", "{\"x\":2,\"y\":90,\"z\":-3,\"item\":true}",
                "{\"x\":2,\"y\":90,\"z\":-3,\"item\":\"invalid::id\"}",
                "{\"x\":2,\"y\":90,\"z\":-3,\"item\":\"offline:missing\"}")) {
            JsonObject args = JsonParser.parseString(json).getAsJsonObject();
            assertFalse(TransferTool.execute(args, f).ok());
        }
        assertTrue(f.reads.isEmpty());
        assertNull(TransferTool.arguments(null));
        assertNotNull(TransferTool.arguments(JsonParser.parseString("{\"x\":2.0,\"y\":9e1,\"z\":-3}").getAsJsonObject()));
    }

    @Test
    void bodyPositionSelectsAConsistentContainerFace() {
        assertEquals(Direction.UP, TransferTool.facing(1, 3, 0));
        assertEquals(Direction.DOWN, TransferTool.facing(0, -3, 1));
        assertEquals(Direction.EAST, TransferTool.facing(3, 1, 0));
        assertEquals(Direction.WEST, TransferTool.facing(-3, 1, 0));
        assertEquals(Direction.SOUTH, TransferTool.facing(0, 1, 3));
        assertEquals(Direction.NORTH, TransferTool.facing(0, 1, -3));
    }
}
