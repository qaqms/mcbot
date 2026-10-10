package com.neko.mcbot.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.neko.mcbot.server.BlockActionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class BlockPlacementTest {
    private static final BlockPos POS = new BlockPos(2, 90, -4);
    @BeforeAll static void registriesOnly() { bootstrap(); }

    private Placement access() {
        Placement access = new Placement();
        access.inventory.setItem(13, new ItemStack(Items.COBBLESTONE, 3));
        return access;
    }

    @Test void originalFullStackFaceAndExactTargetCrossThePlacementBoundary() {
        Placement access = access();
        ItemStack original = access.inventory.getItem(13);
        original.set(DataComponents.CUSTOM_NAME, Component.literal("keep"));
        access.inventory.setSelectedSlot(4);
        access.inventory.setItem(4, new ItemStack(Items.IRON_PICKAXE));
        access.inventory.setItem(40, new ItemStack(Items.TORCH, 7));
        var result = BlockPlacement.place(access, POS, 13, Direction.WEST);
        assertTrue(result.ok());
        assertSame(original, access.supplied);
        assertEquals(POS, access.target);
        assertEquals(Direction.WEST, access.face);
        assertEquals(2, original.getCount());
        assertEquals("keep", original.get(DataComponents.CUSTOM_NAME).getString());
        assertEquals(4, access.inventory.getSelectedSlot());
        assertEquals(7, access.inventory.getItem(40).getCount());
        assertTrue(access.inventory.getSelectedItem().is(Items.IRON_PICKAXE));
        assertEquals("minecraft:cobblestone", result.data().get("placed").getAsString());
        assertEquals(1, result.data().get("consumed_count").getAsInt());
        assertFalse(result.feedback().contains("keep"));
    }

    @Test void guardRejectsBeforeReadingWorldOrMutatingInventory() {
        Placement access = access();
        access.denied = new ServerTool.Result(false, "TARGET_LOST:unloaded", null);
        assertSame(access.denied, BlockPlacement.place(access, POS, 13, Direction.UP));
        assertEquals(0, access.calls);
        assertEquals(0, access.reads);
        assertEquals(3, access.inventory.getItem(13).getCount());
    }

    @Test void missingNonBlockEquipmentAndRedirectingItemsNeverInvokePlacement() {
        Placement access = access();
        access.inventory.setItem(40, new ItemStack(Items.COBBLESTONE));
        access.inventory.setItem(10, new ItemStack(Items.STICK));
        access.inventory.setItem(11, new ItemStack(Items.SCAFFOLDING));
        for (int slot : new int[]{-1, 36, 40, 43, 0, 10, 11}) {
            assertFalse(BlockPlacement.place(access, POS, slot, Direction.UP).ok());
        }
        assertEquals(0, access.calls);
        assertEquals(3, access.inventory.getItem(13).getCount());
    }

    @Test void supportedVanillaClassesAreExplicitAndSpecialSignContextsDenied() {
        for (var item : new net.minecraft.world.item.Item[]{Items.COBBLESTONE, Items.RED_BED,
                Items.OAK_DOOR, Items.TORCH}) {
            assertTrue(BlockPlacement.supported((BlockItem) item));
        }
        assertFalse(BlockPlacement.supported((BlockItem) Items.SCAFFOLDING));
        assertFalse(BlockPlacement.supported((BlockItem) Items.OAK_SIGN));
        assertFalse(BlockPlacement.supported((BlockItem) Items.OAK_HANGING_SIGN));
    }

    @Test void vanillaFailureWithNoMutationKeepsOriginalStack() {
        Placement access = access();
        access.result = InteractionResult.FAIL;
        access.after = access.before;
        access.consume = 0;
        ItemStack before = access.inventory.getItem(13).copy();
        var result = BlockPlacement.place(access, POS, 13, Direction.UP);
        assertFalse(result.ok());
        assertFalse(result.data().get("changed").getAsBoolean());
        assertEquals(0, result.data().get("consumed_count").getAsInt());
        assertTrue(ItemStack.matches(before, access.inventory.getItem(13)));
    }

    @Test void misleadingSuccessMustNotHideUnchangedAirOrWrongConsumption() {
        for (int scenario = 0; scenario < 4; scenario++) {
            Placement access = access();
            switch (scenario) {
                case 0 -> access.after = access.before;
                case 1 -> { access.before = Blocks.STONE.defaultBlockState(); access.after = Blocks.AIR.defaultBlockState(); }
                case 2 -> access.consume = 0;
                case 3 -> access.consume = 2;
            }
            var result = BlockPlacement.place(access, POS, 13, Direction.UP);
            assertFalse(result.ok());
            assertTrue(result.feedback().startsWith("PLACE_FAILED:"));
            assertEquals(access.consume, result.data().get("consumed_count").getAsInt());
        }
    }

    @Test void failedActionReportsObservedEffectsWithoutPretendingRollback() {
        Placement access = access();
        access.result = InteractionResult.FAIL;
        var result = BlockPlacement.place(access, POS, 13, Direction.NORTH);
        assertFalse(result.ok());
        assertTrue(result.data().get("changed").getAsBoolean());
        assertEquals(1, result.data().get("consumed_count").getAsInt());
        assertEquals(2, access.inventory.getItem(13).getCount());
        assertTrue(result.feedback().contains("已消耗 1"));
    }

    @Test void observedReplacingWaterAndSlabMergeCanSucceedWithoutAirPrecheck() {
        for (var before : new net.minecraft.world.level.block.state.BlockState[]{
                Blocks.WATER.defaultBlockState(), Blocks.SHORT_GRASS.defaultBlockState(),
                Blocks.OAK_SLAB.defaultBlockState()}) {
            Placement access = access();
            access.before = before;
            access.after = Blocks.OAK_SLAB.defaultBlockState().setValue(
                    net.minecraft.world.level.block.SlabBlock.TYPE, SlabType.DOUBLE);
            access.inventory.setItem(13, new ItemStack(Items.OAK_SLAB, 3));
            assertTrue(BlockPlacement.place(access, POS, 13, Direction.UP).ok());
            assertEquals(2, access.inventory.getItem(13).getCount());
        }
    }
}
