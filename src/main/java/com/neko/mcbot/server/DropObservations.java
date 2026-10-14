package com.neko.mcbot.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Fresh re-observation of bounded drop identities. Disappearance is never proof of pickup. */
public final class DropObservations {
    public static final int MAX_TRACKED = 256;
    private final Map<UUID, Integer> counts = new LinkedHashMap<>();
    private int historical;

    public void record(JsonObject removal) {
        historical += removal.get("remaining_count").getAsInt();
        var drops = removal.getAsJsonArray("drops");
        if (drops == null) return;
        for (var value : drops) {
            var drop = value.getAsJsonObject();
            UUID id = UUID.fromString(drop.get("target_uuid").getAsString());
            int count = drop.get("count").getAsInt();
            if (count > 0 && (counts.containsKey(id) || counts.size() < MAX_TRACKED))
                counts.merge(id, count, Math::max);
        }
    }

    public JsonObject snapshot(ServerLevel level) {
        return snapshot(id -> {
            var entity = level.getEntity(id);
            return entity instanceof ItemEntity item && item.isAlive()
                    && level.isLoaded(item.blockPosition()) ? GroundItems.describe(item) : null;
        });
    }

    public JsonObject snapshot(Function<UUID, JsonObject> lookup) {
        int visible = 0;
        var drops = new JsonArray();
        for (var entry : counts.entrySet()) {
            var drop = lookup.apply(entry.getKey());
            if (drop == null) continue;
            int count = Math.min(entry.getValue(), Math.max(0, drop.get("count").getAsInt()));
            visible += count;
            if (count > 0 && drops.size() < 16) {
                var observation = drop.deepCopy();
                observation.addProperty("observed_recorded_count", count);
                drops.add(observation);
            }
        }
        var data = new JsonObject();
        data.addProperty("observed_drop_items", historical);
        data.addProperty("remaining_items", visible);
        data.addProperty("unresolved_drop_items", Math.max(0, historical - visible));
        data.addProperty("remaining_items_complete", historical == visible);
        data.addProperty("drop_accounting", "bounded_identity_observation");
        data.add("remaining_drops", drops);
        return data;
    }

    public static String sentence(JsonObject snapshot) {
        int historical = snapshot.get("observed_drop_items").getAsInt();
        if (historical == 0) return "";
        return " 移除时历史观测掉落 " + historical + " 个；此刻记录实体仍可见 "
                + snapshot.get("remaining_items").getAsInt() + " 个，去向未核实 "
                + snapshot.get("unresolved_drop_items").getAsInt()
                + " 个（可能拾取、合并、消失或离开加载区）；入包成果须核对背包。";
    }
}
