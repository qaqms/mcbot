package com.neko.mcbot.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.common.WireSize;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Bounded provenance quantities. Mixed partial transfers lose attribution rather than invent it. */
public final class DropLedger {
    public static final int MAX_ORIGINS = 128;
    public static final int MAX_ENTITIES = 256;
    public static final int MAX_PARTS = 512;
    public static final long RETAIN_TICKS = 2400;
    public enum Availability { LIVE, UNLOADED, MISSING }
    public enum Relation { SOURCE_CELL, OTHER_CELL, OPERATION_ONLY }
    public enum Outcome { PICKUP, DESTROYED, UNKNOWN }
    public record Picker(UUID actor, UUID body) { }
    public record Sample(UUID id, ItemStack stack, JsonObject observation, Availability availability) {
        public Sample {
            stack = stack.copy();
            observation = observation.deepCopy();
        }
    }
    private record Part(long origin, Relation relation) { }
    private static final class StackRecord {
        final ItemStack unit;
        int seen;
        JsonObject at;
        final Map<Part, Integer> parts = new LinkedHashMap<>();
        StackRecord(Sample sample) {
            unit = sample.stack.copyWithCount(1);
            seen = sample.stack.getCount();
            at = sample.observation.deepCopy();
        }
        int attributed() { return parts.values().stream().mapToInt(Integer::intValue).sum(); }
    }
    public static final class Origin {
        private final long id;
        private final UUID actor;
        private final UUID body;
        private final BlockPos source;
        private final String role;
        private final long tick;
        private long spawned, self, other, destroyed, unknown;
        private boolean limited, expired, spawnsTruncated, eventsTruncated;
        private final JsonArray spawns = new JsonArray();
        private final JsonArray events = new JsonArray();
        private Origin(long id, UUID actor, UUID body, BlockPos source, String role, long tick) {
            this.id = id; this.actor = actor; this.body = body; this.source = source.immutable();
            this.role = role; this.tick = tick;
        }
        public long id() { return id; }
    }

    private final UUID identity = UUID.randomUUID();
    private long nextId;
    private boolean gapReported;
    private final Map<Long, Origin> origins = new LinkedHashMap<>();
    private final Map<UUID, StackRecord> entities = new LinkedHashMap<>();

    public Origin begin(UUID actor, UUID body, BlockPos source, String role, long tick) {
        expire(tick);
        if (origins.size() >= MAX_ORIGINS) retire(origins.values().iterator().next());
        var origin = new Origin(++nextId, actor, body, source, role, tick);
        origins.put(origin.id, origin);
        return origin;
    }

    public void spawned(Origin origin, Sample sample, Relation relation) {
        if (sample.availability != Availability.LIVE || sample.stack.isEmpty()) return;
        if (entities.containsKey(sample.id)) return; // Repeated admission is not another spawn.
        int count = sample.stack.getCount();
        origin.spawned += count;
        var spawn = describe(sample.stack, count);
        spawn.addProperty("source_relation", relation.name().toLowerCase(java.util.Locale.ROOT));
        spawn.addProperty("target_uuid", sample.id.toString());
        if (origin.spawns.size() < 16) origin.spawns.add(spawn);
        else origin.spawnsTruncated = true;
        if (origin.expired || entities.size() >= MAX_ENTITIES || partCount() >= MAX_PARTS) {
            origin.limited = true;
            settle(origin, count, Outcome.UNKNOWN, null, "tracking_limit", sample.observation);
            return;
        }
        var record = new StackRecord(sample);
        record.parts.put(new Part(origin.id, relation), count);
        entities.put(sample.id, record);
    }

    /** Reconcile only known identities; this never loads chunks or discovers a drop's source. */
    public void observe(UUID id, Sample sample) {
        var record = entities.get(id);
        if (record == null) return;
        if (sample == null || sample.availability != Availability.LIVE) {
            unknown(record, sample != null && sample.availability == Availability.UNLOADED
                    ? "unloaded" : "missing");
            entities.remove(id);
            return;
        }
        record.at = sample.observation.deepCopy();
        if (!ItemStack.isSameItemSameComponents(record.unit, sample.stack)) {
            unknown(record, "stack_changed");
            entities.remove(id);
            return;
        }
        if (sample.stack.getCount() < record.seen)
            remove(record, record.seen - sample.stack.getCount(), Outcome.UNKNOWN, null, "unobserved_loss");
        record.seen = sample.stack.getCount();
        if (record.parts.isEmpty()) entities.remove(id);
    }

    /**
     * Samples straddle exactly one native merge, before removal/count reconciliation.
     * Both sides must conserve quantity and components; a modded mismatch remains unknown.
     */
    public void merged(Sample fromBefore, Sample intoBefore, Sample fromAfter, Sample intoAfter) {
        UUID from = fromBefore.id, into = intoBefore.id;
        if (from.equals(into)) return;
        observe(from, fromBefore);
        observe(into, intoBefore);
        var source = entities.get(from);
        var target = entities.get(into);
        int moved = fromBefore.stack.getCount() - fromAfter.stack.getCount();
        boolean compatible = ItemStack.isSameItemSameComponents(fromBefore.stack, intoBefore.stack)
                && ItemStack.isSameItemSameComponents(intoBefore.stack, intoAfter.stack)
                && (fromAfter.stack.isEmpty()
                    || ItemStack.isSameItemSameComponents(fromBefore.stack, fromAfter.stack));
        if (!compatible || moved < 0 || intoAfter.stack.getCount() - intoBefore.stack.getCount() != moved) {
            if (source != null) unknown(source, "merge_mismatch");
            if (target != null) unknown(target, "merge_mismatch");
        } else if (source != null && moved > 0) {
            boolean whole = moved == source.seen;
            boolean homogeneous = source.parts.size() == 1 && source.attributed() == source.seen;
            if (!whole && !homogeneous) {
                unknown(source, "mixed_partial_merge");
            } else {
                if (target == null && entities.size() < MAX_ENTITIES) {
                    target = new StackRecord(intoBefore);
                    entities.put(into, target);
                }
                for (var entry : java.util.List.copyOf(source.parts.entrySet())) {
                    int quantity = whole ? entry.getValue() : moved;
                    if (target == null || !target.parts.containsKey(entry.getKey()) && partCount() >= MAX_PARTS) {
                        var origin = origins.get(entry.getKey().origin);
                        origin.limited = true;
                        settle(origin, quantity, Outcome.UNKNOWN, null, "tracking_limit", source.at);
                    } else target.parts.merge(entry.getKey(), quantity, Integer::sum);
                    int left = entry.getValue() - quantity;
                    if (left == 0) source.parts.remove(entry.getKey());
                    else source.parts.put(entry.getKey(), left);
                }
            }
        }
        if (source != null) source.seen = Math.max(0, fromAfter.stack.getCount());
        if (target != null) { target.seen = intoAfter.stack.getCount(); target.at = intoAfter.observation.deepCopy(); }
        prune();
    }

    /** Use the actual stack reduction, never LivingEntity.take's animation amount. */
    public void reduced(Sample before, Sample after, Outcome outcome, Picker taker, String reason) {
        observe(before.id, before);
        var record = entities.get(before.id);
        if (record == null) return;
        if (!after.stack.isEmpty() && !ItemStack.isSameItemSameComponents(before.stack, after.stack)) {
            unknown(record, "stack_changed");
        } else {
            int removed = before.stack.getCount() - after.stack.getCount();
            if (removed > 0) remove(record, removed, outcome, taker, reason);
            record.seen = Math.max(0, after.stack.getCount());
            record.at = after.observation.deepCopy();
        }
        prune();
    }

    public void unavailable(UUID id, String reason) {
        var record = entities.remove(id);
        if (record != null) unknown(record, reason);
    }

    public boolean gap() {
        boolean first = !gapReported;
        gapReported = true;
        for (var origin : origins.values()) origin.limited = true;
        for (var record : entities.values()) unknown(record, "observation_gap");
        entities.clear();
        return first;
    }

    private void remove(StackRecord record, int amount, Outcome outcome, Picker taker, String reason) {
        boolean whole = amount >= record.seen;
        boolean homogeneous = record.parts.size() == 1 && record.attributed() == record.seen;
        if (!whole && !homogeneous) {
            unknown(record, "mixed_partial_" + outcome.name().toLowerCase(java.util.Locale.ROOT));
            return;
        }
        for (var entry : java.util.List.copyOf(record.parts.entrySet())) {
            int quantity = whole ? entry.getValue() : Math.min(amount, entry.getValue());
            settle(origins.get(entry.getKey().origin), quantity, outcome, taker, reason, record.at);
            int left = entry.getValue() - quantity;
            if (left == 0) record.parts.remove(entry.getKey());
            else record.parts.put(entry.getKey(), left);
        }
    }

    private void unknown(StackRecord record, String reason) {
        for (var entry : record.parts.entrySet())
            settle(origins.get(entry.getKey().origin), entry.getValue(), Outcome.UNKNOWN, null, reason, record.at);
        record.parts.clear();
    }

    private void settle(Origin origin, int count, Outcome outcome, Picker taker, String reason, JsonObject at) {
        if (count <= 0) return;
        switch (outcome) {
            case PICKUP -> {
                if (taker != null && origin.actor.equals(taker.actor) && origin.body.equals(taker.body))
                    origin.self += count;
                else if (taker != null) origin.other += count;
                else origin.unknown += count;
            }
            case DESTROYED -> origin.destroyed += count;
            case UNKNOWN -> origin.unknown += count;
        }
        if (origin.events.size() < 8) {
            var event = new JsonObject();
            event.addProperty("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT));
            event.addProperty("count", count);
            event.addProperty("reason", WireSize.truncateToBytes(reason, 128));
            if (taker != null) {
                event.addProperty("taker_uuid", taker.actor.toString());
                if (taker.body != null) event.addProperty("taker_body_id", taker.body.toString());
            }
            for (String axis : java.util.List.of("x", "y", "z"))
                if (at.has(axis)) event.add(axis, at.get(axis).deepCopy());
            origin.events.add(event);
        } else origin.eventsTruncated = true;
    }

    public void refresh(long tick, Function<UUID, Sample> lookup) {
        expire(tick);
        for (UUID id : java.util.List.copyOf(entities.keySet())) observe(id, lookup.apply(id));
    }

    public void expire(long tick) {
        for (var origin : java.util.List.copyOf(origins.values()))
            if (tick < origin.tick || tick - origin.tick > RETAIN_TICKS) retire(origin);
    }

    private void retire(Origin origin) {
        for (var record : entities.values()) {
            for (var entry : java.util.List.copyOf(record.parts.entrySet())) {
                if (entry.getKey().origin == origin.id) {
                    settle(origin, entry.getValue(), Outcome.UNKNOWN, null, "history_expired", record.at);
                    record.parts.remove(entry.getKey());
                }
            }
        }
        origin.expired = true;
        origins.remove(origin.id);
        prune();
    }

    public JsonObject report(Origin origin) {
        long live = 0;
        var drops = new JsonArray();
        for (var entry : entities.entrySet()) {
            for (var part : entry.getValue().parts.entrySet()) {
                if (part.getKey().origin != origin.id) continue;
                live += part.getValue();
                if (drops.size() < 16) {
                    var drop = entry.getValue().at.deepCopy();
                    drop.addProperty("target_uuid", entry.getKey().toString());
                    drop.addProperty("recorded_count", part.getValue());
                    drop.addProperty("source_relation", part.getKey().relation.name().toLowerCase(java.util.Locale.ROOT));
                    drops.add(drop);
                }
            }
        }
        var data = new JsonObject();
        data.addProperty("ledger_id", identity.toString());
        data.addProperty("origin_id", origin.id);
        data.addProperty("created_tick", origin.tick);
        data.addProperty("actor_uuid", origin.actor.toString());
        data.addProperty("body_id", origin.body.toString());
        data.addProperty("role", origin.role);
        data.addProperty("attribution_scope", "synchronous_admitted_items");
        data.addProperty("delayed_spawns_attributed", false);
        var source = new JsonObject();
        source.addProperty("x", origin.source.getX()); source.addProperty("y", origin.source.getY());
        source.addProperty("z", origin.source.getZ()); data.add("source", source);
        data.addProperty("spawned_count", origin.spawned);
        data.addProperty("live_count", live);
        data.addProperty("picked_up_self_count", origin.self);
        data.addProperty("picked_up_other_count", origin.other);
        data.addProperty("destroyed_count", origin.destroyed);
        data.addProperty("unresolved_count", origin.unknown);
        data.addProperty("accounting_complete", !origin.limited && !origin.expired && origin.unknown == 0
                && origin.spawned == live + origin.self + origin.other + origin.destroyed);
        data.addProperty("tracking_limited", origin.limited);
        data.addProperty("history_expired", origin.expired);
        data.addProperty("spawns_truncated", origin.spawnsTruncated);
        data.addProperty("events_truncated", origin.eventsTruncated);
        data.addProperty("drops_truncated", drops.size() == 16 && live > drops.asList().stream()
                .mapToLong(value -> value.getAsJsonObject().get("recorded_count").getAsLong()).sum());
        data.add("spawns", origin.spawns.deepCopy());
        data.add("events", origin.events.deepCopy());
        data.add("live_drops", drops);
        boolean omitted = WireSize.utf8Bytes(data.toString()) > 8192;
        if (omitted) { data.remove("spawns"); data.remove("events"); data.remove("live_drops"); }
        data.addProperty("details_omitted", omitted);
        return data;
    }

    public JsonObject report(UUID ledger, long id) {
        var origin = origins.get(id);
        return identity.equals(ledger) && origin != null ? report(origin) : null;
    }

    public JsonObject recent(UUID actor, UUID body) {
        var values = origins.values().stream().filter(o -> o.actor.equals(actor) && o.body.equals(body)).toList();
        var records = new JsonArray();
        for (int i = Math.max(0, values.size() - 4); i < values.size(); i++) {
            var report = report(values.get(i));
            report.remove("spawns"); report.remove("events"); report.remove("live_drops");
            report.addProperty("details_omitted", true);
            records.add(report);
        }
        var data = new JsonObject();
        data.addProperty("ledger_id", identity.toString());
        data.addProperty("retention_ticks", RETAIN_TICKS);
        data.addProperty("history_bounded", true);
        data.addProperty("origins_truncated", values.size() > 4);
        data.add("origins", records);
        return data;
    }

    public JsonObject provenance(UUID entity, UUID actor, UUID body) {
        var record = entities.get(entity);
        var parts = new JsonArray();
        int own = 0;
        if (record != null) for (var entry : record.parts.entrySet()) {
            var origin = origins.get(entry.getKey().origin);
            if (!origin.actor.equals(actor) || !origin.body.equals(body)) continue;
            own += entry.getValue();
            if (parts.size() < 8) {
                var part = new JsonObject();
                part.addProperty("origin_id", origin.id); part.addProperty("role", origin.role);
                part.addProperty("count", entry.getValue());
                part.addProperty("source_relation", entry.getKey().relation.name().toLowerCase(java.util.Locale.ROOT));
                parts.add(part);
            }
        }
        var data = new JsonObject();
        data.addProperty("ledger_id", identity.toString());
        data.addProperty("recorded_own_count", own);
        data.addProperty("parts_truncated", parts.asList().stream()
                .mapToInt(e -> e.getAsJsonObject().get("count").getAsInt()).sum() < own);
        data.add("parts", parts);
        return data;
    }

    private static JsonObject describe(ItemStack stack, int count) {
        var data = new JsonObject();
        String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        data.addProperty("item", WireSize.truncateToBytes(item, 128));
        data.addProperty("item_id_truncated", WireSize.utf8Bytes(item) > 128);
        data.addProperty("count", count);
        return data;
    }
    private int partCount() { return entities.values().stream().mapToInt(e -> e.parts.size()).sum(); }
    public boolean tracks(UUID entity) { return entities.containsKey(entity); }
    private void prune() { entities.values().removeIf(record -> record.parts.isEmpty()); }
}
