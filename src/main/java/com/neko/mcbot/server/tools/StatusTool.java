package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.neko.mcbot.McbotMod;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.server.ActionPermissions;
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
        companion.observeHealth(com.neko.mcbot.body.HealthObservation.Phase.STATUS);
        return java.util.concurrent.CompletableFuture.completedFuture(report(args,
                new Body(companion.getX(), companion.getY(), companion.getZ(),
                        companion.level().dimension().identifier().toString(), companion.getHealth(),
                        companion.getFoodData().getFoodLevel(), companion.isOnFire(), companion.level().getGameTime()),
                companion.getInventory(), scheduler.observation(companion.getUUID())));
    }

    @Override public java.util.concurrent.CompletableFuture<Result> runAuthorized(
            CompanionPlayer companion, JsonObject args, CompanionScheduler scheduler,
            ActionPermissions permissions, ActionPermissions.Context context) {
        return runAsync(companion, args, scheduler).thenApply(result -> permissionReport(result, context));
    }

    static Result permissionReport(Result result, ActionPermissions.Context context) {
        if (!result.ok()) return result;
        var data = result.data().deepCopy();
        data.addProperty("permission_mode", context == null ? "none" : context.mode());
        String mode = context == null ? "未建立动作任务"
                : context.readOnly() ? "只读"
                : context.autonomous() ? "自主执行，动作无需主人逐次批准" : "逐次确认，需批准的动作按服务端清单询问";
        return new Result(true, result.feedback() + "\n服务端任务权限：" + mode + "。", data);
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
