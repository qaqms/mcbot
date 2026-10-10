package com.neko.mcbot.server.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.McbotMod;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.common.WireSize;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.task.CompanionScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Plan the entire request on copies; a failed batch must not consume items or drop overflow. */
public final class CraftTool implements ServerTool {
    private static final int MAX_SCAN = 4096;
    private static final int MAX_CANDIDATES = 32;

    record Arguments(Identifier item, int count, boolean query, Identifier recipe) {
    }

    private record Candidate(RecipeHolder<?> holder, CraftingRecipe recipe, ItemStack output,
                             int width, int height, List<Integer> placement, List<Ingredient> ingredients) {
        boolean needsTable() {
            return width > 2 || height > 2;
        }
    }

    private record Plan(List<ItemStack> storage, int batches, String failure) {
    }

    @Override
    public String name() {
        return "craft";
    }

    @Override
    public Result run(CompanionPlayer companion, JsonObject args) {
        return craft(companion, args, McbotMod.scheduler());
    }

    @Override
    public CompletableFuture<Result> runAsync(CompanionPlayer companion, JsonObject args,
                                               CompanionScheduler scheduler) {
        return CompletableFuture.completedFuture(craft(companion, args, scheduler));
    }

    static Arguments arguments(JsonObject args) {
        if (args == null) return null;
        Identifier item = identifier(args, "item");
        Identifier recipe = args.has("recipe") ? identifier(args, "recipe") : null;
        if (item == null || (args.has("recipe") && recipe == null)) return null;
        int count = 1;
        if (args.has("count")) {
            var value = args.get("count");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
            try {
                count = value.getAsBigDecimal().intValueExact();
            } catch (RuntimeException invalid) {
                return null;
            }
        }
        if (count < 1 || count > 64) return null;
        boolean query = false;
        if (args.has("query")) {
            var value = args.get("query");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) return null;
            query = value.getAsBoolean();
        }
        return new Arguments(item, count, query, recipe);
    }

    private static Identifier identifier(JsonObject args, String key) {
        var value = args.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
        String text = value.getAsString();
        return text.isBlank() || WireSize.utf8Bytes(text) > 128 ? null : Identifier.tryParse(text);
    }

    private static Result craft(CompanionPlayer companion, JsonObject json, CompanionScheduler scheduler) {
        Arguments args = arguments(json);
        if (args == null) {
            return new Result(false, "DENIED:需要有效 item，count 为 1-64 的整数（默认 1），"
                    + "query 为布尔值，recipe 可选；用 query=true 先查材料与配方。", null);
        }
        if (!args.query() && scheduler.busy(companion.getUUID())) {
            return new Result(false, "BUSY:我正忙着上一件事，等它结束或取消后再合成。", null);
        }
        var item = BuiltInRegistries.ITEM.get(args.item());
        if (item.isEmpty() || item.get().value() == net.minecraft.world.item.Items.AIR) {
            return new Result(false, "DENIED:item 不是有效物品；用 inventory 查看注册物品 ID。", null);
        }
        var manager = companion.level().recipeAccess();
        List<RecipeHolder<?>> recipes;
        if (args.recipe() != null) {
            var selected = manager.byKey(ResourceKey.create(Registries.RECIPE, args.recipe()));
            if (selected.isEmpty()) return noRecipe();
            recipes = List.of(selected.get());
        } else {
            var loaded = manager.getRecipes();
            if (loaded.size() > MAX_SCAN) {
                return new Result(false, "DENIED:服务器配方超过自动查询上限；请指定 recipe 的注册 ID。", null);
            }
            recipes = new ArrayList<>(loaded);
            recipes.sort(Comparator.comparing(holder -> holder.id().identifier().toString()));
        }
        List<Candidate> candidates = new ArrayList<>();
        for (var holder : recipes) {
            Candidate candidate = candidate(companion, holder, item.get().value());
            if (candidate != null) {
                if (candidates.size() == MAX_CANDIDATES) {
                    return new Result(false, "DENIED:该物品配方过多；请指定 recipe 的注册 ID。", null);
                }
                candidates.add(candidate);
            }
        }
        if (candidates.isEmpty()) return noRecipe();

        BlockPos table = candidates.stream().anyMatch(Candidate::needsTable) ? findTable(companion) : null;
        Candidate selected = null;
        Plan selectedPlan = null;
        for (Candidate candidate : candidates) {
            Plan plan = plan(companion, candidate, args.count());
            String failure = candidate.needsTable() && table == null
                    ? "NEED_WORKBENCH:需要 5.5 格内已加载的工作台；先 scan_area 找到并 move_to 靠近，"
                    + "或用 place_block 放置背包里的工作台。" : plan.failure();
            plan = new Plan(plan.storage(), plan.batches(), failure);
            if (selected == null || (selectedPlan.failure() != null && failure == null)) {
                selected = candidate;
                selectedPlan = plan;
            }
            if (failure == null) break;
        }
        int batches = selectedPlan.batches();
        int produced = batches * selected.output().getCount();
        JsonObject data = details(companion, selected, args.count(), batches, table);
        boolean possible = selectedPlan.failure() == null;
        data.addProperty("can_craft", possible);
        data.addProperty("query", args.query());
        data.addProperty("crafted_count", possible && !args.query() ? produced : 0);
        String summary = "配方 " + displayId(selected.holder().id().identifier())
                + "；请求至少 " + args.count() + " 个，计划 " + batches + " 次产出 " + produced + " 个 "
                + InventoryTool.stackData(selected.output()).get("item").getAsString()
                + "。单次材料：" + ingredientFeedback(companion, selected) + "。";
        if (selected.needsTable() && table != null) summary += "工作台 @(" + table.toShortString() + ")。";
        if (args.query()) {
            return new Result(true, "仅查询，未改背包。" + summary
                    + (possible ? "当前可合成；需要执行时用 query=false。"
                    : selectedPlan.failure()), data);
        }
        if (!possible) return new Result(false, selectedPlan.failure() + summary + "背包未修改。", data);

        Inventory inventory = companion.getInventory();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            // Keep unrelated stack identities as well as their complete components.
            if (!ItemStack.matches(inventory.getItem(slot), selectedPlan.storage().get(slot))) {
                inventory.setItem(slot, selectedPlan.storage().get(slot));
            }
        }
        inventory.setChanged();
        return new Result(true, "合成完成。" + summary + "成品及配方返还物已全部放入背包；可用 inventory 核对。", data);
    }

    private static Result noRecipe() {
        return new Result(false, "NO_RECIPE:没有匹配的普通有序/无序合成配方；检查 item/recipe。"
                + "冶炼、切石、锻造及染色/修复等特殊动态配方不由 craft 执行。", null);
    }

    private static Candidate candidate(CompanionPlayer companion, RecipeHolder<?> holder, Item target) {
        // Exact vanilla classes have a static result; arbitrary/special recipes cannot be probed with empty input.
        var value = holder.value();
        if (value.getClass() != ShapedRecipe.class && value.getClass() != ShapelessRecipe.class) return null;
        CraftingRecipe recipe = (CraftingRecipe) value;
        ItemStack output = recipe.assemble(CraftingInput.EMPTY, companion.level().registryAccess());
        if (output.isEmpty() || output.getItem() != target
                || !output.isItemEnabled(companion.level().enabledFeatures())
                || output.getCount() > output.getMaxStackSize()) return null;
        var info = recipe.placementInfo();
        if (info.isImpossibleToPlace() || info.ingredients().isEmpty() || info.ingredients().size() > 9) return null;
        int width;
        int height;
        if (recipe instanceof ShapedRecipe shaped) {
            width = shaped.getWidth();
            height = shaped.getHeight();
        } else {
            width = info.ingredients().size() <= 4 ? 2 : 3;
            height = (info.ingredients().size() + width - 1) / width;
        }
        if (width < 1 || width > 3 || height < 1 || height > 3) return null;
        return new Candidate(holder, recipe, output, width, height,
                new ArrayList<>(info.slotsToIngredientIndex()), info.ingredients());
    }

    private static Plan plan(CompanionPlayer companion, Candidate candidate, int count) {
        int batches = (count + candidate.output().getCount() - 1) / candidate.output().getCount();
        List<ItemStack> storage = new ArrayList<>();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            storage.add(companion.getInventory().getItem(slot).copy());
        }
        for (int batch = 0; batch < batches; batch++) {
            // Vanilla's matching algorithm resolves overlapping tags without consuming one stack twice.
            // Stack identities retain component-aware ingredient predicates, unlike item-ID-only accounting.
            StackedContents<ItemStack> contents = new StackedContents<>();
            for (ItemStack stack : storage) if (!stack.isEmpty()) contents.account(stack, stack.getCount());
            List<StackedContents.IngredientInfo<ItemStack>> predicates = candidate.ingredients().stream()
                    .<StackedContents.IngredientInfo<ItemStack>>map(ingredient -> ingredient::test).toList();
            List<ItemStack> chosen = new ArrayList<>();
            if (!contents.tryPick(predicates, 1, chosen::add)) {
                return new Plan(storage, batches, "MISSING_MATERIALS:材料不足以完成整批合成；"
                        + "先用 inventory 对照下列材料，取得缺料后再调用。");
            }
            List<ItemStack> grid = new ArrayList<>();
            for (int cell = 0; cell < candidate.width() * candidate.height(); cell++) {
                int ingredient = cell < candidate.placement().size() ? candidate.placement().get(cell) : -1;
                grid.add(ingredient < 0 ? ItemStack.EMPTY : chosen.get(ingredient).copyWithCount(1));
            }
            CraftingInput input = CraftingInput.of(candidate.width(), candidate.height(), grid);
            if (!candidate.recipe().matches(input, companion.level())) {
                return new Plan(storage, batches, "DENIED:实际材料不符合服务器配方；换材料或指定其他 recipe。");
            }
            ItemStack output = candidate.recipe().assemble(input, companion.level().registryAccess());
            if (!ItemStack.matches(output, candidate.output())) {
                return new Plan(storage, batches, "DENIED:配方产物发生变化；先重新查询配方。");
            }
            var remaining = candidate.recipe().getRemainingItems(input);
            for (ItemStack stack : chosen) stack.shrink(1);
            // Reusable containers belong to the same transaction as the result.
            for (ItemStack remainder : remaining) {
                if (!insert(storage, remainder.copy())) {
                    return new Plan(storage, batches, "INVENTORY_FULL:装不下配方返还物；先 transfer 腾出背包空间。");
                }
            }
            if (!insert(storage, output)) {
                return new Plan(storage, batches, "INVENTORY_FULL:装不下整批成品；先 transfer 腾出背包空间。");
            }
        }
        return new Plan(storage, batches, null);
    }

    private static boolean insert(List<ItemStack> storage, ItemStack remaining) {
        for (ItemStack at : storage) {
            if (remaining.isEmpty()) return true;
            if (at.isEmpty() || !ItemStack.isSameItemSameComponents(at, remaining)) continue;
            int amount = Math.min(Math.max(0, at.getMaxStackSize() - at.getCount()), remaining.getCount());
            at.grow(amount);
            remaining.shrink(amount);
        }
        for (int slot = 0; slot < storage.size() && !remaining.isEmpty(); slot++) {
            if (storage.get(slot).isEmpty()) storage.set(slot, remaining.split(remaining.getMaxStackSize()));
        }
        return remaining.isEmpty();
    }

    private static BlockPos findTable(CompanionPlayer companion) {
        BlockPos best = null;
        double distance = 5.5 * 5.5;
        for (BlockPos pos : BlockPos.betweenClosed(companion.blockPosition().offset(-6, -6, -6),
                companion.blockPosition().offset(6, 6, 6))) {
            double candidate = companion.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (candidate <= distance && companion.level().isLoaded(pos)
                    && companion.level().getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
                best = pos.immutable();
                distance = candidate;
            }
        }
        return best;
    }

    private static Map<Ingredient, Integer> grouped(Candidate candidate) {
        Map<Ingredient, Integer> grouped = new LinkedHashMap<>();
        for (Ingredient ingredient : candidate.ingredients()) grouped.merge(ingredient, 1, Integer::sum);
        return grouped;
    }

    private static String ingredientFeedback(CompanionPlayer companion, Candidate candidate) {
        List<String> descriptions = new ArrayList<>();
        for (var entry : grouped(candidate).entrySet()) {
            descriptions.add(options(entry.getKey()).toString() + " × " + entry.getValue()
                    + (entry.getKey().items().limit(5).count() > 4 ? "（仅展示前 4 个候选）" : "")
                    + "（背包匹配 " + available(companion, entry.getKey()) + "，共享候选不能重复计数）");
        }
        return String.join("；", descriptions);
    }

    private static JsonObject details(CompanionPlayer companion, Candidate candidate, int requested,
                                      int batches, BlockPos table) {
        JsonObject data = new JsonObject();
        data.addProperty("recipe", displayId(candidate.holder().id().identifier()));
        data.addProperty("requested_count", requested);
        data.addProperty("batches", batches);
        data.addProperty("produced_count", batches * candidate.output().getCount());
        data.add("output_per_batch", InventoryTool.stackData(candidate.output()));
        data.addProperty("requires_workbench", candidate.needsTable());
        if (candidate.needsTable() && table != null) {
            JsonObject position = new JsonObject();
            position.addProperty("x", table.getX());
            position.addProperty("y", table.getY());
            position.addProperty("z", table.getZ());
            data.add("workbench", position);
        }
        JsonArray ingredients = new JsonArray();
        for (var entry : grouped(candidate).entrySet()) {
            JsonObject ingredient = new JsonObject();
            ingredient.add("options", options(entry.getKey()));
            ingredient.addProperty("per_batch", entry.getValue());
            ingredient.addProperty("required_count", entry.getValue() * batches);
            ingredient.addProperty("available_count", available(companion, entry.getKey()));
            ingredient.addProperty("options_truncated", entry.getKey().items().limit(5).count() > 4);
            ingredients.add(ingredient);
        }
        data.add("ingredients", ingredients);
        return data;
    }

    private static int available(CompanionPlayer companion, Ingredient ingredient) {
        int count = 0;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = companion.getInventory().getItem(slot);
            if (!stack.isEmpty() && ingredient.test(stack)) count += stack.getCount();
        }
        return count;
    }

    private static JsonArray options(Ingredient ingredient) {
        JsonArray options = new JsonArray();
        ingredient.items().limit(4).forEach(item -> options.add(displayId(BuiltInRegistries.ITEM.getKey(item.value()))));
        return options;
    }

    private static String displayId(Identifier identifier) {
        String text = identifier.toString();
        return WireSize.utf8Bytes(text) <= 128 ? text : WireSize.truncateToBytes(text, 128) + "...[ID已截短]";
    }
}
