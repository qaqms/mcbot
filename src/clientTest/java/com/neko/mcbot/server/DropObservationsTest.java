package com.neko.mcbot.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DropObservationsTest {
    private static JsonObject drop(UUID id, int count) {
        var data = new JsonObject();
        data.addProperty("target_uuid", id.toString());
        data.addProperty("count", count);
        return data;
    }
    private static JsonObject removal(int count, JsonObject... records) {
        var data = new JsonObject();
        data.addProperty("remaining_count", count);
        var drops = new JsonArray();
        for (var record : records) drops.add(record);
        data.add("drops", drops);
        return data;
    }

    @Test void currentRemainingChangesWhileHistoricalCountStaysFifteen() {
        var ledger = new DropObservations();
        UUID id = UUID.randomUUID();
        ledger.record(removal(15, drop(id, 15)));
        var current = new HashMap<UUID, JsonObject>();
        current.put(id, drop(id, 3));
        var snapshot = ledger.snapshot(current::get);
        assertEquals(15, snapshot.get("observed_drop_items").getAsInt());
        assertEquals(3, snapshot.get("remaining_items").getAsInt());
        assertEquals(12, snapshot.get("unresolved_drop_items").getAsInt());
        assertFalse(snapshot.get("remaining_items_complete").getAsBoolean());
        current.clear();
        assertEquals(0, ledger.snapshot(current::get).get("remaining_items").getAsInt());
        assertTrue(DropObservations.sentence(snapshot).contains("历史"));
        assertFalse(DropObservations.sentence(snapshot).contains("未入包"));
    }

    @Test void disappearanceAndMergingRemainUnknownRatherThanReportedAsPickup() {
        var ledger = new DropObservations();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        ledger.record(removal(2, drop(first, 2)));
        ledger.record(removal(3, drop(second, 3)));
        var snapshot = ledger.snapshot(id -> id.equals(first) ? drop(first, 5) : null);
        assertEquals(2, snapshot.get("remaining_items").getAsInt(), "growth from a merge is not attributed");
        assertEquals(3, snapshot.get("unresolved_drop_items").getAsInt());
        assertFalse(snapshot.has("collected_count"));
    }

    @Test void trackingAndReceiptCapsPreserveUnobservedRemainder() {
        var ledger = new DropObservations();
        for (int i = 0; i < DropObservations.MAX_TRACKED + 1; i++) {
            UUID id = UUID.randomUUID();
            ledger.record(removal(1, drop(id, 1)));
        }
        var snapshot = ledger.snapshot(id -> drop(id, 1));
        assertEquals(256, snapshot.get("remaining_items").getAsInt());
        assertEquals(1, snapshot.get("unresolved_drop_items").getAsInt());
        assertEquals(16, snapshot.getAsJsonArray("remaining_drops").size());
    }
}
