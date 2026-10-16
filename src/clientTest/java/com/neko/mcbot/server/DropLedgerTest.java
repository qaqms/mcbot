package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.neko.mcbot.common.WireSize;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.neko.mcbot.server.DropLedger.*;
import static org.junit.jupiter.api.Assertions.*;

class DropLedgerTest {
    @BeforeAll static void bootstrap() { BlockActionFixtures.bootstrap(); }
    final DropLedger ledger = new DropLedger();
    final UUID actor = UUID.randomUUID(), body = UUID.randomUUID();
    Origin begin(String role) { return ledger.begin(actor, body, new BlockPos(2, 60, 3), role, 10); }
    static Sample item(UUID id, int count) {
        return sample(id, count == 0 ? ItemStack.EMPTY : new ItemStack(Items.COBBLESTONE, count));
    }
    static Sample sample(UUID id, ItemStack stack) {
        var at = new JsonObject();
        at.addProperty("item", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        at.addProperty("count", stack.getCount());
        at.addProperty("x", 2.5); at.addProperty("y", 60); at.addProperty("z", 3.5);
        return new Sample(id, stack, at, Availability.LIVE);
    }
    void spawn(Origin origin, UUID id, int count) { ledger.spawned(origin, item(id, count), Relation.SOURCE_CELL); }
    long count(Origin origin, String field) { return ledger.report(origin).get(field).getAsLong(); }
    void conserved(Origin origin) {
        assertEquals(count(origin, "spawned_count"), count(origin, "live_count")
                + count(origin, "picked_up_self_count") + count(origin, "picked_up_other_count")
                + count(origin, "destroyed_count") + count(origin, "unresolved_count"));
    }

    @Test void sourcesRolesAndHistoricalEntitiesRemainSeparateWithNoLootPrediction() {
        var target = begin("target");
        var clear = begin("clearing");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), history = UUID.randomUUID();
        spawn(target, a, 3); spawn(clear, b, 2);
        ledger.observe(history, item(history, 40));
        assertEquals(3, count(target, "spawned_count"));
        assertEquals(2, count(clear, "spawned_count"));
        assertEquals(0, ledger.provenance(history, actor, body).get("recorded_own_count").getAsInt());
        var empty = begin("path_clear");
        assertEquals(0, count(empty, "spawned_count"));
        assertFalse(ledger.report(empty).get("delayed_spawns_attributed").getAsBoolean());
        conserved(target); conserved(clear); conserved(empty);
    }

    @Test void fullMergeIntoHistoricalStackKeepsOnlyKnownQuantityAndFollowsItsNewIdentity() {
        var origin = begin("target");
        UUID source = UUID.randomUUID(), history = UUID.randomUUID();
        spawn(origin, source, 3);
        ledger.merged(item(source, 3), item(history, 20), item(source, 0), item(history, 23));
        assertEquals(3, count(origin, "live_count"));
        var live = ledger.report(origin).getAsJsonArray("live_drops");
        assertEquals(1, live.size());
        assertEquals(history.toString(), live.get(0).getAsJsonObject().get("target_uuid").getAsString());
        assertEquals(3, ledger.provenance(history, actor, body).get("recorded_own_count").getAsInt());
        ledger.reduced(item(history, 23), item(history, 0), Outcome.PICKUP, new Picker(actor, body), "touch");
        assertEquals(3, count(origin, "picked_up_self_count"));
        assertEquals(0, count(origin, "picked_up_other_count"));
        conserved(origin);
    }

    @Test void homogeneousPartialMergeCanSplitAndRecombineWithoutLosingOrCreatingItems() {
        var origin = begin("path_clear");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        spawn(origin, a, 8);
        ledger.merged(item(a, 8), item(b, 60), item(a, 4), item(b, 64));
        assertEquals(8, count(origin, "live_count"));
        assertEquals(2, ledger.report(origin).getAsJsonArray("live_drops").size());
        ledger.reduced(item(a, 4), item(a, 2), Outcome.PICKUP, new Picker(actor, body), "partial_space");
        assertEquals(2, count(origin, "picked_up_self_count"));
        UUID c = UUID.randomUUID();
        ledger.merged(item(a, 2), item(c, 10), item(a, 0), item(c, 12));
        assertEquals(6, count(origin, "live_count"));
        conserved(origin);
    }

    @Test void twoKnownSourcesMergeButPartialPickupCannotChooseWhichSourceWasTaken() {
        var target = begin("target"); var clearing = begin("clearing");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        spawn(target, a, 3); spawn(clearing, b, 5);
        ledger.merged(item(a, 3), item(b, 5), item(a, 0), item(b, 8));
        ledger.reduced(item(b, 8), item(b, 6), Outcome.PICKUP, new Picker(actor, body), "partial");
        assertEquals(3, count(target, "unresolved_count"));
        assertEquals(5, count(clearing, "unresolved_count"));
        assertEquals(0, count(target, "picked_up_self_count"));
        assertEquals(0, count(clearing, "live_count"));
        assertFalse(ledger.report(target).get("accounting_complete").getAsBoolean());
        conserved(target); conserved(clearing);
    }

    @Test void partialTransferFromMixedHistoricalStackInvalidatesDonorButKeepsDestinationEvidence() {
        var donor = begin("target"); var destination = begin("clearing");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        spawn(donor, a, 2); spawn(destination, c, 1);
        ledger.merged(item(a, 2), item(b, 8), item(a, 0), item(b, 10));
        ledger.merged(item(b, 10), item(c, 1), item(b, 7), item(c, 4));
        assertEquals(2, count(donor, "unresolved_count"));
        assertEquals(1, count(destination, "live_count"));
        ledger.reduced(item(c, 4), item(c, 0), Outcome.PICKUP, new Picker(UUID.randomUUID(), null), "other");
        assertEquals(1, count(destination, "picked_up_other_count"));
        conserved(donor); conserved(destination);
    }

    @Test void fullMixedPickupSettlesEverySourceAndComponentTextNeverLeavesTheLedger() {
        var one = begin("target"); var two = begin("clearing");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var named = new ItemStack(Items.COBBLESTONE, 2);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("private_component_" + "x".repeat(40_000)));
        ledger.spawned(one, sample(a, named), Relation.SOURCE_CELL);
        ledger.spawned(two, sample(b, named.copyWithCount(3)), Relation.OPERATION_ONLY);
        ledger.merged(sample(a, named), sample(b, named.copyWithCount(3)), item(a, 0),
                sample(b, named.copyWithCount(5)));
        ledger.reduced(sample(b, named.copyWithCount(5)), item(b, 0), Outcome.PICKUP,
                new Picker(actor, body), "native_player_touch");
        assertEquals(2, count(one, "picked_up_self_count"));
        assertEquals(3, count(two, "picked_up_self_count"));
        assertFalse(ledger.report(one).toString().contains("private_component"));
        conserved(one); conserved(two);
    }

    @Test void pickupDelayRejectionAndFullInventoryProduceNoPickupAndRealPartialSpaceUsesActualReduction() {
        var origin = begin("target");
        UUID id = UUID.randomUUID();
        spawn(origin, id, 6);
        for (int i = 0; i < 4; i++)
            ledger.reduced(item(id, 6), item(id, 6), Outcome.PICKUP, new Picker(actor, body), "delay_or_full");
        assertEquals(0, count(origin, "picked_up_self_count"));
        ledger.reduced(item(id, 6), item(id, 4), Outcome.PICKUP, new Picker(actor, body), "actual_reduction");
        assertEquals(2, count(origin, "picked_up_self_count"));
        assertEquals(4, count(origin, "live_count"));
        conserved(origin);
    }

    @Test void otherPlayerAndSameUuidReplacementBodyCannotBeCreditedAsTheOriginalBody() {
        var origin = begin("target");
        UUID id = UUID.randomUUID();
        spawn(origin, id, 5);
        ledger.reduced(item(id, 5), item(id, 3), Outcome.PICKUP,
                new Picker(UUID.randomUUID(), null), "other_player");
        ledger.reduced(item(id, 3), item(id, 0), Outcome.PICKUP,
                new Picker(actor, UUID.randomUUID()), "replacement_body");
        assertEquals(0, count(origin, "picked_up_self_count"));
        assertEquals(5, count(origin, "picked_up_other_count"));
        assertTrue(ledger.recent(actor, UUID.randomUUID()).getAsJsonArray("origins").isEmpty());
        conserved(origin);
    }

    @Test void actualFatalDamageHasACauseButNonfatalDamageDoesNotSettleAnything() {
        var origin = begin("target"); UUID id = UUID.randomUUID();
        spawn(origin, id, 4);
        ledger.reduced(item(id, 4), item(id, 4), Outcome.DESTROYED, null, "minecraft:in_fire");
        assertEquals(0, count(origin, "destroyed_count"));
        ledger.reduced(item(id, 4), item(id, 0), Outcome.DESTROYED, null, "minecraft:lava");
        assertEquals(4, count(origin, "destroyed_count"));
        assertEquals("minecraft:lava", ledger.report(origin).getAsJsonArray("events")
                .get(0).getAsJsonObject().get("reason").getAsString());
        conserved(origin);
    }

    @Test void unloadingMissingUnknownRemovalAndSilentMutationNeverBecomePickupOrDestruction() {
        for (String scenario : java.util.List.of("unload", "missing", "discard", "silent_shrink", "component")) {
            var origin = begin("target"); UUID id = UUID.randomUUID();
            spawn(origin, id, 3);
            switch (scenario) {
                case "unload" -> ledger.observe(id, new Sample(id, new ItemStack(Items.COBBLESTONE, 3),
                        new JsonObject(), Availability.UNLOADED));
                case "missing" -> ledger.observe(id, null);
                case "discard" -> ledger.unavailable(id, "removed_unverified");
                case "silent_shrink" -> ledger.observe(id, item(id, 1));
                case "component" -> ledger.observe(id, sample(id, new ItemStack(Items.DIRT, 3)));
            }
            assertTrue(count(origin, "unresolved_count") > 0);
            assertEquals(0, count(origin, "picked_up_self_count"));
            assertEquals(0, count(origin, "picked_up_other_count"));
            assertEquals(0, count(origin, "destroyed_count"));
            conserved(origin);
        }
    }

    @Test void unobservedGrowthIsHistoricalAndCannotBeClaimedByAnExistingOrigin() {
        var origin = begin("target"); UUID id = UUID.randomUUID();
        spawn(origin, id, 3);
        ledger.observe(id, item(id, 9));
        assertEquals(3, count(origin, "spawned_count"));
        ledger.reduced(item(id, 9), item(id, 8), Outcome.PICKUP, new Picker(actor, body), "partial");
        assertEquals(3, count(origin, "unresolved_count"));
        assertEquals(0, count(origin, "picked_up_self_count"));
        conserved(origin);
    }

    @Test void mergeCountOrComponentMismatchMarksBothSidesUnknownWithoutAwardingAnyItems() {
        for (boolean component : new boolean[]{false, true}) {
            var one = begin("target"); var two = begin("clearing");
            UUID a = UUID.randomUUID(), b = UUID.randomUUID();
            spawn(one, a, 2); spawn(two, b, 3);
            var after = component ? sample(b, new ItemStack(Items.DIRT, 5)) : item(b, 4);
            ledger.merged(item(a, 2), item(b, 3), item(a, 0), after);
            assertEquals(2, count(one, "unresolved_count"));
            assertEquals(3, count(two, "unresolved_count"));
            conserved(one); conserved(two);
        }
    }

    @Test void boundedTrackingDoesNotEvictQuantitiesIntoFakeSuccessAndReportsOmittedDetail() {
        var origin = begin("target");
        for (int i = 0; i <= MAX_ENTITIES; i++) spawn(origin, UUID.randomUUID(), 1);
        assertEquals(MAX_ENTITIES, count(origin, "live_count"));
        assertEquals(1, count(origin, "unresolved_count"));
        var report = ledger.report(origin);
        assertTrue(report.get("tracking_limited").getAsBoolean());
        assertTrue(report.get("spawns_truncated").getAsBoolean());
        assertTrue(report.get("drops_truncated").getAsBoolean());
        assertTrue(WireSize.utf8Bytes(report.toString()) <= 8192);
        conserved(origin);
    }

    @Test void expirationAndOriginLimitMakeOldTokensUnavailableAndDoNotCrossWorldLedgers() {
        var origin = begin("target"); UUID id = UUID.randomUUID();
        spawn(origin, id, 2);
        var token = ledger.report(origin);
        var identity = UUID.fromString(token.get("ledger_id").getAsString());
        assertNull(new DropLedger().report(identity, origin.id()));
        ledger.expire(10 + RETAIN_TICKS);
        assertNotNull(ledger.report(identity, origin.id()));
        ledger.expire(11 + RETAIN_TICKS);
        assertNull(ledger.report(identity, origin.id()));
        assertEquals(2, count(origin, "unresolved_count"));
        assertTrue(ledger.report(origin).get("history_expired").getAsBoolean());
        for (int i = 0; i <= MAX_ORIGINS; i++) begin("target");
        assertTrue(ledger.recent(actor, body).get("origins_truncated").getAsBoolean());
        assertEquals(4, ledger.recent(actor, body).getAsJsonArray("origins").size());
        conserved(origin);
    }

    @Test void collateralAndUnverifiedOperationSpawnsNeverEnterPrimaryYieldSamples() {
        var origin = begin("target");
        spawn(origin, UUID.randomUUID(), 1);
        ledger.spawned(origin, item(UUID.randomUUID(), 2), Relation.OTHER_CELL);
        ledger.spawned(origin, item(UUID.randomUUID(), 3), Relation.OPERATION_ONLY);
        var data = new JsonObject();
        DropTracker.addReport(data, ledger.report(origin));
        assertEquals(1, data.getAsJsonArray("drops").size());
        assertEquals(1, data.getAsJsonArray("drop_spawns").size());
        assertEquals(2, data.getAsJsonArray("related_drops").size());
        assertEquals(6, data.get("remaining_count").getAsInt());
        conserved(origin);
    }

    @Test void gapAndRepeatedReadOnlyObservationsCannotSettleOrDuplicateActions() {
        var origin = begin("target"); UUID id = UUID.randomUUID();
        spawn(origin, id, 4);
        for (int i = 0; i < 20; i++) {
            ledger.observe(id, item(id, 4));
            ledger.report(origin);
        }
        assertEquals(4, count(origin, "spawned_count"));
        assertTrue(ledger.gap());
        assertFalse(ledger.gap());
        ledger.observe(id, item(id, 4));
        assertEquals(4, count(origin, "unresolved_count"));
        assertEquals(0, count(origin, "live_count"));
        conserved(origin);
    }
}
