package com.neko.mcbot.server.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.McbotMod;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.ItemTransfers;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.task.CompanionScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** collect：把指定点（默认为自己脚下）半径内掉落地上的物品吸进背包。 */
public final class CollectTool implements ServerTool {
    record Arguments(BlockPos center, int radius) {
    }

    interface GroundItem {
        ItemStack stack();
        void settle(ItemStack remainder);
    }

    interface Access {
        boolean busy();
        BlockPos position();
        Container inventory();
        List<GroundItem> items(BlockPos center, int radius);
    }

    private record PlayerAccess(CompanionPlayer companion, CompanionScheduler scheduler) implements Access {
        @Override public boolean busy() { return scheduler.busy(companion.getUUID()); }
        @Override public BlockPos position() { return companion.blockPosition(); }
        @Override public Container inventory() { return companion.getInventory(); }
        @Override public List<GroundItem> items(BlockPos center, int radius) {
            List<GroundItem> result = new ArrayList<>();
            for (var entity : companion.level().getEntitiesOfClass(ItemEntity.class, new AABB(center).inflate(radius))) {
                result.add(new LiveItem(entity));
            }
            return result;
        }
    }

    private record LiveItem(ItemEntity entity) implements GroundItem {
        @Override public ItemStack stack() { return entity.getItem(); }
        @Override public void settle(ItemStack remainder) {
            if (remainder.isEmpty()) entity.discard();
            else entity.setItem(remainder);
        }
    }

    @Override
    public String name() {
        return "collect";
    }

    @Override
    public Result run(CompanionPlayer c, JsonObject args) {
        return execute(args, new PlayerAccess(c, McbotMod.scheduler()));
    }

    @Override
    public CompletableFuture<Result> runAsync(CompanionPlayer c, JsonObject args, CompanionScheduler scheduler) {
        return CompletableFuture.completedFuture(execute(args, new PlayerAccess(c, scheduler)));
    }

    static Arguments arguments(JsonObject args) {
        if (args == null) return null;
        boolean hasCenter = args.has("x") || args.has("y") || args.has("z");
        BlockPos center = null;
        if (hasCenter) {
            Integer x = integer(args, "x"), y = integer(args, "y"), z = integer(args, "z");
            if (x == null || y == null || z == null) return null;
            center = new BlockPos(x, y, z);
        }
        Integer radius = args.has("r") ? integer(args, "r") : Integer.valueOf(3);
        return radius == null || radius < 1 || radius > 12 ? null : new Arguments(center, radius);
    }

    private static Integer integer(JsonObject args, String key) {
        var value = args.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    static Result execute(JsonObject json, Access access) {
        Arguments args = arguments(json);
        if (args == null) return new Result(false, "DENIED:拾取中心须完整整数 x/y/z 或全部省略，r 为 1-12 整数。", null);
        if (access.busy()) {
            return new Result(false, "BUSY:我正忙着上一件事，等它结束或取消后再拾取。", null);
        }
        BlockPos at = args.center() == null ? access.position() : args.center();

        List<String> got = new ArrayList<>();
        List<String> left = new ArrayList<>();
        int collectedCount = 0, remainingCount = 0;
        Container inventory = access.inventory();
        for (GroundItem it : access.items(at, args.radius())) {
            ItemStack stack = it.stack();
            if (stack.isEmpty()) {
                continue;
            }
            var movement = ItemTransfers.receive(inventory, stack, it::settle);
            collectedCount += movement.moved();
            remainingCount += movement.remainder().getCount();
            if (movement.moved() > 0) {
                if (got.size() < 32) got.add(InventoryTool.describe(stack.copyWithCount(movement.moved())));
            }
            if (!movement.remainder().isEmpty() && left.size() < 32) left.add(InventoryTool.describe(movement.remainder()));
        }
        JsonObject data = new JsonObject();
        JsonArray arr = new JsonArray();
        got.forEach(arr::add);
        data.add("collected", arr);
        data.addProperty("collected_count", collectedCount);
        data.addProperty("remaining_count", remainingCount);
        data.addProperty("partial", collectedCount > 0 && remainingCount > 0);
        if (collectedCount == 0 && remainingCount == 0) {
            return new Result(true, "半径 " + args.radius() + " 内地上没有可捡的东西。", data);
        }
        return new Result(remainingCount == 0,
                "捡起 " + collectedCount + " 个：" + String.join("、", got)
                        + "（明细最多 32 项）。"
                        + (remainingCount == 0 ? "" : "仍有 " + remainingCount + " 个留在地上，背包满了捡不下："
                        + String.join("、", left) + "（明细最多 32 项）；先腾出空间再拾取。"), data);
    }
}
