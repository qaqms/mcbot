package com.neko.mcbot.body;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.mcbot.common.WireSize;
import com.neko.mcbot.server.ServerTool;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/** Bounded observations, never an attribution ledger or a task-completion predicate. */
public final class BodyChangeLog {
    public static final int MAX_BYTES = 4096;
    private static final int KEPT = 16;
    private static final int LIST_LIMIT = 8;

    record Snapshot(Object world, String dimension, long tick, Vec3 position,
                    float health, float absorption, int food, int air, int points, int levels,
                    UUID vehicle, int selected, List<ItemStack> inventory,
                    Map<EquipmentSlot, ItemStack> equipment) {
        Snapshot {
            if (inventory.size() > 64 || equipment.size() > EquipmentSlot.VALUES.size())
                throw new IllegalArgumentException("snapshot size");
            inventory = inventory.stream().map(ItemStack::copy).toList();
            var copy = new java.util.EnumMap<EquipmentSlot, ItemStack>(EquipmentSlot.class);
            equipment.forEach((slot, stack) -> copy.put(slot, stack.copy()));
            equipment = Map.copyOf(copy);
        }
    }

    private record Entry(long sequence, JsonObject data) { }

    public final class Window {
        private final Snapshot start;
        private final long from;
        private ServerTool.Result result;
        private Window(Snapshot start) { this.start = start; this.from = sequence; }

        /** Stable terminal projection; observing it again cannot sample or replay an action. */
        public ServerTool.Result finish(ServerTool.Result original) {
            if (result != null) return result;
            Snapshot end = sample();
            JsonObject delta = header(from);
            boolean same = start.world() == end.world();
            delta.addProperty("same_world", same);
            delta.addProperty("from_tick", start.tick());
            delta.addProperty("through_tick", end.tick());
            delta.addProperty("dimension_before", WireSize.truncateToBytes(start.dimension(), 128));
            delta.addProperty("dimension_after", WireSize.truncateToBytes(end.dimension(), 128));
            delta.add("net", same ? changes(start, end) : new JsonObject());
            delta.add("equipment_breaks", entries(breaks, from, LIST_LIMIT));
            delta.addProperty("breaks_truncated", brokenSince(from) > LIST_LIMIT
                    || lastDroppedBreak > from);
            delta.addProperty("history_truncated", lastDropped > from);
            delta.addProperty("observation_known", lastGap <= from);
            fit(delta);
            var data = original.data() == null ? new JsonObject() : original.data().deepCopy();
            data.add("body_delta", delta);
            result = new ServerTool.Result(original.ok(),
                    original.feedback() + sentence(delta), data);
            return result;
        }
    }

    private final UUID bodyId;
    private final Supplier<Snapshot> capture;
    private final ArrayDeque<Entry> history = new ArrayDeque<>(), breaks = new ArrayDeque<>();
    private Snapshot previous;
    private long sequence, lastDropped, lastDroppedBreak, lastGap;
    private boolean gap;

    BodyChangeLog(UUID bodyId, Supplier<Snapshot> capture) {
        this.bodyId = bodyId;
        this.capture = capture;
    }

    static BodyChangeLog forPlayer(UUID bodyId, CompanionPlayer player) {
        return new BodyChangeLog(bodyId, () -> {
            var inventory = new ArrayList<ItemStack>();
            for (int i = 0; i < player.getInventory().getContainerSize(); i++)
                inventory.add(player.getInventory().getItem(i));
            var equipment = new java.util.EnumMap<EquipmentSlot, ItemStack>(EquipmentSlot.class);
            for (EquipmentSlot slot : EquipmentSlot.VALUES) equipment.put(slot, player.getItemBySlot(slot));
            var vehicle = player.getVehicle();
            return new Snapshot(player.level(), player.level().dimension().identifier().toString(),
                    player.level().getGameTime(), player.position(), player.getHealth(),
                    player.getAbsorptionAmount(), player.getFoodData().getFoodLevel(), player.getAirSupply(),
                    player.totalExperience, player.experienceLevel,
                    vehicle == null ? null : vehicle.getUUID(), player.getInventory().getSelectedSlot(),
                    inventory, equipment);
        });
    }

    public Window open() { return new Window(sample()); }

    public void observe() { sample(); }

    private Snapshot sample() {
        try {
            return sampleKnown();
        } catch (RuntimeException unavailable) {
            gap = true;
            throw unavailable;
        }
    }

    private Snapshot sampleKnown() {
        Snapshot now = capture.get();
        if (gap) {
            var unknown = new JsonObject();
            unknown.addProperty("kind", "observation_gap");
            record(history, unknown);
            lastGap = sequence;
            gap = false;
        }
        if (previous != null) {
            JsonObject delta;
            if (previous.world() != now.world()) {
                delta = new JsonObject();
                delta.addProperty("world_changed", true);
            } else delta = changes(previous, now);
            if (!delta.isEmpty()) {
                delta.addProperty("from_tick", previous.tick());
                delta.addProperty("through_tick", now.tick());
                record(history, delta);
            }
        }
        previous = now;
        return now;
    }

    /** Called only by the native equipped-item break callback, never inferred from empty slots. */
    public void equipmentBroken(Item item, EquipmentSlot slot) {
        var event = new JsonObject();
        event.addProperty("kind", "equipped_item_broken");
        event.addProperty("item", id(item));
        event.addProperty("slot", slot.getSerializedName());
        record(breaks, event);
    }

    public JsonObject recent() {
        sample();
        var data = header(0);
        data.add("recent", entries(history, 0, 4));
        data.add("equipment_breaks", entries(breaks, 0, 4));
        data.addProperty("history_truncated", sequence > history.size() + breaks.size()
                || history.size() > 4 || breaks.size() > 4);
        data.addProperty("observation_gap_seen", lastGap > 0);
        data.addProperty("history_limit", KEPT);
        data.addProperty("recent_limit", 4);
        fit(data);
        return data;
    }

    public static String sentence(JsonObject delta) {
        return "\n身体区间观察（仅事实，不证明动作归因、入包来源或目标完成；同body_id/seq不重复计数）："
                + delta;
    }

    private JsonObject header(long from) {
        var data = new JsonObject();
        data.addProperty("body_id", bodyId.toString());
        data.addProperty("from_seq", from);
        data.addProperty("through_seq", sequence);
        data.addProperty("attribution", "observation_only");
        return data;
    }

    private void record(ArrayDeque<Entry> target, JsonObject data) {
        if (target.size() == KEPT) {
            long dropped = target.removeFirst().sequence();
            if (target == breaks) lastDroppedBreak = dropped;
            else lastDropped = dropped;
        }
        fit(data);
        target.addLast(new Entry(++sequence, data));
    }

    private long brokenSince(long from) {
        return breaks.stream().filter(entry -> entry.sequence() > from).count();
    }

    private static JsonArray entries(ArrayDeque<Entry> source, long from, int limit) {
        var selected = source.stream().filter(entry -> entry.sequence() > from).toList();
        var array = new JsonArray();
        for (int i = Math.max(0, selected.size() - limit); i < selected.size(); i++) {
            Entry entry = selected.get(i);
            JsonObject data = entry.data().deepCopy();
            data.addProperty("seq", entry.sequence());
            array.add(data);
        }
        return array;
    }

    private static JsonObject changes(Snapshot before, Snapshot after) {
        var data = new JsonObject();
        if (!before.position().equals(after.position())) {
            data.add("position_before", position(before.position()));
            data.add("position_after", position(after.position()));
        }
        scalar(data, "health", before.health(), after.health());
        scalar(data, "absorption", before.absorption(), after.absorption());
        scalar(data, "food", before.food(), after.food());
        scalar(data, "air", before.air(), after.air());
        scalar(data, "experience_points", before.points(), after.points());
        scalar(data, "experience_levels", before.levels(), after.levels());
        scalar(data, "selected_slot", before.selected(), after.selected());
        if (!java.util.Objects.equals(before.vehicle(), after.vehicle())) {
            data.addProperty("vehicle_before", before.vehicle() == null ? "" : before.vehicle().toString());
            data.addProperty("vehicle_after", after.vehicle() == null ? "" : after.vehicle().toString());
        }
        Map<String, Integer> totals = totals(after.inventory());
        totals(before.inventory()).forEach((item, count) -> totals.merge(item, -count, Integer::sum));
        var items = new JsonArray();
        int changed = 0;
        for (var entry : totals.entrySet()) {
            if (entry.getValue() == 0) continue;
            changed++;
            if (items.size() >= LIST_LIMIT) continue;
            var change = new JsonObject();
            change.addProperty("item", entry.getKey());
            change.addProperty("count_delta", entry.getValue());
            items.add(change);
        }
        if (changed > 0) {
            for (var item : items) {
                var entry = item.getAsJsonObject();
                String full = entry.get("item").getAsString();
                entry.addProperty("item", WireSize.truncateToBytes(full, 128));
                if (WireSize.utf8Bytes(full) > 128) entry.addProperty("item_id_truncated", true);
            }
            data.add("carried_items", items);
            data.addProperty("carried_items_truncated", changed > LIST_LIMIT);
        }
        var equipment = new JsonArray();
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            var old = before.equipment().getOrDefault(slot, ItemStack.EMPTY);
            var fresh = after.equipment().getOrDefault(slot, ItemStack.EMPTY);
            if (ItemStack.matches(old, fresh)) continue;
            var change = new JsonObject();
            change.addProperty("slot", slot.getSerializedName());
            change.add("before", stack(old));
            change.add("after", stack(fresh));
            equipment.add(change);
        }
        if (!equipment.isEmpty()) data.add("equipment", equipment);
        // Components are compared without exposing names, lore, NBT or arbitrary mod payloads.
        int changedSlots = 0;
        for (int i = 0; i < Math.min(before.inventory().size(), after.inventory().size()); i++) {
            ItemStack old = before.inventory().get(i), fresh = after.inventory().get(i);
            if (!old.isEmpty() && !fresh.isEmpty() && old.is(fresh.getItem())
                    && !ItemStack.isSameItemSameComponents(old, fresh)) changedSlots++;
        }
        if (changedSlots > 0) data.addProperty("component_changed_slots", changedSlots);
        return data;
    }

    private static JsonArray position(Vec3 point) {
        var data = new JsonArray();
        data.add(point.x); data.add(point.y); data.add(point.z);
        return data;
    }

    private static void scalar(JsonObject data, String key, double before, double after) {
        if (before == after) return;
        var change = new JsonArray();
        change.add(before); change.add(after);
        data.add(key, change);
    }

    private static Map<String, Integer> totals(List<ItemStack> inventory) {
        var totals = new TreeMap<String, Integer>();
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty()) totals.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    stack.getCount(), Math::addExact);
        }
        return totals;
    }

    private static String id(Item item) {
        return WireSize.truncateToBytes(BuiltInRegistries.ITEM.getKey(item).toString(), 128);
    }

    private static JsonObject stack(ItemStack stack) {
        var data = new JsonObject();
        data.addProperty("item", stack.isEmpty() ? "" : id(stack.getItem()));
        data.addProperty("count", stack.isEmpty() ? 0 : stack.getCount());
        if (!stack.isEmpty() && stack.isDamageableItem()) data.addProperty("damage", stack.getDamageValue());
        return data;
    }

    private static void fit(JsonObject data) {
        // Drop only complete detail collections and state the loss; scalar facts and cursors survive.
        for (String key : List.of("recent", "equipment", "carried_items", "equipment_breaks", "net")) {
            if (WireSize.utf8Bytes(data.toString()) <= MAX_BYTES) return;
            if (data.remove(key) != null) data.addProperty(key + "_omitted", true);
        }
    }
}
