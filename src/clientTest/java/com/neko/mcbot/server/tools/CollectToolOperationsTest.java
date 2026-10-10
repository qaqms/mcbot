package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CollectToolOperationsTest {
    @BeforeAll
    static void registriesOnly() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final class Ground implements CollectTool.GroundItem {
        ItemStack stack;
        boolean discarded;
        boolean eligible = true;
        Ground(ItemStack stack) { this.stack = stack; }
        @Override public ItemStack stack() { return stack; }
        @Override public boolean eligible() { return eligible; }
        @Override public void settle(ItemStack remainder) {
            stack = remainder;
            discarded = remainder.isEmpty();
        }
    }

    private static final class Fixture implements CollectTool.Access {
        final Inventory inventory = new Inventory(null, new EntityEquipment());
        final List<CollectTool.GroundItem> ground = new ArrayList<>();
        final List<String> reads = new ArrayList<>();
        boolean busy;
        BlockPos searched;
        int radius;
        @Override public boolean busy() { reads.add("busy"); return busy; }
        @Override public BlockPos position() { reads.add("position"); return new BlockPos(1, 90, -3); }
        @Override public Container inventory() { reads.add("inventory"); return inventory; }
        @Override public List<CollectTool.GroundItem> items(BlockPos center, int r) {
            reads.add("items");
            searched = center;
            radius = r;
            return ground;
        }
        void fill() {
            for (int slot = 0; slot < 36; slot++) inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
        }
        com.neko.mcbot.server.ServerTool.Result execute(String json) {
            return CollectTool.execute(JsonParser.parseString(json).getAsJsonObject(), this);
        }
    }

    @Test
    void partialPickupReportsFourAndLeavesSixWithOriginalComponentsOnGround() {
        Fixture f = new Fixture();
        f.fill();
        ItemStack incoming = new ItemStack(Items.COBBLESTONE, 10);
        incoming.set(DataComponents.CUSTOM_NAME, Component.literal("sample"));
        f.inventory.setItem(0, incoming.copyWithCount(60));
        Ground entity = new Ground(incoming);
        f.ground.add(entity);
        var receipt = f.execute("{}");
        assertFalse(receipt.ok());
        assertEquals(4, receipt.data().get("collected_count").getAsInt());
        assertEquals(6, receipt.data().get("remaining_count").getAsInt());
        assertTrue(receipt.data().get("partial").getAsBoolean());
        assertEquals(64, f.inventory.getItem(0).getCount());
        assertEquals(6, entity.stack.getCount());
        assertFalse(entity.discarded);
        assertTrue(ItemStack.isSameItemSameComponents(incoming, entity.stack));
        assertTrue(receipt.feedback().contains("捡起 4 个"));
        assertTrue(receipt.feedback().contains("仍有 6 个"));
        assertEquals(70, f.inventory.countItem(Items.COBBLESTONE) + entity.stack.getCount());
        assertEquals(0, f.execute("{}").data().get("collected_count").getAsInt());
        assertEquals(70, f.inventory.countItem(Items.COBBLESTONE) + entity.stack.getCount());
    }

    @Test
    void completePickupDiscardsOnlyWhenAllItemsActuallyEnteredStorage() {
        Fixture f = new Fixture();
        Ground entity = new Ground(new ItemStack(Items.DIAMOND, 10));
        f.ground.add(entity);
        var result = f.execute("{}");
        assertTrue(result.ok());
        assertEquals(10, result.data().get("collected_count").getAsInt());
        assertEquals(0, result.data().get("remaining_count").getAsInt());
        assertTrue(entity.discarded);
        assertEquals(10, f.inventory.countItem(Items.DIAMOND));
    }

    @Test
    void fullStorageLeavesDropAndEmptyEquipmentUntouched() {
        Fixture f = new Fixture();
        f.fill();
        Ground entity = new Ground(new ItemStack(Items.DIAMOND, 10));
        var before = entity.stack.copy();
        f.ground.add(entity);
        var result = f.execute("{}");
        assertFalse(result.ok());
        assertEquals(0, result.data().get("collected_count").getAsInt());
        assertTrue(ItemStack.matches(before, entity.stack));
        assertFalse(entity.discarded);
        for (int slot = 36; slot < f.inventory.getContainerSize(); slot++) assertTrue(f.inventory.getItem(slot).isEmpty());
    }

    @Test
    void oneBlockedDropDoesNotPreventOtherMatchingStacksFromBeingCollected() {
        Fixture f = new Fixture();
        f.fill();
        f.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 60));
        Ground blocked = new Ground(new ItemStack(Items.DIAMOND, 3));
        Ground partial = new Ground(new ItemStack(Items.COBBLESTONE, 10));
        f.ground.add(blocked);
        f.ground.add(partial);
        var result = f.execute("{}");
        assertEquals(4, result.data().get("collected_count").getAsInt());
        assertEquals(9, result.data().get("remaining_count").getAsInt());
        assertEquals(3, blocked.stack.getCount());
        assertEquals(6, partial.stack.getCount());
    }

    @Test
    void busyRejectsBeforeLookingAtBodyInventoryOrGround() {
        Fixture f = new Fixture();
        f.busy = true;
        var result = f.execute("{}");
        assertFalse(result.ok());
        assertTrue(result.feedback().startsWith("BUSY:"));
        assertEquals(List.of("busy"), f.reads);
    }

    @Test
    void malformedCentersAndRadiusNeverReachWorldAccess() {
        Fixture f = new Fixture();
        for (String json : List.of("{\"x\":1}", "{\"y\":90}", "{\"z\":1}", "{\"x\":1,\"y\":90}",
                "{\"x\":\"1\",\"y\":90,\"z\":1}", "{\"x\":1.5,\"y\":90,\"z\":1}",
                "{\"x\":2147483648,\"y\":90,\"z\":1}", "{\"r\":\"3\"}", "{\"r\":true}",
                "{\"r\":null}", "{\"r\":3.1}", "{\"r\":0}", "{\"r\":13}")) {
            var result = f.execute(json);
            assertFalse(result.ok(), json);
            assertTrue(result.feedback().startsWith("DENIED:"), json);
        }
        assertTrue(f.reads.isEmpty());
        assertNull(CollectTool.arguments(null));
    }

    @Test
    void emptyGroundAndDefaultOrExplicitCenterGiveZeroActualPickup() {
        Fixture f = new Fixture();
        assertTrue(f.execute("{}").ok());
        assertEquals(new BlockPos(1, 90, -3), f.searched);
        assertEquals(3, f.radius);
        var result = f.execute("{\"x\":2.0,\"y\":9e1,\"z\":0,\"r\":12}");
        assertTrue(result.ok());
        assertEquals(0, result.data().get("collected_count").getAsInt());
        assertEquals(new BlockPos(2, 90, 0), f.searched);
        assertEquals(12, f.radius);
    }

    @Test
    void largeCustomNamesNeverExpandOrErasePickupReceipt() {
        Fixture f = new Fixture();
        var stack = new ItemStack(Items.DIAMOND, 10);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("X".repeat(40000)));
        f.ground.add(new Ground(stack));
        var result = f.execute("{}");
        assertTrue(result.ok());
        assertTrue(result.feedback().length() < 1000);
        assertTrue(result.feedback().contains("minecraft:diamond"));
        assertTrue(ItemStack.matches(stack, f.inventory.getItem(0)));
    }

    @Test void remoteCenterIsRejectedBeforeGroundOrInventoryAccess() {
        Fixture f = new Fixture();
        assertTrue(f.execute("{\"x\":100,\"y\":90,\"z\":-3}").feedback().startsWith("OUT_OF_REACH:"));
        assertFalse(f.reads.contains("items"));
        assertFalse(f.reads.contains("inventory"));
    }

    @Test void delayedForeignOrOutOfBodyReachItemsAreNotConsumed() {
        Fixture f = new Fixture();
        Ground denied = new Ground(new ItemStack(Items.DIAMOND, 2));
        denied.eligible = false;
        Ground allowed = new Ground(new ItemStack(Items.DIRT, 3));
        f.ground.add(denied);
        f.ground.add(allowed);
        var result = f.execute("{}");
        assertEquals(3, result.data().get("collected_count").getAsInt());
        assertEquals(2, denied.stack.getCount());
        assertFalse(denied.discarded);
        assertTrue(allowed.discarded);
    }

    @Test void liveEligibilityPredicateEnforcesDelayOwnerLoadingAndBothDistances() {
        var body = java.util.UUID.randomUUID();
        assertTrue(CollectTool.eligible(null, body, true, false, true, 1, 1, 3));
        assertTrue(CollectTool.eligible(body, body, true, false, true, 1, 1, 3));
        assertFalse(CollectTool.eligible(java.util.UUID.randomUUID(), body, true, false, true, 1, 1, 3));
        assertFalse(CollectTool.eligible(null, body, false, false, true, 1, 1, 3));
        assertFalse(CollectTool.eligible(null, body, true, true, true, 1, 1, 3));
        assertFalse(CollectTool.eligible(null, body, true, false, false, 1, 1, 3));
        assertFalse(CollectTool.eligible(null, body, true, false, true, 43, 1, 12));
        assertFalse(CollectTool.eligible(null, body, true, false, true, 1, 10, 3));
        assertFalse(CollectTool.eligible(null, body, true, false, true, Double.NaN, 1, 3));
    }
}
