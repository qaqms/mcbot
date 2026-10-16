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
    private final Map<String, JsonObject> origins = new LinkedHashMap<>();
    private long untracked;
    private boolean nativeAccounting;
    private boolean accountingGap;

    public void record(JsonObject removal) {
        if (removal.has("drop_accounting_known")) {
            nativeAccounting = true;
            if (!removal.get("drop_accounting_known").getAsBoolean()) {
                accountingGap = true;
                return;
            }
            var report = removal.getAsJsonObject("drop_accounting");
            String key = report.get("ledger_id").getAsString() + ":" + report.get("origin_id").getAsLong();
            if (origins.containsKey(key)) return;
            if (origins.size() < MAX_TRACKED) {
                var token = new JsonObject();
                for (String field : java.util.List.of("ledger_id", "origin_id", "spawned_count"))
                    token.add(field, report.get(field).deepCopy());
                origins.put(key, token);
            }
            else { untracked += report.get("spawned_count").getAsLong(); accountingGap = true; }
            return;
        }
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
        if (nativeAccounting) {
            DropTracker.tick(level);
            return snapshotSources(report -> DropTracker.report(level,
                    UUID.fromString(report.get("ledger_id").getAsString()), report.get("origin_id").getAsLong()));
        }
        return snapshot(id -> {
            var entity = level.getEntity(id);
            return entity instanceof ItemEntity item && item.isAlive()
                    && level.isLoaded(item.blockPosition()) ? GroundItems.describe(item) : null;
        });
    }

    public JsonObject snapshotSources(Function<JsonObject, JsonObject> lookup) {
        long spawned = untracked, live = 0, self = 0, other = 0, destroyed = 0, unresolved = untracked;
        boolean complete = !accountingGap;
        var drops = new JsonArray();
        var summaries = new JsonArray();
        for (var token : origins.values()) {
            var report = lookup.apply(token.deepCopy());
            spawned += token.get("spawned_count").getAsLong();
            if (report == null) {
                unresolved += token.get("spawned_count").getAsLong();
                complete = false;
                continue;
            }
            live += report.get("live_count").getAsLong();
            self += report.get("picked_up_self_count").getAsLong();
            other += report.get("picked_up_other_count").getAsLong();
            destroyed += report.get("destroyed_count").getAsLong();
            unresolved += report.get("unresolved_count").getAsLong();
            complete &= report.get("accounting_complete").getAsBoolean();
            if (report.has("live_drops")) for (var drop : report.getAsJsonArray("live_drops")) {
                if (drops.size() >= 16) break;
                var item = drop.getAsJsonObject().deepCopy();
                item.addProperty("observed_recorded_count", item.get("recorded_count").getAsInt());
                item.addProperty("origin_id", report.get("origin_id").getAsLong());
                drops.add(item);
            }
            if (summaries.size() < 16) {
                var summary = report.deepCopy();
                summary.remove("spawns"); summary.remove("events"); summary.remove("live_drops");
                summary.addProperty("details_omitted", true);
                summaries.add(summary);
            }
        }
        var data = new JsonObject();
        data.addProperty("observed_drop_items", spawned);
        data.addProperty("remaining_items", live);
        data.addProperty("picked_up_self_items", self);
        data.addProperty("picked_up_other_items", other);
        data.addProperty("destroyed_drop_items", destroyed);
        data.addProperty("unresolved_drop_items", unresolved);
        data.addProperty("remaining_items_complete", complete);
        data.addProperty("drop_accounting", "native_origin_events");
        data.addProperty("drop_origins_truncated", summaries.size() < origins.size());
        data.addProperty("remaining_drops_truncated", drops.asList().stream()
                .mapToLong(v -> v.getAsJsonObject().get("recorded_count").getAsLong()).sum() < live);
        data.add("drop_origins", summaries);
        data.add("remaining_drops", drops);
        return data;
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
        long historical = snapshot.get("observed_drop_items").getAsLong();
        if ("native_origin_events".equals(snapshot.get("drop_accounting").getAsString()))
            return " 来源账记录生成 " + historical + " 个，当前在地 "
                    + snapshot.get("remaining_items") + "、本身体原版拾取 "
                    + snapshot.get("picked_up_self_items") + "、其他实体拾取 "
                    + snapshot.get("picked_up_other_items") + "、损毁 " + snapshot.get("destroyed_drop_items")
                    + "、未核实 " + snapshot.get("unresolved_drop_items")
                    + "；结算完整=" + snapshot.get("remaining_items_complete")
                    + "。未知不等于零掉落；最终成果仍须核对背包。";
        if (historical == 0) return "";
        return " 移除时历史观测掉落 " + historical + " 个；此刻记录实体仍可见 "
                + snapshot.get("remaining_items").getAsInt() + " 个，去向未核实 "
                + snapshot.get("unresolved_drop_items").getAsInt()
                + " 个（可能拾取、合并、消失或离开加载区）；入包成果须核对背包。";
    }
}
