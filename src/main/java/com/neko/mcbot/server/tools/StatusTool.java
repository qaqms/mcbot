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
                       boolean onFire, long gameTick, boolean onGround, boolean inWater,
                       boolean eyesInWater, boolean inLava, int air, int maxAir, String recovery) {
        public Body(double x, double y, double z, String dimension, float hp, int food,
                    boolean onFire, long gameTick) {
            this(x, y, z, dimension, hp, food, onFire, gameTick, false, false, false, false, 300, 300, "idle");
        }
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
        var task = scheduler.observation(companion.getUUID());
        task.addProperty("body_owner", companion.bodyControl().owner().name());
        Result result = report(args,
                new Body(companion.getX(), companion.getY(), companion.getZ(),
                        companion.level().dimension().identifier().toString(), companion.getHealth(),
                        companion.getFoodData().getFoodLevel(), companion.isOnFire(), companion.level().getGameTime(),
                        companion.onGround(), companion.isInWater(),
                        companion.isEyeInFluid(net.minecraft.tags.FluidTags.WATER), companion.isInLava(),
                        companion.getAirSupply(), companion.getMaxAirSupply(), companion.waterRecoveryState()),
                companion.getInventory(), task);
        if (result.ok()) {
            result.data().addProperty("body_id", companion.observationBodyId().toString());
            result.data().addProperty("body_continuity_id", companion.bodyContinuityId().toString());
            result.data().addProperty("owner_uuid", companion.ownerUuid().toString());
            result.data().addProperty("companion_uuid", companion.getUUID().toString());
            var records = McbotMod.buildRecords(companion.level().getServer());
            if (records != null && records.healthy()) result.data().addProperty("world_id", records.worldId().toString());
            result.data().addProperty("alive", companion.isAlive() && !companion.lifecycle().retired());
            result.data().add("body_lifecycle", companion.lifecycle().observation());
            result.data().addProperty("body_state", companion.lifecycle().state());
            result.data().add("last_death", companion.deathObservation());
            var recent = companion.recentBodyChanges();
            result.data().add("body_changes", recent);
            result = defenseReport(result, companion.defense().observation());
            result = environmentReport(result, companion.environmentObservation());
            result = maintenanceReport(result, companion.maintenance().observation());
            result.data().addProperty("saturation", companion.getFoodData().getSaturationLevel());
            result.data().addProperty("using_item", companion.isUsingItem());
            result.data().add("drop_changes", com.neko.mcbot.server.DropTracker.recent(companion));
            result.data().add("spatial_facts", com.neko.mcbot.server.TerrainSnapshots.around(
                    companion.level(), companion.blockPosition(), 64));
            if (args.has("recovery") && args.get("recovery").getAsBoolean()) {
                try {
                    result.data().add("recovery_spatial", com.neko.mcbot.server.SpatialCheckpoint.snapshot(companion));
                    result.data().addProperty("recovery_spatial_known", true);
                } catch (RuntimeException unavailable) {
                    result.data().addProperty("recovery_spatial_known", false);
                }
            }
            result = new Result(true, result.feedback()
                    + com.neko.mcbot.body.BodyChangeLog.sentence(recent), result.data());
        }
        return java.util.concurrent.CompletableFuture.completedFuture(result);
    }

    @Override public java.util.concurrent.CompletableFuture<Result> runAuthorized(
            CompanionPlayer companion, JsonObject args, CompanionScheduler scheduler,
            ActionPermissions permissions, ActionPermissions.Context context) {
        companion.bindSpatialTask(context);
        return runAsync(companion, args, scheduler).thenApply(result -> {
            if (result.ok()) result.data().add("navigation_trail", companion.navigationTrail().report());
            if (result.ok()) result.data().add("build_site", companion.buildSite().report(
                    new com.neko.mcbot.server.BuildSiteAccess(companion)));
            return permissionReport(result, context);
        });
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

    static Result defenseReport(Result result, JsonObject defense) {
        var data = result.data();
        data.add("body_defense", defense);
        data.addProperty("defense_active", defense.get("active").getAsBoolean());
        data.addProperty("defense_state", defense.get("state").getAsString());
        data.addProperty("threat_scan_complete", defense.getAsJsonObject("threats").get("scan_complete").getAsBoolean());
        return new Result(result.ok(), result.feedback() + "\n身体自保：" + defense.get("state").getAsString()
                + "，活动=" + defense.get("active").getAsBoolean() + "；详情见body_defense，局部拉开不代表甩掉追兵。", data);
    }

    static Result environmentReport(Result result, JsonObject environment) {
        var data = result.data();
        data.add("body_environment", environment);
        data.addProperty("environment_active", environment.get("active").getAsBoolean());
        data.addProperty("environment_state", environment.get("state").getAsString());
        data.addProperty("environment_work_safe", environment.get("work_safe").getAsBoolean());
        return new Result(result.ok(), result.feedback() + "\n环境避险：" + environment.get("state").getAsString()
                + "，工作安全=" + environment.get("work_safe").getAsBoolean()
                + "；空气与局部恢复不验收外层目标。", data);
    }
    static Result maintenanceReport(Result result, JsonObject maintenance) {
        var data = result.data();
        data.add("body_maintenance", maintenance);
        data.addProperty("maintenance_active", maintenance.get("active").getAsBoolean());
        data.addProperty("maintenance_state", maintenance.get("state").getAsString());
        return new Result(result.ok(), result.feedback() + "\n身体补给：" + maintenance.get("state").getAsString()
                + "，活动=" + maintenance.get("active").getAsBoolean()
                + "；只用已有库存，实际消费见body_maintenance，原目标另行核验。", data);
    }
    static Result report(JsonObject args, Body body, Inventory inventory, JsonObject task) {
        for (String key : args.keySet()) if (!key.equals("details") && !key.equals("recovery")) return denied();
        if (args.has("details") && (!args.get("details").isJsonPrimitive()
                || !args.get("details").getAsJsonPrimitive().isBoolean())) return denied();
        if (args.has("recovery") && (!args.get("recovery").isJsonPrimitive()
                || !args.get("recovery").getAsJsonPrimitive().isBoolean())) return denied();
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
        data.addProperty("on_ground", body.onGround());
        data.addProperty("in_water", body.inWater());
        data.addProperty("eyes_in_water", body.eyesInWater());
        data.addProperty("in_lava", body.inLava());
        data.addProperty("air_supply", body.air());
        data.addProperty("max_air_supply", body.maxAir());
        data.addProperty("water_recovery", body.recovery());
        data.add("task", task.deepCopy());
        String feedback = "我在 " + body.dimension() + " (" + body.x() + "," + body.y() + "," + body.z()
                + ")，生命 " + body.hp() + "，饥饿 " + body.food()
                + "，背包 " + data.get("slots_used").getAsInt() + "/36 格，手持" + held
                + (body.onFire() ? "，身上着火！" : "") + "。观测游戏刻 " + body.gameTick()
                + "；身体接地=" + body.onGround() + "、入水=" + body.inWater()
                + "、眼睛没水=" + body.eyesInWater() + "、岩浆=" + body.inLava()
                + "、原版空气读数=" + body.air() + "/" + body.maxAir() + "、水中避险=" + body.recovery()
                + "；当前身体任务 " + task + "（耗时不是完成率）。";
        if (details) {
            data.add("inventory", contents.data());
            feedback += "\n" + contents.feedback();
        }
        return new Result(true, feedback, data);
    }

    private static Result denied() {
        return new Result(false, "DENIED:status 只接受可选布尔 details 和内部 recovery。", null);
    }
}
