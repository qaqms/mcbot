package com.neko.mcbot.server;

import com.neko.mcbot.common.WireSize;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.neko.mcbot.server.BlockActionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class BlockMiningTest {
    @BeforeAll static void registriesOnly() { bootstrap(); }

    @Test void wrongHarvestToolStopsBeforeCracksLootOrDurability() {
        Mining access = new Mining();
        access.correct = false;
        ItemStack before = access.held().copy();
        var result = new BlockMining(access).tick();
        assertFalse(result.ok());
        assertTrue(result.feedback().startsWith("WRONG_TOOL:"));
        assertFalse(result.data().get("removed").getAsBoolean());
        assertEquals(0, access.destroys);
        assertTrue(access.cracks.isEmpty());
        assertTrue(ItemStack.matches(before, access.held()));
        assertTrue(access.state.is(Blocks.STONE));
    }

    @Test void guardFailureDoesNotReadWorldAndGetsStableFailureData() {
        Mining access = new Mining();
        access.denied = new ServerTool.Result(false, "TARGET_LOST:unloaded", null);
        BlockMining mining = new BlockMining(access);
        var result = mining.tick();
        assertFalse(result.data().get("removed").getAsBoolean());
        assertSame(result, mining.tick());
        assertEquals(0, access.reads);
        assertEquals(0, access.destroys);
    }

    @Test void airFluidUnbreakableAndRestrictedTargetsCannotBeDestroyed() {
        for (int scenario = 0; scenario < 4; scenario++) {
            Mining access = new Mining();
            switch (scenario) {
                case 0 -> access.state = Blocks.AIR.defaultBlockState();
                case 1 -> access.state = Blocks.WATER.defaultBlockState();
                case 2 -> access.hardness = -1;
                case 3 -> access.permitted = false;
            }
            assertFalse(new BlockMining(access).tick().ok());
            assertEquals(0, access.destroys);
        }
    }

    @Test void changingTargetStopsAndClearsExistingCrack() {
        Mining access = new Mining();
        BlockMining mining = new BlockMining(access);
        assertNull(mining.tick());
        access.state = Blocks.DIRT.defaultBlockState();
        assertTrue(mining.tick().feedback().startsWith("TARGET_LOST:"));
        assertEquals(-1, access.cracks.getLast());
        assertEquals(0, access.destroys);
    }

    @Test void changingSlotCountDamageOrComponentsStopsBeforeDestroy() {
        for (int scenario = 0; scenario < 4; scenario++) {
            Mining access = new Mining();
            BlockMining mining = new BlockMining(access);
            assertNull(mining.tick());
            switch (scenario) {
                case 0 -> access.inventory.setSelectedSlot(1);
                case 1 -> access.held().grow(1);
                case 2 -> access.held().setDamageValue(5);
                case 3 -> access.held().set(DataComponents.CUSTOM_NAME, Component.literal("changed"));
            }
            assertTrue(mining.tick().feedback().startsWith("WRONG_TOOL:"));
            assertEquals(-1, access.cracks.getLast());
            assertEquals(0, access.destroys);
        }
    }

    @Test void preflightAndTicksUseDifferentReachAndLiveProgress() {
        Mining access = new Mining();
        BlockMining mining = new BlockMining(access);
        assertNull(mining.preflight(5.5));
        assertNull(mining.tick());
        access.gain = .75f;
        assertTrue(mining.tick().ok());
        assertEquals(java.util.List.of(5.5, 6.5, 6.5), access.reaches);
        assertEquals(java.util.List.of(2, 9, -1), access.cracks);
        assertEquals(1, access.destroys);
    }

    @Test void zeroHardnessPositiveInfinityFinishesOnce() {
        Mining access = new Mining();
        access.hardness = 0;
        access.gain = Float.POSITIVE_INFINITY;
        BlockMining mining = new BlockMining(access);
        var result = mining.tick();
        assertTrue(result.ok());
        assertSame(result, mining.tick());
        assertEquals(1, access.destroys);
    }

    @Test void invalidProgressStopsAfterBoundedStallWithoutDestroy() {
        for (float gain : new float[]{0, -1, Float.NaN, Float.NEGATIVE_INFINITY}) {
            Mining access = new Mining();
            access.gain = gain;
            BlockMining mining = new BlockMining(access);
            for (int i = 0; i < 9; i++) assertNull(mining.tick());
            assertFalse(mining.tick().ok());
            assertEquals(0, access.destroys);
        }
    }

    @Test void abortClearsCrackAndNeverRunsActionOnLaterTicks() {
        Mining access = new Mining();
        BlockMining mining = new BlockMining(access);
        assertNull(mining.tick());
        mining.abort();
        mining.abort();
        assertTrue(mining.tick().feedback().startsWith("CANCELLED:"));
        assertEquals(java.util.List.of(2, -1), access.cracks);
        assertEquals(0, access.destroys);
    }

    @Test void reportedSuccessWithoutActualRemovalCannotAwardLoot() {
        Mining access = new Mining();
        access.gain = 1;
        Drop emitted = new Drop(new ItemStack(Items.DIAMOND, 3));
        access.action = () -> access.drops.add(emitted);
        var result = new BlockMining(access).tick();
        assertTrue(result.feedback().startsWith("BREAK_FAILED:"));
        assertFalse(result.data().get("removed").getAsBoolean());
        assertEquals(0, access.inventory.countItem(Items.DIAMOND));
        assertEquals(0, emitted.settlements);
    }

    @Test void reportedFailureWithObservedRemovalStillReportsRealSideEffects() {
        Mining access = new Mining();
        access.gain = 1;
        access.reported = false;
        access.action = () -> {
            access.state = Blocks.AIR.defaultBlockState();
            access.drops.add(new Drop(new ItemStack(Items.COBBLESTONE, 2)));
        };
        var result = new BlockMining(access).tick();
        assertFalse(result.ok());
        assertTrue(result.data().get("removed").getAsBoolean());
        assertFalse(result.data().get("reported_success").getAsBoolean());
        assertEquals(2, result.data().get("collected_count").getAsInt());
        assertEquals(2, access.inventory.countItem(Items.COBBLESTONE));
    }

    @Test void actionMutatesOriginalToolAndIsNotInvokedAgainForLootOrDamage() {
        Mining access = new Mining();
        access.gain = 1;
        ItemStack original = access.held();
        original.setDamageValue(17);
        original.set(DataComponents.CUSTOM_NAME, Component.literal("keep"));
        access.inventory.setItem(40, new ItemStack(Items.TORCH, 7));
        access.action = () -> {
            access.held().setDamageValue(access.held().getDamageValue() + 1);
            access.state = Blocks.AIR.defaultBlockState();
        };
        BlockMining mining = new BlockMining(access);
        assertTrue(mining.tick().ok());
        mining.tick();
        assertSame(original, access.held());
        assertEquals(18, original.getDamageValue());
        assertEquals("keep", original.get(DataComponents.CUSTOM_NAME).getString());
        assertEquals(7, access.inventory.getItem(40).getCount());
        assertEquals(0, access.selectedSlot());
        assertEquals(1, access.destroys);
    }

    @Test void oldDropsStayUntouchedAndNewPartialRemainderKeepsAllComponents() {
        Mining access = new Mining();
        access.gain = 1;
        ItemStack sample = new ItemStack(Items.COBBLESTONE, 10);
        sample.set(DataComponents.CUSTOM_NAME, Component.literal("sample"));
        for (int i = 1; i < 36; i++) access.inventory.setItem(i, new ItemStack(Items.DIRT, 64));
        access.inventory.setItem(1, sample.copyWithCount(60));
        Drop old = new Drop(sample.copyWithCount(5));
        Drop fresh = new Drop(sample);
        access.drops.add(old);
        access.action = () -> {
            access.state = Blocks.AIR.defaultBlockState();
            access.drops.add(fresh);
            access.drops.add(fresh); // Duplicate observation must not double-settle an entity.
        };
        BlockMining mining = new BlockMining(access);
        var result = mining.tick();
        assertFalse(result.ok());
        assertTrue(result.data().get("removed").getAsBoolean());
        assertEquals(4, result.data().get("collected_count").getAsInt());
        assertEquals(6, result.data().get("remaining_count").getAsInt());
        assertEquals(0, old.settlements);
        assertEquals(5, old.stack.getCount());
        assertEquals(1, fresh.settlements);
        assertTrue(ItemStack.matches(sample.copyWithCount(6), fresh.stack));
        assertTrue(ItemStack.matches(sample.copyWithCount(64), access.inventory.getItem(1)));
        assertEquals(75, old.stack.getCount() + fresh.stack.getCount() + access.inventory.countItem(Items.COBBLESTONE));
        assertTrue(result.feedback().contains("minecraft:cobblestone"));
        assertFalse(result.feedback().contains("sample"));
        assertSame(result, mining.tick());
        assertEquals(1, fresh.settlements);
    }

    @Test void fullCollectionEmptiesOriginalDropWithoutRespawningAnything() {
        Mining access = new Mining();
        access.gain = 1;
        Drop fresh = new Drop(new ItemStack(Items.COBBLESTONE, 4));
        access.action = () -> {
            access.state = Blocks.AIR.defaultBlockState();
            access.drops.add(fresh);
        };
        var result = new BlockMining(access).tick();
        assertTrue(result.ok());
        assertEquals(4, result.data().get("collected_count").getAsInt());
        assertTrue(fresh.stack.isEmpty());
        assertEquals(1, fresh.settlements);
        assertEquals(1, access.drops.size());
    }

    @Test void largeCustomNamesAndManyDropsCannotOverflowFeedback() {
        Mining access = new Mining();
        access.gain = 1;
        access.action = () -> {
            access.state = Blocks.AIR.defaultBlockState();
            for (int i = 0; i < 40; i++) {
                ItemStack stack = new ItemStack(Items.COBBLESTONE);
                stack.set(DataComponents.CUSTOM_NAME, Component.literal("x".repeat(40_000) + i));
                access.drops.add(new Drop(stack));
            }
        };
        var result = new BlockMining(access).tick();
        assertEquals(35, result.data().get("collected_count").getAsInt());
        assertEquals(5, result.data().get("remaining_count").getAsInt());
        assertEquals(32, result.data().getAsJsonArray("collected").size());
        assertTrue(result.feedback().contains("最多32组"));
        assertTrue(WireSize.utf8Bytes(result.feedback()) < 12_000);
    }
}
