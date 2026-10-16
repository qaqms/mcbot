package com.neko.mcbot.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DropObservationsTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() { BlockActionFixtures.bootstrap(); }
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

    @Test void nativeOriginsAreDeduplicatedFollowMergesAndKeepOtherPickupOutOfInventoryProgress() {
        var ledger = new DropLedger();
        var actor = UUID.randomUUID(); var body = UUID.randomUUID();
        var origin = ledger.begin(actor, body, net.minecraft.core.BlockPos.ZERO, "path_clear", 0);
        UUID source = UUID.randomUUID(), destination = UUID.randomUUID();
        ledger.spawned(origin, DropLedgerTest.item(source, 4), DropLedger.Relation.SOURCE_CELL);
        var removal = new JsonObject();
        DropTracker.addReport(removal, ledger.report(origin));
        var observations = new DropObservations();
        observations.record(removal); observations.record(removal.deepCopy());
        ledger.merged(DropLedgerTest.item(source, 4), DropLedgerTest.item(destination, 7),
                DropLedgerTest.item(source, 0), DropLedgerTest.item(destination, 11));
        var snapshot = observations.snapshotSources(ignored -> ledger.report(origin));
        assertEquals(4, snapshot.get("observed_drop_items").getAsInt());
        assertEquals(4, snapshot.get("remaining_items").getAsInt());
        assertEquals(destination.toString(), snapshot.getAsJsonArray("remaining_drops").get(0)
                .getAsJsonObject().get("target_uuid").getAsString());
        ledger.reduced(DropLedgerTest.item(destination, 11), DropLedgerTest.item(destination, 0),
                DropLedger.Outcome.PICKUP, new DropLedger.Picker(UUID.randomUUID(), null), "other_player");
        snapshot = observations.snapshotSources(ignored -> ledger.report(origin));
        assertEquals(4, snapshot.get("picked_up_other_items").getAsInt());
        assertEquals(0, snapshot.get("picked_up_self_items").getAsInt());
        assertEquals(0, snapshot.get("remaining_items").getAsInt());
        assertTrue(snapshot.get("remaining_items_complete").getAsBoolean());
        assertFalse(snapshot.has("collected_count"));
    }

    @Test void expiredMissingOrUninstrumentedOriginsStayUnknownAndNeverLoadOrInventEntities() {
        var ledger = new DropLedger();
        var origin = ledger.begin(UUID.randomUUID(), UUID.randomUUID(), net.minecraft.core.BlockPos.ZERO, "target", 0);
        ledger.spawned(origin, DropLedgerTest.item(UUID.randomUUID(), 3), DropLedger.Relation.SOURCE_CELL);
        var removal = new JsonObject();
        DropTracker.addReport(removal, ledger.report(origin));
        var observations = new DropObservations();
        observations.record(removal);
        var missing = observations.snapshotSources(ignored -> null);
        assertEquals(3, missing.get("unresolved_drop_items").getAsInt());
        assertFalse(missing.get("remaining_items_complete").getAsBoolean());
        var uninstrumented = new JsonObject();
        DropTracker.addReport(uninstrumented, null);
        observations.record(uninstrumented);
        assertFalse(observations.snapshotSources(ignored -> ledger.report(origin))
                .get("remaining_items_complete").getAsBoolean());
        assertTrue(DropObservations.sentence(missing).contains("最终成果仍须核对背包"));
    }
}
