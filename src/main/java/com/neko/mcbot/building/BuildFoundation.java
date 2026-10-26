package com.neko.mcbot.building;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

import java.util.TreeMap;
import java.util.function.Function;

/** Whole-drawing foundation facts, independent of candidate pages and current hand reach. */
final class BuildFoundation {
    private static final int MATERIAL_SAMPLES = 8;
    private static final class Need {
        final Item item;
        int required, minimum = Integer.MAX_VALUE;
        Need(Item item) { this.item = item; }
    }

    private BuildFoundation() { }

    static JsonObject observe(BuildTarget target, BuildSurvey.Access access, Function<BlockPos, BlockState> states) {
        int y = target.cells().stream().filter(c -> BuildSite.restorable(c.desired()))
                .mapToInt(c -> c.pos().getY()).min().orElse(Integer.MAX_VALUE);
        var needs = new TreeMap<String, Need>();
        int total = 0, unfinished = 0, repairs = 0;
        boolean known = true;
        for (var cell : target.cells()) {
            if (cell.pos().getY() != y || !BuildSite.restorable(cell.desired())) continue;
            total++;
            var actual = states.apply(cell.pos());
            known &= actual != null;
            if (cell.matches(actual)) continue;
            // A passage opened in finished work retains its existing repair/exit lifecycle.
            if (access.repairPending(cell.pos())) { repairs++; continue; }
            unfinished++;
            var material = cell.material();
            if (material.supported() && material.item() != null && material.units() > 0) {
                var need = needs.computeIfAbsent(BuiltInRegistries.ITEM.getKey(material.item()).toString(),
                        ignored -> new Need(material.item()));
                need.required += material.units();
                need.minimum = Math.min(need.minimum, material.units());
            }
        }
        var materials = new JsonArray();
        boolean payable = false;
        int omitted = 0;
        for (var entry : needs.entrySet()) {
            var need = entry.getValue();
            int available = BuildInventory.available(access.placement().inventory(), need.item);
            payable |= available >= need.minimum;
            if (materials.size() >= MATERIAL_SAMPLES) { omitted++; continue; }
            var item = new JsonObject();
            item.addProperty("item", entry.getKey());
            item.addProperty("required", need.required);
            item.addProperty("available", available);
            item.addProperty("shortfall", Math.max(0, need.required - available));
            materials.add(item);
        }
        var data = new JsonObject();
        data.addProperty("active", unfinished > 0);
        if (total > 0) data.addProperty("y", y);
        data.addProperty("total", total);
        data.addProperty("unfinished", unfinished);
        data.addProperty("repairs_pending", repairs);
        data.addProperty("known", known);
        data.addProperty("supply_required", known && unfinished > 0 && !needs.isEmpty() && !payable);
        data.add("materials", materials);
        data.addProperty("materials_omitted", omitted);
        return data;
    }

    static boolean allows(JsonObject foundation, BlockPos pos) {
        return foundation == null || !foundation.get("active").getAsBoolean()
                || pos.getY() <= foundation.get("y").getAsInt();
    }
}
