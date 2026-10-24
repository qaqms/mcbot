package com.neko.mcbot.server;

import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MiningToolsTest {
    @BeforeAll static void bootstrap() { BlockActionFixtures.bootstrap(); }

    static ItemStack tool(Item item, Block block, float speed) {
        var stack = new ItemStack(item);
        stack.set(DataComponents.TOOL, new Tool(List.of(
                Tool.Rule.minesAndDrops(HolderSet.direct(block.builtInRegistryHolder()), speed)), 1, 1, true));
        return stack;
    }
    private static MiningTools choices(net.minecraft.world.entity.player.Inventory inventory) {
        return new MiningTools(inventory, stack -> 0, stack -> 1);
    }
    @Test void fasterNearBrokenToolCannotSpendAnotherToolsDurabilityReserve() {
        var inventory = BlockActionFixtures.inventory();
        var fast = tool(Items.DIAMOND_PICKAXE, Blocks.DIAMOND_ORE, 8);
        fast.setDamageValue(fast.getMaxDamage() - 16);
        inventory.setItem(0, fast);
        inventory.setItem(12, tool(Items.IRON_PICKAXE, Blocks.DIAMOND_ORE, 6));
        inventory.setItem(13, tool(Items.DIAMOND_AXE, Blocks.SPRUCE_LOG, 8));
        var tools = choices(inventory);
        var ore = Blocks.DIAMOND_ORE.defaultBlockState();
        assertEquals(0, tools.best(ore).slot());
        assertEquals(12, tools.best(ore, 16).slot());
        assertFalse(MiningTools.usable(fast, 16));
        fast.setDamageValue(fast.getMaxDamage() - 17);
        assertTrue(MiningTools.usable(fast, 16));
        fast.set(DataComponents.TOOL, new Tool(fast.get(DataComponents.TOOL).rules(), 1, 3, true));
        assertFalse(MiningTools.usable(fast, 16), "native per-block durability cost must fit above the reserve");
        inventory.setItem(12, ItemStack.EMPTY);
        assertNull(choices(inventory).best(ore, 16), "the durable axe cannot provide ore harvest eligibility");
    }
    @Test void picksWoodAndStoneToolsFromAllStorageWithoutChangingAnySlotUntilTaken() {
        var inventory = BlockActionFixtures.inventory();
        var axe = tool(Items.STONE_AXE, Blocks.SPRUCE_LOG, 6);
        var pickaxe = tool(Items.STONE_PICKAXE, Blocks.STONE, 6);
        inventory.setItem(0, pickaxe);
        inventory.setItem(24, axe);
        var tools = choices(inventory);
        var log = tools.best(Blocks.SPRUCE_LOG.defaultBlockState());
        assertEquals(24, log.slot());
        assertEquals(0, inventory.getSelectedSlot());
        assertSame(axe, inventory.getItem(24));
        assertEquals(.5, log.seconds(2), 1e-6);
        assertEquals(0, tools.best(Blocks.STONE.defaultBlockState()).slot());
        var result = MiningTools.take(inventory, log);
        assertTrue(result.ok());
        assertTrue(result.data().get("changed").getAsBoolean());
        assertSame(axe, inventory.getSelectedItem());
        assertSame(pickaxe, inventory.getItem(24));
        assertTrue(result.data().getAsJsonObject("held").get("item").getAsString().endsWith("stone_axe"));
    }
    @Test void equalSpeedDoesNotWastePickaxeDurabilityOnLogsAndWorksWithAFullPack() {
        for (boolean full : new boolean[]{false, true}) {
            var inventory = BlockActionFixtures.inventory();
            inventory.setItem(0, tool(Items.STONE_PICKAXE, Blocks.STONE, 6));
            if (full) for (int slot = 1; slot < 36; slot++) inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
            var pick = choices(inventory).best(Blocks.SPRUCE_LOG.defaultBlockState());
            assertNotNull(pick);
            assertFalse(pick.stack().isDamageableItem());
            assertEquals(!full, pick.stack().isEmpty());
            assertTrue(MiningTools.take(inventory, pick).ok());
            assertTrue(inventory.getItem(0).is(Items.STONE_PICKAXE));
        }
    }
    @Test void harvestEligibilityOutranksFasterToolsAndExhaustedToolsAreExcluded() {
        var inventory = BlockActionFixtures.inventory();
        inventory.setItem(0, tool(Items.STONE_AXE, Blocks.SPRUCE_LOG, 20));
        assertNull(choices(inventory).best(Blocks.DIAMOND_ORE.defaultBlockState()));
        var correct = tool(Items.IRON_PICKAXE, Blocks.DIAMOND_ORE, 6);
        inventory.setItem(18, correct);
        assertEquals(18, choices(inventory).best(Blocks.DIAMOND_ORE.defaultBlockState()).slot());
        correct.setDamageValue(correct.getMaxDamage());
        assertNull(choices(inventory).best(Blocks.DIAMOND_ORE.defaultBlockState()));
    }
    @Test void changedCandidateNeverMovesAnotherItemOrRetriesItsSelection() {
        var inventory = BlockActionFixtures.inventory();
        inventory.setItem(12, tool(Items.STONE_AXE, Blocks.SPRUCE_LOG, 6));
        var pick = choices(inventory).best(Blocks.SPRUCE_LOG.defaultBlockState());
        inventory.setItem(12, new ItemStack(Items.DIAMOND));
        var result = MiningTools.take(inventory, pick);
        assertFalse(result.ok());
        assertTrue(inventory.getItem(12).is(Items.DIAMOND));
        assertTrue(inventory.getSelectedItem().isEmpty());
        assertEquals(0, inventory.getSelectedSlot());
    }
    @Test void nativeCandidateAttributesAreEvaluatedOnCopiesAndOnlyApplyOnEffectiveBlocks() {
        var inventory = BlockActionFixtures.inventory();
        var plain = tool(Items.IRON_AXE, Blocks.SPRUCE_LOG, 6);
        var efficient = tool(Items.STONE_AXE, Blocks.SPRUCE_LOG, 4);
        efficient.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY.withModifierAdded(
                Attributes.MINING_EFFICIENCY, new AttributeModifier(Identifier.fromNamespaceAndPath("test", "efficiency"),
                        8, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND));
        inventory.setItem(0, plain);
        inventory.setItem(20, efficient);
        var baseline = new AttributeInstance(Attributes.MINING_EFFICIENCY, ignored -> fail("live mutation"));
        var tools = new MiningTools(inventory, stack -> MiningTools.value(baseline, stack), stack -> .5);
        var log = tools.best(Blocks.SPRUCE_LOG.defaultBlockState());
        assertEquals(20, log.slot());
        assertEquals(6, log.speed(), 1e-6);
        assertEquals(0, baseline.getValue());
        var dirt = tools.best(Blocks.DIRT.defaultBlockState());
        assertTrue(dirt.stack().isEmpty(), "efficiency cannot make an unsuitable tool faster on dirt");
        assertEquals(.5, dirt.speed(), 1e-6);
        assertEquals(0, inventory.getSelectedSlot());
    }
}
