package com.neko.mcbot.server.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.McbotMod;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.ItemTransfers;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.task.CompanionScheduler;
import com.neko.mcbot.mixin.ItemTargetAccess;
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
        default boolean eligible() { return true; }
    }

    interface Access {
        boolean busy();
        BlockPos position();
        Container inventory();
        List<GroundItem> items(BlockPos center, int radius);
        default Result guard(BlockPos center) { return null; }
    }

    private record PlayerAccess(CompanionPlayer companion, CompanionScheduler scheduler) implements Access {
        @Override public boolean busy() { return scheduler.busy(companion.getUUID()); }
        @Override public BlockPos position() { return companion.blockPosition(); }
        @Override public Container inventory() { return companion.getInventory(); }
        @Override public Result guard(BlockPos center) {
            var level = companion.level();
            if (!level.isInWorldBounds(center) || !level.getWorldBorder().isWithinBounds(center)) {
                return new Result(false, "DENIED:拾取中心超出世界范围。", null);
            }
            if (!level.isLoaded(center)) return new Result(false, "TARGET_LOST:拾取中心未加载。", null);
            return null;
        }
        @Override public List<GroundItem> items(BlockPos center, int radius) {
            List<GroundItem> result = new ArrayList<>();
            for (var entity : companion.level().getEntitiesOfClass(ItemEntity.class, new AABB(center).inflate(radius))) {
                result.add(new LiveItem(entity, companion, center, radius));
            }
            return result;
        }
    }

    private record LiveItem(ItemEntity entity, CompanionPlayer companion, BlockPos center, int radius) implements GroundItem {
        @Override public boolean eligible() {
            // getOwner() is the thrower, not the pickup target in this version.
            var target = ((ItemTargetAccess) entity).mcbot$getPickupTarget();
            return CollectTool.eligible(target, companion.getUUID(), entity.isAlive(), entity.hasPickUpDelay(),
                    companion.level().isLoaded(entity.blockPosition()), entity.distanceToSqr(companion),
                    entity.distanceToSqr(center.getX() + .5, center.getY() + .5, center.getZ() + .5), radius);
        }
        @Override public ItemStack stack() { return entity.getItem(); }
        @Override public void settle(ItemStack remainder) {
            if (remainder.isEmpty()) entity.discard();
            else entity.setItem(remainder);
        }
    }

    static boolean eligible(java.util.UUID target, java.util.UUID body, boolean alive, boolean delay,
                            boolean loaded, double bodyDistanceSquared, double centerDistanceSquared, int radius) {
        return alive && !delay && loaded && (target == null || target.equals(body))
                && Double.isFinite(bodyDistanceSquared) && bodyDistanceSquared >= 0 && bodyDistanceSquared <= 6.5 * 6.5
                && Double.isFinite(centerDistanceSquared) && centerDistanceSquared >= 0 && centerDistanceSquared <= radius * radius;
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
        BlockPos body = access.position();
        double dx = (double) at.getX() - body.getX(), dy = (double) at.getY() - body.getY(),
                dz = (double) at.getZ() - body.getZ();
        if (dx * dx + dy * dy + dz * dz > 6.5 * 6.5) {
            return new Result(false, "OUT_OF_REACH:拾取中心超过身体6.5格，先靠近；不能远程取物。", null);
        }
        Result guard = access.guard(at);
        if (guard != null) return guard;

        List<String> got = new ArrayList<>();
        List<String> left = new ArrayList<>();
        int collectedCount = 0, remainingCount = 0;
        Container inventory = access.inventory();
        for (GroundItem it : access.items(at, args.radius())) {
            if (!it.eligible()) continue;
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
