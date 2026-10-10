package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.neko.mcbot.McbotMod;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.common.WireSize;
import com.neko.mcbot.mixin.FurnaceAccess;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.task.CompanionScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Only move stacks here. Vanilla owns fuel consumption, cooking time, output and persistence. */
public final class SmeltTool implements ServerTool {
    record Arguments(BlockPos pos, String action, Integer inputSlot, int inputCount,
                     Integer fuelSlot, int fuelCount, int takeSlot, int count) {
    }

    // Replace world observations in offline tests, keeping stack planning and commit on the real path.
    interface Access {
        boolean busy();
        boolean inBounds(BlockPos pos);
        double distanceSquared(BlockPos pos);
        boolean loaded(BlockPos pos);
        Machine machine(BlockPos pos);
        Container inventory();
    }

    interface Machine {
        Container slots();
        BlockPos pos();
        String id();
        boolean locked();
        boolean ticking();
        int counter(int index);
        int burnTicks(ItemStack fuel);
        Cooking recipe(ItemStack input);
    }

    record Cooking(String id, ItemStack output, int ticks) {
    }

    private record PlayerAccess(CompanionPlayer companion, CompanionScheduler scheduler) implements Access {
        @Override public boolean busy() { return scheduler.busy(companion.getUUID()); }
        @Override public boolean inBounds(BlockPos pos) { return companion.level().isInWorldBounds(pos); }
        @Override public double distanceSquared(BlockPos pos) {
            return companion.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        }
        @Override public boolean loaded(BlockPos pos) { return companion.level().isLoaded(pos); }
        @Override public Container inventory() { return companion.getInventory(); }
        @Override public Machine machine(BlockPos pos) {
            var entity = companion.level().getBlockEntity(pos);
            return entity instanceof AbstractFurnaceBlockEntity furnace && supported(furnace)
                    ? new LiveMachine(companion, furnace) : null;
        }
    }

    private record LiveMachine(CompanionPlayer companion, AbstractFurnaceBlockEntity furnace) implements Machine {
        @Override public Container slots() { return furnace; }
        @Override public BlockPos pos() { return furnace.getBlockPos(); }
        @Override public String id() {
            return BuiltInRegistries.BLOCK.getKey(furnace.getBlockState().getBlock()).toString();
        }
        @Override public boolean locked() { return furnace.isLocked(); }
        @Override public boolean ticking() { return companion.level().shouldTickBlocksAt(pos()); }
        @Override public int counter(int index) { return ((FurnaceAccess) furnace).mcbot$data().get(index); }
        @Override public int burnTicks(ItemStack fuel) {
            int ticks = companion.level().fuelValues().burnDuration(fuel);
            return furnace.getClass() == FurnaceBlockEntity.class ? ticks : ticks / 2;
        }
        @Override public Cooking recipe(ItemStack input) {
            if (input.isEmpty()) return null;
            var holder = ((FurnaceAccess) furnace).mcbot$recipeCheck()
                    .getRecipeFor(new SingleRecipeInput(input), companion.level()).orElse(null);
            return cooking(holder, input, companion.level().registryAccess(), companion.level().enabledFeatures());
        }
    }

    @Override
    public String name() {
        return "smelt";
    }

    @Override
    public Result run(CompanionPlayer companion, JsonObject args) {
        return execute(args, new PlayerAccess(companion, McbotMod.scheduler()));
    }

    @Override
    public CompletableFuture<Result> runAsync(CompanionPlayer companion, JsonObject args,
                                               CompanionScheduler scheduler) {
        return CompletableFuture.completedFuture(execute(args, new PlayerAccess(companion, scheduler)));
    }

    static Arguments arguments(JsonObject args) {
        if (args == null) return null;
        Integer x = integer(args, "x", Integer.MIN_VALUE, Integer.MAX_VALUE);
        Integer y = integer(args, "y", Integer.MIN_VALUE, Integer.MAX_VALUE);
        Integer z = integer(args, "z", Integer.MIN_VALUE, Integer.MAX_VALUE);
        if (x == null || y == null || z == null) return null;
        String action = args.has("action") ? text(args, "action") : "query";
        if (action == null || !List.of("query", "load", "take").contains(action)) return null;
        // Reject misplaced fields: a wrong mode must never silently move another slot.
        var allowed = switch (action) {
            case "load" -> List.of("x", "y", "z", "action", "input_slot", "input_count", "fuel_slot", "fuel_count");
            case "take" -> List.of("x", "y", "z", "action", "slot", "count");
            default -> List.of("x", "y", "z", "action");
        };
        if (!allowed.containsAll(args.keySet())) return null;
        Integer input = args.has("input_slot") ? integer(args, "input_slot", 0, 35) : null;
        Integer fuel = args.has("fuel_slot") ? integer(args, "fuel_slot", 0, 35) : null;
        Integer inputCount = args.has("input_count") ? integer(args, "input_count", 1, 64) : Integer.valueOf(1);
        Integer fuelCount = args.has("fuel_count") ? integer(args, "fuel_count", 1, 64) : Integer.valueOf(1);
        if (inputCount == null || fuelCount == null
                || (args.has("input_slot") && input == null) || (args.has("fuel_slot") && fuel == null)
                || (args.has("input_count") && input == null) || (args.has("fuel_count") && fuel == null)) return null;
        if (action.equals("load") && input == null && fuel == null) return null;
        String slot = args.has("slot") ? text(args, "slot") : "output";
        if (slot == null || !List.of("input", "fuel", "output").contains(slot)) return null;
        Integer count = args.has("count") ? integer(args, "count", 1, 64) : Integer.valueOf(64);
        if (count == null) return null;
        return new Arguments(new BlockPos(x, y, z), action, input, inputCount, fuel, fuelCount,
                slot.equals("input") ? 0 : slot.equals("fuel") ? 1 : 2, count);
    }

    private static Integer integer(JsonObject args, String key, int min, int max) {
        var value = args.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        try {
            int number = value.getAsBigDecimal().intValueExact();
            return number >= min && number <= max ? number : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static String text(JsonObject args, String key) {
        var value = args.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    static Result execute(JsonObject json, Access access) {
        Arguments args = arguments(json);
        if (args == null) return failure("DENIED:需要整数 x/y/z；action=query/load/take。load 至少指定"
                + " input_slot 或 fuel_slot（背包 0-35），对应数量 1-64，默认 1；"
                + "take 的 slot=input/fuel/output，count 为最多取出数量 1-64，默认 64。");
        if (!args.action().equals("query") && access.busy()) {
            return failure("BUSY:我正忙着上一件事，等它结束或取消后再装料/取出；query 仍可查看。");
        }
        if (!access.inBounds(args.pos())) return failure("DENIED:机器坐标超出当前世界边界。");
        if (access.distanceSquared(args.pos()) > 5.5 * 5.5) {
            return failure("OUT_OF_REACH:机器不在 5.5 格内，先 move_to 靠近。");
        }
        if (!access.loaded(args.pos())) return failure("TARGET_LOST:机器所在区块未加载，先靠近后查询。");
        Machine machine = access.machine(args.pos());
        if (machine == null) {
            return failure("TARGET_LOST:目标不是原版熔炉/高炉/烟熏炉；先 scan_area 找到机器。");
        }
        if (machine.locked()) return failure("DENIED:机器已锁定，不能通过工具绕过容器锁。");
        if (args.action().equals("load")) return load(access.inventory(), machine, args);
        if (args.action().equals("take")) return take(access.inventory(), machine, args);
        return report(machine, args.action(), "仅查询，未移动物品。", 0, 0, 0);
    }

    static boolean supported(AbstractFurnaceBlockEntity furnace) {
        var block = furnace.getBlockState();
        return (furnace.getClass() == FurnaceBlockEntity.class && block.is(Blocks.FURNACE))
                || (furnace.getClass() == BlastFurnaceBlockEntity.class && block.is(Blocks.BLAST_FURNACE))
                || (furnace.getClass() == SmokerBlockEntity.class && block.is(Blocks.SMOKER));
    }

    private static Result load(Container inventory, Machine machine, Arguments args) {
        Container furnace = machine.slots();
        List<ItemStack> storage = storage(inventory);
        ItemStack input = furnace.getItem(0).copy();
        ItemStack fuel = furnace.getItem(1).copy();
        if (args.inputSlot() != null) {
            ItemStack source = storage.get(args.inputSlot());
            if (source.isEmpty() || source.getCount() < args.inputCount()) {
                return failure("MISSING_MATERIALS:指定原料槽数量不足；用 inventory 核对。本次未移动物品。");
            }
            if (!room(furnace, 0, input, source, args.inputCount())) return slotFull("原料");
            input = combine(input, source, args.inputCount());
            source.shrink(args.inputCount());
        }
        // Staging fuel in an empty machine is safe; do not ignite unsupported input already in it.
        Cooking recipe = machine.recipe(input);
        if (!input.isEmpty() && recipe == null) return failure("NO_RECIPE:该机器没有当前原料的普通烧制配方；"
                + "熔炉/高炉/烟熏炉的配方不同。本次未移动物品。");
        if (args.inputSlot() != null && !outputFits(furnace, recipe.output())) {
            return failure("OUTPUT_BLOCKED:产物槽组件不兼容或已满；先 smelt take 取出原有成品。本次未移动物品。");
        }
        if (args.fuelSlot() != null) {
            // A shared source slot is read after reserving input, so it cannot be spent twice.
            ItemStack source = storage.get(args.fuelSlot());
            if (source.isEmpty() || source.getCount() < args.fuelCount()) {
                return failure("MISSING_MATERIALS:指定燃料槽数量不足（与原料共槽时不能重复计数）；"
                        + "用 inventory 核对。本次未移动物品。");
            }
            if (machine.burnTicks(source) <= 0 || !furnace.canPlaceItem(1, source)) {
                return failure("INVALID_FUEL:该物品不是机器当前可用燃料；空桶是返还物，不能点火。本次未移动物品。");
            }
            if (!room(furnace, 1, fuel, source, args.fuelCount())) return slotFull("燃料");
            fuel = combine(fuel, source, args.fuelCount());
            source.shrink(args.fuelCount());
        }
        if (args.inputSlot() != null && machine.counter(AbstractFurnaceBlockEntity.DATA_LIT_TIME) <= 0
                && machine.burnTicks(fuel) <= 0) {
            return failure("MISSING_FUEL:机器未燃烧且没有可用燃料；load 同时指定 fuel_slot，"
                    + "或先备燃料。本次未移动物品。");
        }
        // Every check precedes commit. No inventory.add(), GUI clicks or overflow drops.
        commit(inventory, storage);
        if (!ItemStack.matches(input, furnace.getItem(0))) furnace.setItem(0, input);
        if (!ItemStack.matches(fuel, furnace.getItem(1))) furnace.setItem(1, fuel);
        furnace.setChanged();
        return report(machine, args.action(), "装料完成，尚未烧出本次成品；"
                        + "机器按原版 tick 自动烧制。", args.inputSlot() == null ? 0 : args.inputCount(),
                args.fuelSlot() == null ? 0 : args.fuelCount(), 0);
    }

    private static Result take(Container inventory, Machine machine, Arguments args) {
        Container furnace = machine.slots();
        ItemStack source = furnace.getItem(args.takeSlot());
        if (source.isEmpty()) {
            Result status = report(machine, args.action(), "", 0, 0, 0);
            return new Result(false, (args.takeSlot() == 2 ? "NOT_READY:产物槽为空；" : "EMPTY_SLOT:指定机器槽为空；")
                    + status.feedback(), status.data());
        }
        int amount = Math.min(args.count(), source.getCount());
        ItemStack moving = source.copyWithCount(amount);
        String description = InventoryTool.describe(moving);
        List<ItemStack> storage = storage(inventory);
        if (!insert(inventory, storage, moving)) return failure("INVENTORY_FULL:背包装不下本次取出数量；"
                + "先 transfer 存箱腾空间，或减小 count。本次未移动物品。");
        ItemStack remaining = source.copy();
        remaining.shrink(amount);
        commit(inventory, storage);
        furnace.setItem(args.takeSlot(), remaining);
        furnace.setChanged();
        return report(machine, args.action(), "已从机器"
                + (args.takeSlot() == 0 ? "原料" : args.takeSlot() == 1 ? "燃料" : "产物")
                + "槽取出 " + description + " 到背包。", 0, 0, amount);
    }

    static Cooking cooking(RecipeHolder<? extends AbstractCookingRecipe> holder, ItemStack input,
                           HolderLookup.Provider registries, FeatureFlagSet enabledFeatures) {
        if (holder == null || input.isEmpty()) return null;
        var value = holder.value();
        if (value.getClass() != SmeltingRecipe.class && value.getClass() != BlastingRecipe.class
                && value.getClass() != SmokingRecipe.class) return null;
        ItemStack output = value.assemble(new SingleRecipeInput(input), registries);
        // Vanilla only grows existing results by one, even for a multi-count datapack result.
        // Reject those recipes rather than promise a yield the real machine cannot conserve.
        return output.isEmpty() || output.getCount() != 1 || value.cookingTime() < 1
                || !output.isItemEnabled(enabledFeatures) ? null
                : new Cooking(holder.id().identifier().toString(), output, value.cookingTime());
    }

    private static boolean room(Container furnace, int slot, ItemStack at,
                                ItemStack source, int count) {
        return furnace.canPlaceItem(slot, source)
                && (at.isEmpty() || ItemStack.isSameItemSameComponents(at, source))
                && (long) at.getCount() + count <= furnace.getMaxStackSize(source);
    }

    private static ItemStack combine(ItemStack at, ItemStack source, int count) {
        if (at.isEmpty()) return source.copyWithCount(count);
        at.grow(count);
        return at;
    }

    private static boolean outputFits(Container furnace, ItemStack output) {
        ItemStack at = furnace.getItem(2);
        return !output.isEmpty() && (at.isEmpty() || ItemStack.isSameItemSameComponents(at, output))
                && (long) at.getCount() + output.getCount() <= furnace.getMaxStackSize(output);
    }

    private static Result report(Machine machine,
                                 String action, String prefix, int loadedInput, int loadedFuel, int taken) {
        Container furnace = machine.slots();
        ItemStack input = furnace.getItem(0);
        ItemStack fuel = furnace.getItem(1);
        ItemStack output = furnace.getItem(2);
        int burn = machine.counter(AbstractFurnaceBlockEntity.DATA_LIT_TIME);
        int totalBurn = machine.counter(AbstractFurnaceBlockEntity.DATA_LIT_DURATION);
        int progress = machine.counter(AbstractFurnaceBlockEntity.DATA_COOKING_PROGRESS);
        int total = machine.counter(AbstractFurnaceBlockEntity.DATA_COOKING_TOTAL_TIME);
        Cooking recipe = machine.recipe(input);
        ItemStack planned = recipe == null ? ItemStack.EMPTY : recipe.output();
        boolean ticking = machine.ticking();
        int fuelTicks = machine.burnTicks(fuel);
        long availableFuel = (long) burn + (long) fuelTicks * fuel.getCount();
        String state = input.isEmpty() ? "EMPTY_INPUT" : recipe == null ? "NO_RECIPE"
                : !outputFits(furnace, planned) ? "OUTPUT_BLOCKED"
                : burn <= 0 && fuelTicks <= 0 ? "MISSING_FUEL"
                : !ticking ? "NOT_TICKING" : "COOKING";
        // The first item's live total can differ from a reloaded recipe's time.
        long neededTicks = recipe == null ? 0 : Math.max(0, (long) (total > 0 ? total : recipe.ticks()) - progress)
                + (long) recipe.ticks() * (input.getCount() - 1);
        int wait = recipe == null ? 0 : (int) Math.min(60, Math.max(1, (neededTicks + 19) / 20));
        boolean enoughFuel = recipe != null && availableFuel >= neededTicks;
        JsonObject data = new JsonObject();
        data.addProperty("action", action);
        var pos = machine.pos();
        data.addProperty("x", pos.getX());
        data.addProperty("y", pos.getY());
        data.addProperty("z", pos.getZ());
        data.addProperty("machine", machine.id());
        data.add("input", InventoryTool.stackData(input));
        data.add("fuel", InventoryTool.stackData(fuel));
        data.add("output", InventoryTool.stackData(output));
        data.addProperty("burn_remaining_ticks", burn);
        data.addProperty("burn_total_ticks", totalBurn);
        data.addProperty("cook_progress_ticks", progress);
        data.addProperty("cook_total_ticks", total);
        data.addProperty("fuel_ticks_per_item", fuelTicks);
        data.addProperty("available_fuel_ticks", availableFuel);
        data.addProperty("needed_cook_ticks", neededTicks);
        data.addProperty("fuel_sufficient_for_input", enoughFuel);
        data.addProperty("ticking", ticking);
        data.addProperty("state", state);
        data.addProperty("suggested_wait_seconds", state.equals("COOKING") ? wait : 0);
        data.addProperty("loaded_input_count", loadedInput);
        data.addProperty("loaded_fuel_count", loadedFuel);
        data.addProperty("taken_count", taken);
        String recipeText = "";
        if (recipe != null) {
            String id = WireSize.truncateToBytes(recipe.id(), 128);
            data.addProperty("recipe", id);
            data.addProperty("recipe_id_truncated", !id.equals(recipe.id()));
            data.add("result_per_input", InventoryTool.stackData(planned));
            data.addProperty("recipe_cook_ticks", recipe.ticks());
            recipeText = "配方 " + id + "，每个原料产出 " + InventoryTool.describe(planned)
                    + "，需 " + recipe.ticks() + " tick。当前原料预计还需 " + neededTicks
                    + " tick，可用燃烧 " + availableFuel + " tick"
                    + (enoughFuel ? "（燃料预计够用）。" : "（燃料不足以完成整批，需补充）。");
        }
        String next = switch (state) {
            case "COOKING" -> "建议 wait " + wait + " 秒后 query/take；时间按 20 TPS 估算，"
                    + "不能将等待结束当作烧制成功。";
            case "MISSING_FUEL" -> "MISSING_FUEL:用 load 的 fuel_slot 补充燃料。";
            case "OUTPUT_BLOCKED" -> "OUTPUT_BLOCKED:先 take 产物，再继续烧制。";
            case "NO_RECIPE" -> "NO_RECIPE:取回原料，核对物品或更换机器。";
            case "NOT_TICKING" -> "机器区块当前未推进；保持附近区块加载后再查询。";
            default -> "原料槽为空；可 take 已有产物/燃料，或准备下一批原料。";
        };
        return new Result(true, prefix + "机器 @(" + pos.toShortString() + ")；原料 "
                + InventoryTool.describe(input) + "；燃料 " + InventoryTool.describe(fuel)
                + "；产物 " + InventoryTool.describe(output) + "；烧制进度 " + progress + "/" + total
                + " tick，剩余燃烧 " + burn + " tick；状态 " + state + "。" + recipeText + next
                + "取消 wait/任务不会熄灭机器；需停止后续烧制时显式 take 原料，燃烧余量仍按原版消耗。", data);
    }

    private static List<ItemStack> storage(Container inv) {
        List<ItemStack> result = new ArrayList<>();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) result.add(inv.getItem(slot).copy());
        return result;
    }

    private static void commit(Container inv, List<ItemStack> storage) {
        for (int slot = 0; slot < storage.size(); slot++) {
            if (!ItemStack.matches(inv.getItem(slot), storage.get(slot))) inv.setItem(slot, storage.get(slot));
        }
        inv.setChanged();
    }

    private static boolean insert(Container inventory, List<ItemStack> storage, ItemStack need) {
        for (ItemStack at : storage) {
            if (need.isEmpty()) return true;
            if (at.isEmpty() || !ItemStack.isSameItemSameComponents(at, need)) continue;
            int amount = Math.min(Math.max(0, inventory.getMaxStackSize(at) - at.getCount()), need.getCount());
            at.grow(amount);
            need.shrink(amount);
        }
        for (int slot = 0; slot < storage.size() && !need.isEmpty(); slot++) {
            if (storage.get(slot).isEmpty()) storage.set(slot, need.split(inventory.getMaxStackSize(need)));
        }
        return need.isEmpty();
    }

    private static Result slotFull(String slot) {
        return failure("SLOT_BLOCKED:" + slot + "槽组件不兼容或装不下指定数量；先 take 回收该槽或减小数量。"
                + "本次未移动物品。");
    }

    private static Result failure(String text) {
        return new Result(false, text, null);
    }
}
