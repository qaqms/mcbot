package com.neko.mcbot.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Native event adapter. The ledger retains identifiers and samples, never levels or entities. */
public final class DropTracker {
    private static final Map<ServerLevel, DropLedger> WORLDS = new WeakHashMap<>();
    private static final ThreadLocal<Request> REQUEST = new ThreadLocal<>();
    private static final ThreadLocal<Capture> CAPTURE = new ThreadLocal<>();
    private static final ThreadLocal<BlockPos> SOURCE = new ThreadLocal<>();
    private static final ThreadLocal<Map<ItemEntity, Integer>> MUTATING =
            ThreadLocal.withInitial(IdentityHashMap::new);
    private record Capture(ServerLevel level, DropLedger ledger, DropLedger.Origin origin, BlockPos pos) { }
    public static final class Request {
        private final CompanionPlayer body;
        private final BlockPos pos;
        private final String role;
        private Capture captured;
        private Request(CompanionPlayer body, BlockPos pos, String role) {
            this.body = body; this.pos = pos.immutable(); this.role = role;
        }
        public JsonObject report() {
            if (captured == null) return null; // Missing native hook must not look like zero drops.
            var ledger = captured.ledger;
            guarded(ledger, () -> ledger.refresh(captured.level.getGameTime(), id -> sample(captured.level, id)));
            return ledger.report(captured.origin);
        }
    }
    private DropTracker() { }

    public static Request request(CompanionPlayer body, BlockPos pos, String role) {
        return new Request(body, pos, role);
    }
    public static boolean destroy(Request request, BooleanSupplier action) {
        Request previous = REQUEST.get();
        REQUEST.set(request);
        try { return action.getAsBoolean(); }
        finally { restore(REQUEST, previous); }
    }

    /** Capture only entities actually admitted during native death, without predicting loot. */
    public static void death(Request request, Runnable action) {
        Capture previous = CAPTURE.get();
        BlockPos oldSource = SOURCE.get();
        Request oldRequest = REQUEST.get();
        var ledger = ledger(request.body.level());
        CAPTURE.remove(); SOURCE.remove(); REQUEST.remove();
        try {
            guarded(ledger, () -> {
                var origin = ledger.begin(request.body.getUUID(), request.body.observationBodyId(),
                        request.pos, "death", request.body.level().getGameTime());
                request.captured = new Capture(request.body.level(), ledger, origin, request.pos);
                CAPTURE.set(request.captured);
            });
            action.run();
        } finally {
            restore(CAPTURE, previous); restore(SOURCE, oldSource); restore(REQUEST, oldRequest);
        }
    }

    /** Mask nested foreign/native destruction instead of attributing its items to the caller. */
    public static boolean nativeBreak(ServerPlayer player, BlockPos pos, BooleanSupplier action) {
        if (REQUEST.get() == null && CAPTURE.get() == null) return action.getAsBoolean();
        Capture previous = CAPTURE.get();
        BlockPos oldSource = SOURCE.get();
        CAPTURE.remove();
        SOURCE.remove();
        try {
            var request = REQUEST.get();
            if (request != null && request.body == player && request.pos.equals(pos) && request.captured == null) {
                var ledger = ledger(player.level());
                guarded(ledger, () -> {
                    var origin = ledger.begin(player.getUUID(), request.body.observationBodyId(),
                            pos, request.role, player.level().getGameTime());
                    request.captured = new Capture(player.level(), ledger, origin, pos.immutable());
                    CAPTURE.set(request.captured);
                });
            }
            return action.getAsBoolean();
        } finally {
            restore(CAPTURE, previous);
            restore(SOURCE, oldSource);
        }
    }

    public static void blockSource(Level level, BlockPos pos, Runnable action) {
        if (CAPTURE.get() == null) { action.run(); return; }
        BlockPos previous = SOURCE.get();
        var capture = CAPTURE.get();
        if (capture != null && capture.level == level) SOURCE.set(pos.immutable());
        else SOURCE.remove();
        try { action.run(); }
        finally { restore(SOURCE, previous); }
    }

    public static void admitted(Entity entity) {
        var capture = CAPTURE.get();
        if (capture == null || !(entity instanceof ItemEntity item) || entity.level() != capture.level) return;
        guarded(capture.ledger, () -> {
            BlockPos source = SOURCE.get();
            var relation = source == null ? DropLedger.Relation.OPERATION_ONLY
                    : source.equals(capture.pos) ? DropLedger.Relation.SOURCE_CELL : DropLedger.Relation.OTHER_CELL;
            capture.ledger.spawned(capture.origin, sample(item), relation);
        });
    }

    public static DropLedger.Sample before(ItemEntity item) {
        if (!(item.level() instanceof ServerLevel level) || !WORLDS.containsKey(level)) return null;
        var result = new DropLedger.Sample[1];
        guarded(ledger(level), () -> result[0] = sample(item));
        return result[0];
    }
    public static boolean tracked(ItemEntity item) {
        if (!(item.level() instanceof ServerLevel level)) return false;
        var ledger = WORLDS.get(level);
        return ledger != null && ledger.tracks(item.getUUID());
    }
    public static void mutation(ItemEntity item, boolean entering) {
        var map = MUTATING.get();
        if (entering) map.merge(item, 1, Integer::sum);
        else {
            int depth = map.getOrDefault(item, 1) - 1;
            if (depth <= 0) map.remove(item); else map.put(item, depth);
            if (map.isEmpty()) MUTATING.remove();
        }
    }
    public static void merged(ItemEntity from, ItemEntity into, DropLedger.Sample fromBefore,
                              DropLedger.Sample intoBefore, boolean completed) {
        if (fromBefore == null || intoBefore == null || !(from.level() instanceof ServerLevel level)) return;
        var ledger = ledger(level);
        guarded(ledger, () -> {
            if (completed) ledger.merged(fromBefore, intoBefore, sample(from), sample(into));
            else {
                ledger.unavailable(from.getUUID(), "merge_exception");
                ledger.unavailable(into.getUUID(), "merge_exception");
            }
        });
    }
    public static void reduced(ItemEntity item, DropLedger.Sample before, DropLedger.Outcome outcome,
                               DropLedger.Picker taker, String reason, boolean completed) {
        if (before == null || !(item.level() instanceof ServerLevel level)) return;
        var ledger = ledger(level);
        guarded(ledger, () -> {
            if (completed) ledger.reduced(before, sample(item), outcome, taker, reason);
            else ledger.unavailable(item.getUUID(), "native_exception");
        });
    }
    public static DropLedger.Picker picker(Entity entity) {
        return new DropLedger.Picker(entity.getUUID(),
                entity instanceof CompanionPlayer body ? body.observationBodyId() : null);
    }
    public static void removed(Entity entity, Entity.RemovalReason reason) {
        if (!(entity instanceof ItemEntity item) || !(entity.level() instanceof ServerLevel level)
                || !WORLDS.containsKey(level) || MUTATING.get().containsKey(item)) return;
        guarded(ledger(level), () -> ledger(level).unavailable(item.getUUID(),
                reason == Entity.RemovalReason.UNLOADED_TO_CHUNK ? "unloaded" : "removed_unverified"));
    }

    public static void tick(ServerLevel level) {
        var ledger = WORLDS.get(level);
        if (ledger != null) guarded(ledger, () -> ledger.refresh(level.getGameTime(), id -> sample(level, id)));
    }
    public static JsonObject recent(CompanionPlayer body) {
        tick(body.level());
        return ledger(body.level()).recent(body.getUUID(), body.observationBodyId());
    }
    public static JsonObject provenance(CompanionPlayer body, ItemEntity item) {
        var ledger = ledger(body.level());
        guarded(ledger, () -> ledger.observe(item.getUUID(), sample(item)));
        return ledger.provenance(item.getUUID(), body.getUUID(), body.observationBodyId());
    }
    public static JsonObject report(ServerLevel level, UUID identity, long origin) {
        var ledger = WORLDS.get(level);
        return ledger == null ? null : ledger.report(identity, origin);
    }
    public static void clear() {
        WORLDS.clear();
        REQUEST.remove(); CAPTURE.remove(); SOURCE.remove(); MUTATING.remove();
    }
    private static DropLedger ledger(ServerLevel level) {
        return WORLDS.computeIfAbsent(level, ignored -> new DropLedger());
    }
    private static DropLedger.Sample sample(ServerLevel level, UUID id) {
        var entity = level.getEntity(id);
        return entity instanceof ItemEntity item ? sample(item) : null;
    }
    private static DropLedger.Sample sample(ItemEntity item) {
        // Native pickup restores the removed stack's old count for statistics. Removed means zero.
        ItemStack stack = item.isRemoved() ? ItemStack.EMPTY : item.getItem();
        var at = new JsonObject();
        at.addProperty("entity_id", item.getId()); at.addProperty("target_uuid", item.getUUID().toString());
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString();
        at.addProperty("item", com.neko.mcbot.common.WireSize.truncateToBytes(id, 128));
        at.addProperty("item_id_truncated", com.neko.mcbot.common.WireSize.utf8Bytes(id) > 128);
        at.addProperty("count", stack.getCount());
        at.addProperty("x", item.getX()); at.addProperty("y", item.getY()); at.addProperty("z", item.getZ());
        at.addProperty("on_ground", item.onGround());
        var availability = item.isRemoved() ? DropLedger.Availability.MISSING
                : !item.level().isLoaded(item.blockPosition()) ? DropLedger.Availability.UNLOADED
                : DropLedger.Availability.LIVE;
        return new DropLedger.Sample(item.getUUID(), stack, at, availability);
    }
    private static <T> void restore(ThreadLocal<T> local, T previous) {
        if (previous == null) local.remove(); else local.set(previous);
    }
    private static void guarded(DropLedger ledger, Runnable action) {
        try { action.run(); }
        catch (RuntimeException failure) {
            // Diagnostics contain no stack/component text and never retry the world operation.
            if (ledger.gap()) com.neko.mcbot.McbotMod.LOG.warn("掉落观察出现缺口，来源账已标记未知。");
        }
    }

    /** One serialization for direct mining and its delayed/cancelled receipt. */
    public static void addReport(JsonObject data, JsonObject report) {
        data.addProperty("drop_accounting_known", report != null);
        var primary = new JsonArray();
        var related = new JsonArray();
        var spawns = new JsonArray();
        long remaining = 0;
        if (report != null) {
            data.add("drop_accounting", report.deepCopy());
            if (report.has("live_drops")) for (var value : report.getAsJsonArray("live_drops")) {
                var drop = value.getAsJsonObject().deepCopy();
                int count = drop.get("recorded_count").getAsInt();
                drop.addProperty("count", count);
                remaining += count;
                if ("source_cell".equals(drop.get("source_relation").getAsString())) primary.add(drop);
                else related.add(drop);
            }
            if (report.has("spawns")) for (var value : report.getAsJsonArray("spawns"))
                if ("source_cell".equals(value.getAsJsonObject().get("source_relation").getAsString()))
                    spawns.add(value.deepCopy());
            remaining = report.get("live_count").getAsLong();
        }
        data.addProperty("remaining_count", remaining);
        data.add("drops", primary);
        data.add("related_drops", related);
        data.add("drop_spawns", spawns);
        data.addProperty("drop_spawns_complete", report != null
                && !report.get("spawns_truncated").getAsBoolean()
                && !report.get("details_omitted").getAsBoolean()
                && spawns.asList().stream().noneMatch(value ->
                    value.getAsJsonObject().get("item_id_truncated").getAsBoolean()));
        data.addProperty("collected_count", 0);
        data.add("collected", new JsonArray());
        var leftover = new JsonArray();
        for (var drop : primary) leftover.add(drop.getAsJsonObject().get("item").getAsString()
                + " x" + drop.getAsJsonObject().get("count").getAsInt());
        data.add("leftover", leftover);
    }
}
