package com.neko.mcbot.server.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.common.WireSize;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;

import java.util.LinkedHashMap;
import java.util.List;

/** Recipe facts are separate from collection targets and from currently executable work. */
final class RecipeMaterials {
    enum Process { CRAFTING, SMELTING, BLASTING, SMOKING }
    record Conversion(String recipe, ItemStack output, Ingredient input,
                      Process process, String workstation, int ticks) {
        Conversion(String recipe, ItemStack output, Ingredient input) {
            this(recipe, output, input, Process.CRAFTING, "inventory", 0);
        }
    }
    private static final int LIMIT = 64;
    private RecipeMaterials() { }

    static Conversion cooking(RecipeHolder<?> holder, HolderLookup.Provider registries, FeatureFlagSet features) {
        var value = holder.value();
        Process process;
        String station;
        if (value.getClass() == SmeltingRecipe.class) {
            process = Process.SMELTING; station = "minecraft:furnace";
        } else if (value.getClass() == BlastingRecipe.class) {
            process = Process.BLASTING; station = "minecraft:blast_furnace";
        } else if (value.getClass() == SmokingRecipe.class) {
            process = Process.SMOKING; station = "minecraft:smoker";
        } else return null;
        var recipe = (AbstractCookingRecipe) value;
        var sample = recipe.input().items().findFirst().map(item -> new ItemStack(item.value())).orElse(ItemStack.EMPTY);
        var supported = SmeltTool.cooking(new RecipeHolder<>(holder.id(), recipe), sample, registries, features);
        return supported == null ? null : new Conversion(supported.id(), supported.output(), recipe.input(),
                process, station, supported.ticks());
    }

    static JsonObject describe(Ingredient ingredient, List<Conversion> conversions) {
        var direct = new LinkedHashMap<String, JsonObject>();
        var sources = new LinkedHashMap<String, JsonObject>();
        var processes = new JsonArray();
        int processBytes = 0;
        boolean processesCut = false;
        boolean[] cut = {false};
        ingredient.items().forEach(holder -> add(direct, holder.value(), null, cut));
        for (var conversion : conversions) {
            if (!ingredient.test(conversion.output())) continue;
            var facts = new JsonObject();
            facts.addProperty("recipe", conversion.recipe());
            facts.addProperty("process", conversion.process().name().toLowerCase(java.util.Locale.ROOT));
            facts.addProperty("workstation", conversion.workstation());
            facts.addProperty("produced_item", BuiltInRegistries.ITEM.getKey(conversion.output().getItem()).toString());
            facts.addProperty("produced_count", conversion.output().getCount());
            facts.addProperty("input_count", 1);
            facts.addProperty("requires_fuel", conversion.process() != Process.CRAFTING);
            if (conversion.ticks() > 0) facts.addProperty("cooking_ticks", conversion.ticks());
            var inputs = new LinkedHashMap<String, JsonObject>();
            boolean[] inputsCut = {false};
            conversion.input().items().forEach(holder -> add(inputs, holder.value(), null, inputsCut));
            var options = new JsonArray(); inputs.values().forEach(options::add);
            facts.add("inputs", options);
            facts.addProperty("inputs_truncated", inputsCut[0]);
            int bytes = WireSize.utf8Bytes(facts.toString());
            if (processes.size() < 16 && processBytes + bytes <= 8000) {
                processes.add(facts); processBytes += bytes;
            } else processesCut = true;
            processesCut |= inputsCut[0];
            // Compatibility collection sources remain one-step crafting inputs, never cooking ingredients.
            if (conversion.process() != Process.CRAFTING) continue;
            conversion.input().items().forEach(holder -> {
                Item item = holder.value();
                if (!(item instanceof BlockItem) || !item.getCraftingRemainder().isEmpty()
                        || item == conversion.output().getItem()) return;
                var via = new JsonObject();
                via.addProperty("recipe", conversion.recipe());
                via.addProperty("produced_item", BuiltInRegistries.ITEM.getKey(conversion.output().getItem()).toString());
                via.addProperty("produced_count", conversion.output().getCount());
                add(sources, item, via, cut);
            });
        }
        var data = new JsonObject();
        var items = new JsonArray(); direct.values().forEach(items::add);
        var raw = new JsonArray(); sources.values().forEach(raw::add);
        data.add("items", items); data.add("sources", raw);
        data.addProperty("truncated", cut[0]);
        data.add("processes", processes);
        data.addProperty("processes_truncated", processesCut);
        data.addProperty("process_scope", "single_input_crafting_and_supported_cooking");
        data.addProperty("world_sources_verified", false);
        return data;
    }
    private static void add(LinkedHashMap<String, JsonObject> entries, Item item, JsonObject via, boolean[] cut) {
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        if (entries.containsKey(id)) return;
        if (entries.size() >= LIMIT || id.length() > 128) { cut[0] = true; return; }
        var option = new JsonObject();
        option.addProperty("item", id);
        if (item instanceof BlockItem block)
            option.addProperty("block", BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString());
        if (via != null) option.add("conversion", via);
        entries.put(id, option);
    }
}
