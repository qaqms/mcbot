package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.ServerTool;
import net.minecraft.world.entity.player.Inventory;

/** status：同伴自身的"体感"汇报，回执一句人话 + data 供 UI。 */
public final class StatusTool implements ServerTool {

    @Override
    public String name() {
        return "status";
    }

    @Override
    public Result run(CompanionPlayer companion, JsonObject args) {
        var pos = companion.blockPosition();
        float hp = companion.getHealth();
        float food = companion.getFoodData().getFoodLevel();
        Inventory inv = companion.getInventory();
        int used = 0;
        for (int i = 0; i < 36; i++) {
            if (!inv.getItem(i).isEmpty()) {
                used++;
            }
        }
        String held = inv.getSelectedItem().isEmpty() ? "空手"
                : inv.getSelectedItem().getCount() + " × "
                        + inv.getSelectedItem().getItemName().getString();

        JsonObject data = new JsonObject();
        data.addProperty("x", pos.getX());
        data.addProperty("y", pos.getY());
        data.addProperty("z", pos.getZ());
        data.addProperty("dimension", companion.level().dimension().identifier().toString());
        data.addProperty("hp", hp);
        data.addProperty("food", food);
        data.addProperty("slots_used", used);
        data.addProperty("held", held);
        data.addProperty("on_fire", companion.isOnFire());

        String fb = String.format("我在 %s (%d,%d,%d)，生命 %.0f，饥饿 %d，背包 %d/36 格，手持%s%s。",
                data.get("dimension").getAsString(), pos.getX(), pos.getY(), pos.getZ(),
                hp, (int) food, used, held, companion.isOnFire() ? "，身上着火！" : "");
        return new Result(true, fb, data);
    }
}
