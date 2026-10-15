package com.neko.mcbot.server.tools;

import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RecipeMaterialsTest {
    @BeforeAll static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }
    @Test void actualIngredientAcceptsDifferentWoodsAndRejectsUnrelatedRecipeOutputs() {
        var material = Ingredient.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS);
        var result = RecipeMaterials.describe(material, List.of(
                new RecipeMaterials.Conversion("test:oak",new ItemStack(Items.OAK_PLANKS,4),Ingredient.of(Items.OAK_LOG)),
                new RecipeMaterials.Conversion("test:birch",new ItemStack(Items.BIRCH_PLANKS,4),Ingredient.of(Items.BIRCH_LOG)),
                new RecipeMaterials.Conversion("test:stone",new ItemStack(Items.COBBLESTONE,1),Ingredient.of(Items.STONE))));
        assertEquals(2,result.getAsJsonArray("items").size());
        var sources=result.getAsJsonArray("sources");
        assertEquals(2,sources.size());
        assertEquals("minecraft:birch_log",sources.get(1).getAsJsonObject().get("item").getAsString());
        assertEquals("minecraft:birch_log",sources.get(1).getAsJsonObject().get("block").getAsString());
        assertEquals(4,sources.get(1).getAsJsonObject().getAsJsonObject("conversion").get("produced_count").getAsInt());
        assertFalse(result.get("truncated").getAsBoolean());
    }
    @Test void duplicateSourcesDoNotDoubleCountAndNoConversionsInventNoRawSources() {
        var material=Ingredient.of(Items.OAK_PLANKS);
        assertTrue(RecipeMaterials.describe(material,List.of()).getAsJsonArray("sources").isEmpty());
        var conversion=new RecipeMaterials.Conversion("test:one",new ItemStack(Items.OAK_PLANKS,4),Ingredient.of(Items.OAK_LOG));
        assertEquals(1,RecipeMaterials.describe(material,List.of(conversion,conversion)).getAsJsonArray("sources").size());
    }

    private static RecipeMaterials.Conversion cooking(String id, AbstractCookingRecipe recipe) {
        return RecipeMaterials.cooking(new RecipeHolder<>(ResourceKey.create(Registries.RECIPE,
                Identifier.parse("test:" + id)), recipe), RegistryAccess.EMPTY, FeatureFlags.VANILLA_SET);
    }

    @Test void ironConversionAndRealCookingFactsRemainSeparateFromWorldCollectionSources() {
        var furnace = cooking("iron_furnace", new SmeltingRecipe("", CookingBookCategory.MISC,
                Ingredient.of(Items.RAW_IRON), new ItemStack(Items.IRON_INGOT), 0, 200));
        var blast = cooking("iron_blast", new BlastingRecipe("", CookingBookCategory.MISC,
                Ingredient.of(Items.RAW_IRON), new ItemStack(Items.IRON_INGOT), 0, 100));
        assertNotNull(furnace);
        assertNotNull(blast);
        var result = RecipeMaterials.describe(Ingredient.of(Items.IRON_INGOT), List.of(
                new RecipeMaterials.Conversion("test:unpack", new ItemStack(Items.IRON_INGOT, 9),
                        Ingredient.of(Items.IRON_BLOCK)), furnace, blast));
        assertEquals(1, result.getAsJsonArray("sources").size());
        assertEquals("minecraft:iron_block", result.getAsJsonArray("sources").get(0)
                .getAsJsonObject().get("block").getAsString());
        var processes = result.getAsJsonArray("processes");
        assertEquals(3, processes.size());
        var fact = processes.get(1).getAsJsonObject();
        assertEquals("smelting", fact.get("process").getAsString());
        assertEquals("minecraft:furnace", fact.get("workstation").getAsString());
        assertTrue(fact.get("requires_fuel").getAsBoolean());
        assertEquals(200, fact.get("cooking_ticks").getAsInt());
        var raw = fact.getAsJsonArray("inputs").get(0).getAsJsonObject();
        assertEquals("minecraft:raw_iron", raw.get("item").getAsString());
        assertFalse(raw.has("block"), "a cooking ingredient must not acquire an invented ore block");
        assertFalse(result.get("world_sources_verified").getAsBoolean());
        assertFalse(result.get("processes_truncated").getAsBoolean());
    }

    @Test void theSameFactPathHandlesFoodAndCopperWithoutMineralSpecificBranches() {
        var food = cooking("food", new SmokingRecipe("", CookingBookCategory.FOOD,
                Ingredient.of(Items.PORKCHOP), new ItemStack(Items.COOKED_PORKCHOP), 0, 100));
        var copper = cooking("copper", new BlastingRecipe("", CookingBookCategory.MISC,
                Ingredient.of(Items.RAW_COPPER), new ItemStack(Items.COPPER_INGOT), 0, 100));
        var result = RecipeMaterials.describe(Ingredient.of(Items.COOKED_PORKCHOP), List.of(food, copper));
        assertEquals(1, result.getAsJsonArray("processes").size());
        var process = result.getAsJsonArray("processes").get(0).getAsJsonObject();
        assertEquals("smoking", process.get("process").getAsString());
        assertEquals("minecraft:smoker", process.get("workstation").getAsString());
        assertTrue(result.getAsJsonArray("sources").isEmpty());
        assertEquals(1, RecipeMaterials.describe(Ingredient.of(Items.COPPER_INGOT),
                List.of(food, copper)).getAsJsonArray("processes").size());
    }

    @Test void unsupportedCookingResultsCannotBecomePromisesThatTheMachineCannotFulfill() {
        assertNull(cooking("multi", new SmeltingRecipe("", CookingBookCategory.MISC,
                Ingredient.of(Items.RAW_IRON), new ItemStack(Items.IRON_INGOT, 2), 0, 200)));
        assertNull(cooking("zero_time", new SmeltingRecipe("", CookingBookCategory.MISC,
                Ingredient.of(Items.RAW_IRON), new ItemStack(Items.IRON_INGOT), 0, 0)));
        var dynamic = new SmeltingRecipe("", CookingBookCategory.MISC, Ingredient.of(Items.RAW_IRON),
                new ItemStack(Items.IRON_INGOT), 0, 200) { };
        assertNull(cooking("custom", dynamic));
    }

    @Test void processTruncationIsExplicitAndDoesNotInvalidateCompleteDirectIngredientOptions() {
        var conversions = new java.util.ArrayList<RecipeMaterials.Conversion>();
        for (int i = 0; i < 20; i++) conversions.add(new RecipeMaterials.Conversion("test:route_" + i,
                new ItemStack(Items.COPPER_INGOT), Ingredient.of(Items.RAW_COPPER),
                RecipeMaterials.Process.BLASTING, "minecraft:blast_furnace", 100));
        var result = RecipeMaterials.describe(Ingredient.of(Items.COPPER_INGOT), conversions);
        assertEquals(16, result.getAsJsonArray("processes").size());
        assertTrue(result.get("processes_truncated").getAsBoolean());
        assertFalse(result.get("truncated").getAsBoolean());
        assertTrue(result.getAsJsonArray("sources").isEmpty());
    }
}
