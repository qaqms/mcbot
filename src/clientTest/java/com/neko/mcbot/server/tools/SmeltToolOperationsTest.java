package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.common.WireSize;
import com.neko.mcbot.server.ServerTool;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SmeltToolOperationsTest {
    private static final BlockPos POS = new BlockPos(2, 90, -3);

    @BeforeAll
    static void registriesOnly() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // No server, world, fake player, ticker or Mixin runtime is created by this fixture.
    private static final class Fixture implements SmeltTool.Access, SmeltTool.Machine {
        int inventoryLimit = 64;
        final SimpleContainer inventory = new SimpleContainer(43) {
            @Override public int getMaxStackSize() { return inventoryLimit; }
        };
        final SimpleContainer slots = new SimpleContainer(3) {
            @Override public boolean canPlaceItem(int slot, ItemStack stack) {
                return slot == 0 || slot == 1 && (burnTicks(stack) > 0 || stack.is(Items.BUCKET));
            }
        };
        final List<String> reads = new ArrayList<>();
        final int[] counters = {0, 0, 0, 200};
        boolean busy;
        boolean inBounds = true;
        boolean loaded = true;
        boolean present = true;
        boolean locked;
        boolean ticking = true;
        double distanceSquared;
        String machineId = "minecraft:furnace";
        Item ingredient = Items.RAW_IRON;
        ItemStack result = new ItemStack(Items.IRON_INGOT);
        int recipeTicks = 200;
        String recipeId = "offline:iron_heat";

        @Override public boolean busy() { reads.add("busy"); return busy; }
        @Override public boolean inBounds(BlockPos pos) { reads.add("bounds"); return inBounds; }
        @Override public double distanceSquared(BlockPos pos) { reads.add("distance"); return distanceSquared; }
        @Override public boolean loaded(BlockPos pos) { reads.add("loaded"); return loaded; }
        @Override public SmeltTool.Machine machine(BlockPos pos) {
            reads.add("machine");
            assertEquals(POS, pos);
            return present ? this : null;
        }
        @Override public Container inventory() { reads.add("inventory"); return inventory; }
        @Override public Container slots() { return slots; }
        @Override public BlockPos pos() { return POS; }
        @Override public String id() { return machineId; }
        @Override public boolean locked() { reads.add("locked"); return locked; }
        @Override public boolean ticking() { return ticking; }
        @Override public int counter(int index) { return counters[index]; }
        @Override public int burnTicks(ItemStack fuel) {
            int ticks = fuel.is(Items.COAL) ? 1600 : fuel.is(Items.LAVA_BUCKET) ? 20000
                    : fuel.is(Items.OAK_LOG) ? 300 : 0;
            return machineId.equals("minecraft:furnace") ? ticks : ticks / 2;
        }
        @Override public SmeltTool.Cooking recipe(ItemStack input) {
            if (input.isEmpty() || !input.is(ingredient)) return null;
            Ingredient materials = Ingredient.of(ingredient);
            AbstractCookingRecipe recipe = switch (machineId) {
                case "minecraft:blast_furnace" ->
                        new BlastingRecipe("", CookingBookCategory.MISC, materials, result, 0, recipeTicks);
                case "minecraft:smoker" ->
                        new SmokingRecipe("", CookingBookCategory.FOOD, materials, result, 0, recipeTicks);
                default -> new SmeltingRecipe("", CookingBookCategory.MISC, materials, result, 0, recipeTicks);
            };
            var holder = new RecipeHolder<>(ResourceKey.create(Registries.RECIPE, Identifier.parse(recipeId)), recipe);
            return SmeltTool.cooking(holder, input, RegistryAccess.EMPTY, FeatureFlags.VANILLA_SET);
        }

        ServerTool.Result execute(String fields) {
            JsonObject args = JsonParser.parseString("{\"x\":2,\"y\":90,\"z\":-3" + fields + "}").getAsJsonObject();
            return SmeltTool.execute(args, this);
        }

        List<ItemStack> snapshot() {
            List<ItemStack> copy = new ArrayList<>();
            for (ItemStack stack : inventory) copy.add(stack.copy());
            for (ItemStack stack : slots) copy.add(stack.copy());
            return copy;
        }

        void unchanged(List<ItemStack> before) {
            List<ItemStack> after = snapshot();
            assertEquals(before.size(), after.size());
            for (int slot = 0; slot < before.size(); slot++) {
                assertTrue(ItemStack.matches(before.get(slot), after.get(slot)), "changed slot " + slot);
            }
        }

        void failsUnchanged(String fields, String prefix) {
            var before = snapshot();
            int[] times = counters.clone();
            var receipt = execute(fields);
            assertFalse(receipt.ok(), receipt.feedback());
            assertTrue(receipt.feedback().startsWith(prefix), receipt.feedback());
            unchanged(before);
            assertArrayEquals(times, counters);
        }

        void stock() {
            inventory.setItem(0, new ItemStack(Items.RAW_IRON, 8));
            inventory.setItem(1, new ItemStack(Items.COAL, 4));
        }
    }

    private static ItemStack named(Item item, int count, String name) {
        ItemStack stack = new ItemStack(item, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    @Test
    void vanillaMachineIdentityRejectsSubclasses() {
        assertTrue(SmeltTool.supported(new FurnaceBlockEntity(POS, Blocks.FURNACE.defaultBlockState())));
        assertTrue(SmeltTool.supported(new BlastFurnaceBlockEntity(POS, Blocks.BLAST_FURNACE.defaultBlockState())));
        assertTrue(SmeltTool.supported(new SmokerBlockEntity(POS, Blocks.SMOKER.defaultBlockState())));
        assertFalse(SmeltTool.supported(new FurnaceBlockEntity(POS, Blocks.FURNACE.defaultBlockState()) {}));
    }

    @Test
    void queryReportsAllThreeMachineKindsAndIsReadonlyEvenWhenBusy() {
        for (String kind : List.of("minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker")) {
            Fixture f = new Fixture();
            f.machineId = kind;
            if (kind.equals("minecraft:smoker")) {
                f.ingredient = Items.BEEF;
                f.result = new ItemStack(Items.COOKED_BEEF);
            }
            f.recipeTicks = kind.equals("minecraft:furnace") ? 200 : 100;
            f.counters[3] = f.recipeTicks;
            f.counters[2] = 40;
            f.counters[0] = 120;
            f.counters[1] = kind.equals("minecraft:furnace") ? 1600 : 800;
            f.busy = true;
            f.slots.setItem(0, new ItemStack(f.ingredient, 3));
            f.slots.setItem(1, new ItemStack(Items.COAL, 2));
            var before = f.snapshot();
            var result = f.execute("");
            assertTrue(result.ok());
            var data = result.data();
            assertEquals(kind, data.get("machine").getAsString());
            assertEquals("COOKING", data.get("state").getAsString());
            assertEquals(f.recipeTicks * 3 - 40, data.get("needed_cook_ticks").getAsLong());
            assertEquals(120 + f.counters[1] * 2, data.get("available_fuel_ticks").getAsLong());
            assertTrue(data.get("fuel_sufficient_for_input").getAsBoolean());
            assertTrue(result.feedback().contains(f.recipeId));
            assertTrue(result.feedback().contains("120 tick"));
            assertFalse(f.reads.contains("inventory"));
            assertFalse(f.reads.contains("busy"));
            f.unchanged(before);
        }
    }

    @Test
    void guardsStopBeforeUnloadedBlockReadsOrInventoryAccess() {
        Fixture f = new Fixture();
        f.inBounds = false;
        f.failsUnchanged("", "DENIED:");
        assertEquals(List.of("bounds"), f.reads);
        f.inBounds = true;
        f.distanceSquared = 5.5 * 5.5 + 0.001;
        f.reads.clear();
        f.failsUnchanged("", "OUT_OF_REACH:");
        assertEquals(List.of("bounds", "distance"), f.reads);
        f.distanceSquared = 5.5 * 5.5;
        f.loaded = false;
        f.reads.clear();
        f.failsUnchanged("", "TARGET_LOST:");
        assertEquals(List.of("bounds", "distance", "loaded"), f.reads);
        f.loaded = true;
        f.present = false;
        f.failsUnchanged("", "TARGET_LOST:");
        f.present = true;
        f.locked = true;
        for (String action : List.of("", ",\"action\":\"load\",\"fuel_slot\":1", ",\"action\":\"take\"")) {
            f.reads.clear();
            f.failsUnchanged(action, "DENIED:");
            assertFalse(f.reads.contains("inventory"));
        }
    }

    @Test
    void loadMovesCompleteComponentsAndLeavesEquipmentAndUnrelatedStacksAlone() {
        for (String kind : List.of("minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker")) {
            Fixture f = new Fixture();
            f.machineId = kind;
            if (kind.equals("minecraft:smoker")) {
                f.ingredient = Items.BEEF;
                f.result = new ItemStack(Items.COOKED_BEEF);
            }
            ItemStack raw = named(f.ingredient, 7, "input metadata");
            ItemStack coal = named(Items.COAL, 4, "fuel metadata");
            ItemStack pick = named(Items.IRON_PICKAXE, 1, "unused tool");
            pick.setDamageValue(17);
            f.inventory.setItem(0, raw);
            f.inventory.setItem(1, coal);
            f.inventory.setItem(8, pick);
            for (int slot = 36; slot < 43; slot++) f.inventory.setItem(slot, named(Items.DIAMOND, 1, "equipment " + slot));
            var equipment = f.snapshot().subList(36, 43);
            int[] times = f.counters.clone();
            var receipt = f.execute(",\"action\":\"load\",\"input_slot\":0,\"input_count\":3,"
                    + "\"fuel_slot\":1,\"fuel_count\":2");
            assertTrue(receipt.ok(), receipt.feedback());
            assertTrue(ItemStack.matches(raw.copyWithCount(3), f.slots.getItem(0)));
            assertTrue(ItemStack.matches(coal.copyWithCount(2), f.slots.getItem(1)));
            assertTrue(ItemStack.matches(raw.copyWithCount(4), f.inventory.getItem(0)));
            assertTrue(ItemStack.matches(coal.copyWithCount(2), f.inventory.getItem(1)));
            assertSame(pick, f.inventory.getItem(8));
            for (int slot = 36; slot < 43; slot++) {
                assertTrue(ItemStack.matches(equipment.get(slot - 36), f.inventory.getItem(slot)));
            }
            assertEquals(3, receipt.data().get("loaded_input_count").getAsInt());
            assertEquals(2, receipt.data().get("loaded_fuel_count").getAsInt());
            assertEquals(0, receipt.data().getAsJsonObject("output").get("count").getAsInt());
            assertArrayEquals(times, f.counters);
        }
    }

    @Test
    void fuelCanBeStagedInEmptyOrOutputBlockedMachinesWithoutConsumingInput() {
        Fixture f = new Fixture();
        f.stock();
        assertTrue(f.execute(",\"action\":\"load\",\"fuel_slot\":1").ok());
        assertTrue(f.slots.getItem(0).isEmpty());
        assertEquals(1, f.slots.getItem(1).getCount());
        f.slots.setItem(0, new ItemStack(Items.RAW_IRON));
        f.slots.setItem(2, new ItemStack(Items.GOLD_INGOT, 64));
        var before = f.slots.getItem(0).copy();
        assertTrue(f.execute(",\"action\":\"load\",\"fuel_slot\":1").ok());
        assertTrue(ItemStack.matches(before, f.slots.getItem(0)));
        assertEquals(2, f.slots.getItem(1).getCount());
        assertEquals("OUTPUT_BLOCKED", f.execute("").data().get("state").getAsString());
    }

    @Test
    void inputOnlyRequiresFuelOrExistingBurnAndDoesNotResetLiveCounters() {
        Fixture f = new Fixture();
        f.stock();
        f.failsUnchanged(",\"action\":\"load\",\"input_slot\":0", "MISSING_FUEL:");
        f.counters[0] = 60;
        f.counters[2] = 80;
        assertTrue(f.execute(",\"action\":\"load\",\"input_slot\":0").ok());
        assertEquals(60, f.counters[0]);
        assertEquals(80, f.counters[2]);
        assertTrue(f.slots.getItem(1).isEmpty());
        assertFalse(f.execute("").data().get("fuel_sufficient_for_input").getAsBoolean());
    }

    @Test
    void invalidOrInsufficientFuelAfterInputReservationRollsBackBothSides() {
        Fixture f = new Fixture();
        f.stock();
        f.inventory.setItem(1, new ItemStack(Items.DIRT, 3));
        f.failsUnchanged(",\"action\":\"load\",\"input_slot\":0,\"input_count\":3,\"fuel_slot\":1",
                "INVALID_FUEL:");
        f.inventory.setItem(1, new ItemStack(Items.COAL));
        f.failsUnchanged(",\"action\":\"load\",\"input_slot\":0,\"fuel_slot\":1,\"fuel_count\":2",
                "MISSING_MATERIALS:");
        f.inventory.setItem(1, new ItemStack(Items.BUCKET));
        f.failsUnchanged(",\"action\":\"load\",\"input_slot\":0,\"fuel_slot\":1", "INVALID_FUEL:");
        f.failsUnchanged(",\"action\":\"load\",\"input_slot\":0,\"input_count\":9", "MISSING_MATERIALS:");
    }

    @Test
    void sharedInputAndFuelSlotCannotBeConsumedTwice() {
        Fixture f = new Fixture();
        f.ingredient = Items.OAK_LOG;
        f.result = new ItemStack(Items.CHARCOAL);
        f.inventory.setItem(4, named(Items.OAK_LOG, 3, "shared"));
        f.failsUnchanged(",\"action\":\"load\",\"input_slot\":4,\"input_count\":3,"
                + "\"fuel_slot\":4", "MISSING_MATERIALS:");
        var original = f.inventory.getItem(4).copy();
        assertTrue(f.execute(",\"action\":\"load\",\"input_slot\":4,\"input_count\":2,"
                + "\"fuel_slot\":4").ok());
        assertTrue(f.inventory.getItem(4).isEmpty());
        assertTrue(ItemStack.matches(original.copyWithCount(2), f.slots.getItem(0)));
        assertTrue(ItemStack.matches(original.copyWithCount(1), f.slots.getItem(1)));
    }

    @Test
    void inputAndFuelSlotCapacityAndComponentsAreCheckedBeforeCommit() {
        Fixture f = new Fixture();
        f.stock();
        f.slots.setItem(0, named(Items.RAW_IRON, 1, "different"));
        f.failsUnchanged(",\"action\":\"load\",\"input_slot\":0,\"fuel_slot\":1", "SLOT_BLOCKED:");
        f.slots.setItem(0, new ItemStack(Items.RAW_IRON, 64));
        f.failsUnchanged(",\"action\":\"load\",\"input_slot\":0,\"fuel_slot\":1", "SLOT_BLOCKED:");
        f.slots.setItem(0, ItemStack.EMPTY);
        f.slots.setItem(1, named(Items.COAL, 1, "different"));
        f.failsUnchanged(",\"action\":\"load\",\"input_slot\":0,\"fuel_slot\":1", "SLOT_BLOCKED:");
        f.slots.setItem(1, new ItemStack(Items.COAL, 64));
        f.failsUnchanged(",\"action\":\"load\",\"input_slot\":0,\"fuel_slot\":1", "SLOT_BLOCKED:");
        f.slots.setItem(1, new ItemStack(Items.BUCKET));
        f.inventory.setItem(1, new ItemStack(Items.LAVA_BUCKET));
        f.failsUnchanged(",\"action\":\"load\",\"input_slot\":0,\"fuel_slot\":1", "SLOT_BLOCKED:");
        f.slots.setItem(1, ItemStack.EMPTY);
        f.inventory.setItem(1, new ItemStack(Items.LAVA_BUCKET, 2));
        f.failsUnchanged(",\"action\":\"load\",\"input_slot\":0,\"fuel_slot\":1,\"fuel_count\":2",
                "MISSING_MATERIALS:");
        ItemStack limited = new ItemStack(Items.RAW_IRON, 3);
        limited.set(DataComponents.MAX_STACK_SIZE, 4);
        f.inventory.setItem(0, limited);
        f.slots.setItem(0, limited.copyWithCount(3));
        f.failsUnchanged(",\"action\":\"load\",\"input_slot\":0,\"input_count\":2", "SLOT_BLOCKED:");
    }

    @Test
    void outputBlockageAndWrongRecipeRejectNewInputWithoutSpendingFuel() {
        Fixture f = new Fixture();
        f.stock();
        for (ItemStack output : List.of(new ItemStack(Items.GOLD_INGOT), new ItemStack(Items.IRON_INGOT, 64),
                named(Items.IRON_INGOT, 1, "incompatible"))) {
            f.slots.setItem(2, output);
            f.failsUnchanged(",\"action\":\"load\",\"input_slot\":0,\"fuel_slot\":1", "OUTPUT_BLOCKED:");
            assertEquals("EMPTY_INPUT", f.execute("").data().get("state").getAsString());
        }
        f.slots.setItem(2, ItemStack.EMPTY);
        f.inventory.setItem(0, new ItemStack(Items.DIRT));
        f.failsUnchanged(",\"action\":\"load\",\"input_slot\":0,\"fuel_slot\":1", "NO_RECIPE:");
        f.ingredient = Items.BEEF;
        f.machineId = "minecraft:smoker";
        f.inventory.setItem(0, new ItemStack(Items.RAW_IRON));
        f.failsUnchanged(",\"action\":\"load\",\"input_slot\":0,\"fuel_slot\":1", "NO_RECIPE:");
    }

    @Test
    void unsupportedCookingYieldTimeAndSubclassesAreRejected() {
        Fixture f = new Fixture();
        f.stock();
        f.result = new ItemStack(Items.GOLD_NUGGET, 3);
        f.failsUnchanged(",\"action\":\"load\",\"input_slot\":0,\"fuel_slot\":1", "NO_RECIPE:");
        f.slots.setItem(0, new ItemStack(Items.RAW_IRON));
        f.failsUnchanged(",\"action\":\"load\",\"fuel_slot\":1", "NO_RECIPE:");
        f.slots.setItem(0, ItemStack.EMPTY);
        f.result = ItemStack.EMPTY;
        f.failsUnchanged(",\"action\":\"load\",\"input_slot\":0,\"fuel_slot\":1", "NO_RECIPE:");
        f.result = new ItemStack(Items.IRON_INGOT);
        f.recipeTicks = 0;
        f.failsUnchanged(",\"action\":\"load\",\"input_slot\":0,\"fuel_slot\":1", "NO_RECIPE:");
        var custom = new SmeltingRecipe("", CookingBookCategory.MISC, Ingredient.of(Items.RAW_IRON),
                f.result, 0, 200) {};
        assertNull(SmeltTool.cooking(new RecipeHolder<>(
                ResourceKey.create(Registries.RECIPE, Identifier.parse("offline:custom")), custom),
                f.inventory.getItem(0), RegistryAccess.EMPTY, FeatureFlags.VANILLA_SET));
    }

    @Test
    void takeMergesMatchingComponentsBeforeUsingEmptySlotsAndHonorsCount() {
        Fixture f = new Fixture();
        ItemStack output = named(Items.IRON_INGOT, 9, "finished");
        f.slots.setItem(2, output);
        f.inventory.setItem(4, output.copyWithCount(62));
        f.inventory.setItem(5, named(Items.IRON_INGOT, 20, "different"));
        var receipt = f.execute(",\"action\":\"take\",\"count\":5");
        assertTrue(receipt.ok());
        assertTrue(ItemStack.matches(output.copyWithCount(64), f.inventory.getItem(4)));
        assertTrue(ItemStack.matches(output.copyWithCount(3), f.inventory.getItem(0)));
        assertEquals(20, f.inventory.getItem(5).getCount());
        assertTrue(ItemStack.matches(output.copyWithCount(4), f.slots.getItem(2)));
        assertEquals(5, receipt.data().get("taken_count").getAsInt());
        assertTrue(f.execute(",\"action\":\"take\",\"count\":64").ok());
        assertTrue(f.slots.getItem(2).isEmpty());
        assertEquals(7, f.inventory.getItem(0).getCount());
    }

    @Test
    void takeFailureAfterPartialCopyMergeDoesNotChangeEitherContainer() {
        Fixture f = new Fixture();
        for (int slot = 0; slot < 43; slot++) f.inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        f.inventory.setItem(4, new ItemStack(Items.IRON_INGOT, 63));
        f.slots.setItem(2, new ItemStack(Items.IRON_INGOT, 3));
        f.failsUnchanged(",\"action\":\"take\",\"count\":3", "INVENTORY_FULL:");
        // Empty equipment is never borrowed as output storage.
        for (int slot = 36; slot < 43; slot++) f.inventory.setItem(slot, ItemStack.EMPTY);
        f.failsUnchanged(",\"action\":\"take\",\"count\":3", "INVENTORY_FULL:");
        assertTrue(f.execute(",\"action\":\"take\",\"count\":1").ok());
        assertEquals(64, f.inventory.getItem(4).getCount());
        assertEquals(2, f.slots.getItem(2).getCount());
    }

    @Test
    void takeRespectsContainerLimitsAndPreservesNonstackableComponents() {
        Fixture f = new Fixture();
        ItemStack pick = named(Items.IRON_PICKAXE, 1, "recovered");
        pick.setDamageValue(31);
        f.slots.setItem(0, pick);
        var receipt = f.execute(",\"action\":\"take\",\"slot\":\"input\",\"count\":2");
        assertTrue(receipt.ok());
        assertTrue(ItemStack.matches(pick.copyWithCount(1), f.inventory.getItem(0)));
        assertTrue(f.inventory.getItem(1).isEmpty());
        assertTrue(f.slots.getItem(0).isEmpty());
        f.inventoryLimit = 8;
        f.slots.setItem(2, named(Items.COAL, 17, "limited"));
        f.inventory.setItem(7, named(Items.COAL, 7, "limited"));
        assertTrue(f.execute(",\"action\":\"take\"").ok());
        assertEquals(8, f.inventory.getItem(7).getCount());
        assertEquals(8, f.inventory.getItem(1).getCount());
        assertEquals(8, f.inventory.getItem(2).getCount());
        assertTrue(f.slots.getItem(2).isEmpty());
    }

    @Test
    void returnedBucketsCanBeRecoveredButAreNeverAcceptedAsFuel() {
        for (Item bucket : List.of(Items.BUCKET, Items.WATER_BUCKET)) {
            Fixture f = new Fixture();
            var returned = named(bucket, 1, "returned container");
            f.slots.setItem(1, returned);
            f.counters[0] = 400;
            f.slots.setItem(0, new ItemStack(Items.RAW_IRON, 2));
            var receipt = f.execute(",\"action\":\"take\",\"slot\":\"fuel\"");
            assertTrue(receipt.ok());
            assertTrue(ItemStack.matches(returned, f.inventory.getItem(0)));
            assertTrue(f.slots.getItem(1).isEmpty());
            assertEquals(400, f.counters[0]);
            assertEquals(2, f.slots.getItem(0).getCount());
            f.failsUnchanged(",\"action\":\"load\",\"fuel_slot\":0", "INVALID_FUEL:");
        }
    }

    @Test
    void emptyTakeReportsActualStatusAndNeverFabricatesProducts() {
        Fixture f = new Fixture();
        f.slots.setItem(0, new ItemStack(Items.RAW_IRON, 3));
        f.slots.setItem(1, new ItemStack(Items.COAL));
        var before = f.snapshot();
        var receipt = f.execute(",\"action\":\"take\"");
        assertFalse(receipt.ok());
        assertTrue(receipt.feedback().startsWith("NOT_READY:"));
        assertEquals(0, receipt.data().get("taken_count").getAsInt());
        assertEquals(3, receipt.data().getAsJsonObject("input").get("count").getAsInt());
        f.unchanged(before);
        f.slots.setItem(0, ItemStack.EMPTY);
        f.failsUnchanged(",\"action\":\"take\",\"slot\":\"input\"", "EMPTY_SLOT:");
        f.slots.setItem(1, ItemStack.EMPTY);
        f.failsUnchanged(",\"action\":\"take\",\"slot\":\"fuel\"", "EMPTY_SLOT:");
    }

    @Test
    void busyMutationsDoNotReadTheWorldAndReleaseDoesNotTouchMachineState() {
        Fixture f = new Fixture();
        f.stock();
        f.slots.setItem(0, new ItemStack(Items.RAW_IRON, 3));
        f.slots.setItem(1, new ItemStack(Items.COAL, 2));
        f.slots.setItem(2, new ItemStack(Items.IRON_INGOT));
        f.counters[0] = 250;
        f.counters[2] = 90;
        f.busy = true;
        for (String fields : List.of(",\"action\":\"load\",\"fuel_slot\":1", ",\"action\":\"take\"")) {
            f.reads.clear();
            f.failsUnchanged(fields, "BUSY:");
            assertEquals(List.of("busy"), f.reads);
        }
        assertTrue(f.execute("").ok());
        var before = f.snapshot();
        f.busy = false;
        var query = f.execute("");
        f.unchanged(before);
        assertEquals(250, query.data().get("burn_remaining_ticks").getAsInt());
        assertEquals(90, query.data().get("cook_progress_ticks").getAsInt());
        assertTrue(f.execute(",\"action\":\"take\",\"slot\":\"input\"").ok());
        assertTrue(f.slots.getItem(0).isEmpty());
        assertEquals(250, f.counters[0]);
        assertEquals(2, f.slots.getItem(1).getCount());
        assertEquals(1, f.slots.getItem(2).getCount());
    }

    @Test
    void liveCookTotalAndRecipeTimeAreKeptSeparateAfterRecipeReload() {
        Fixture f = new Fixture();
        f.slots.setItem(0, new ItemStack(Items.RAW_IRON, 2));
        f.slots.setItem(1, new ItemStack(Items.COAL));
        f.recipeTicks = 37;
        f.counters[3] = 100;
        f.counters[2] = 25;
        var receipt = f.execute("");
        assertEquals(112, receipt.data().get("needed_cook_ticks").getAsLong());
        assertEquals(6, receipt.data().get("suggested_wait_seconds").getAsInt());
        assertEquals(100, receipt.data().get("cook_total_ticks").getAsInt());
        assertEquals(37, receipt.data().get("recipe_cook_ticks").getAsInt());
        f.counters[3] = 0;
        assertEquals(49, f.execute("").data().get("needed_cook_ticks").getAsLong());
        f.recipeTicks = Integer.MAX_VALUE;
        f.counters[3] = Integer.MAX_VALUE;
        f.slots.setItem(0, new ItemStack(Items.RAW_IRON, 64));
        var longBatch = f.execute("");
        assertEquals((long) Integer.MAX_VALUE * 64 - 25, longBatch.data().get("needed_cook_ticks").getAsLong());
        assertEquals(60, longBatch.data().get("suggested_wait_seconds").getAsInt());
    }

    @Test
    void blockedNoFuelAndUntickedStatesDoNotRecommendBlindWaiting() {
        Fixture f = new Fixture();
        f.slots.setItem(0, new ItemStack(Items.RAW_IRON, 64));
        var noFuel = f.execute("");
        assertEquals("MISSING_FUEL", noFuel.data().get("state").getAsString());
        assertEquals(0, noFuel.data().get("suggested_wait_seconds").getAsInt());
        f.slots.setItem(1, new ItemStack(Items.COAL));
        var insufficient = f.execute("");
        assertEquals("COOKING", insufficient.data().get("state").getAsString());
        assertFalse(insufficient.data().get("fuel_sufficient_for_input").getAsBoolean());
        assertTrue(insufficient.feedback().contains("燃料不足"));
        f.ticking = false;
        assertEquals("NOT_TICKING", f.execute("").data().get("state").getAsString());
        assertEquals(0, f.execute("").data().get("suggested_wait_seconds").getAsInt());
        f.slots.setItem(2, named(Items.IRON_INGOT, 1, "different"));
        assertEquals("OUTPUT_BLOCKED", f.execute("").data().get("state").getAsString());
        f.slots.setItem(0, new ItemStack(Items.DIRT));
        assertEquals("NO_RECIPE", f.execute("").data().get("state").getAsString());
    }

    @Test
    void hugeNamesAndRecipeIdsDoNotEraseTheReceiptOrLeakRawComponents() {
        Fixture f = new Fixture();
        String privateName = "private-" + "x".repeat(40000);
        f.slots.setItem(0, named(Items.RAW_IRON, 3, privateName));
        f.slots.setItem(1, named(Items.COAL, 1, privateName));
        f.recipeId = "offline:" + "a".repeat(1000);
        var result = f.execute("");
        assertTrue(result.ok());
        assertTrue(result.data().get("recipe_id_truncated").getAsBoolean());
        assertEquals(128, WireSize.utf8Bytes(result.data().get("recipe").getAsString()));
        assertFalse(result.feedback().contains("private-"));
        assertFalse(result.data().toString().contains("private-"));
        assertTrue(WireSize.utf8Bytes(result.feedback()) + WireSize.utf8Bytes(result.data().toString()) < 8192);
    }
}
