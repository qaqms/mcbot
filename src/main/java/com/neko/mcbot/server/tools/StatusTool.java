package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.neko.mcbot.McbotMod;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.task.CompanionScheduler;
import net.minecraft.world.entity.player.Inventory;

/** status：同伴自身的"体感"汇报，回执一句人话 + data 供 UI。 */
public final class StatusTool implements ServerTool {
    public record Body(double x, double y, double z, String dimension, float hp, int food,
                       boolean onFire, long gameTick) {
    }

    @Override
    public String name() {
        return "status";
    }

    @Override
    public Result run(CompanionPlayer companion, JsonObject args) {
        return runAsync(companion, args, McbotMod.scheduler()).join();
    }

    @Override public java.util.concurrent.CompletableFuture<Result> runAsync(
            CompanionPlayer companion, JsonObject args, CompanionScheduler scheduler) {
        return java.util.concurrent.CompletableFuture.completedFuture(report(args,
                new Body(companion.getX(), companion.getY(), companion.getZ(),
                        companion.level().dimension().identifier().toString(), companion.getHealth(),
                        companion.getFoodData().getFoodLevel(), companion.isOnFire(), companion.level().getGameTime()),
                companion.getInventory(), scheduler.observation(companion.getUUID())));
    }

    static Result report(JsonObject args, Body body, Inventory inventory, JsonObject task) {
        for (String key : args.keySet()) if (!key.equals("details")) return denied();
        if (args.has("details") && (!args.get("details").isJsonPrimitive()
                || !args.get("details").getAsJsonPrimitive().isBoolean())) return denied();
        boolean details = args.has("details") && args.get("details").getAsBoolean();
        Result contents = InventoryTool.report(inventory);
        var data = new JsonObject();
        data.addProperty("x", (int) Math.floor(body.x()));
        data.addProperty("y", (int) Math.floor(body.y()));
        data.addProperty("z", (int) Math.floor(body.z()));
        data.addProperty("dimension", body.dimension());
        data.addProperty("hp", body.hp());
        data.addProperty("food", body.food());
        data.addProperty("slots_used", contents.data().get("slots_used").getAsInt());
        String held = InventoryTool.describe(inventory.getSelectedItem());
        data.addProperty("held", held);
        data.addProperty("on_fire", body.onFire());
        data.addProperty("game_tick", body.gameTick());
        data.add("task", task.deepCopy());
        String feedback = "我在 " + body.dimension() + " (" + body.x() + "," + body.y() + "," + body.z()
                + ")，生命 " + body.hp() + "，饥饿 " + body.food()
                + "，背包 " + data.get("slots_used").getAsInt() + "/36 格，手持" + held
                + (body.onFire() ? "，身上着火！" : "") + "。观测游戏刻 " + body.gameTick()
                + "；当前身体任务 " + task + "（耗时不是完成率）。";
        if (details) {
            data.add("inventory", contents.data());
            feedback += "\n" + contents.feedback();
        }
        return new Result(true, feedback, data);
    }

    private static Result denied() {
        return new Result(false, "DENIED:status 只接受可选布尔 details。", null);
    }
}
