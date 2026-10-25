package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldGoalTest {
    @Test void registrationExposesMissingEfficientToolsWithoutChangingOrBlockingTheGoal() {
        var state = buildState(0);
        state.getAsJsonObject("goal_evidence").add("clearing_work", json("""
                {"preparation_recommended":true,"tools":[
                {"preferred_kind":"shovel","cells":49,"preparation_recommended":true}]}
                """));
        var goal = new WorldGoal(building(),state);
        assertTrue(goal.facts().contains("shovel对应49格"));
        assertTrue(goal.facts().contains("真实配方"));
        assertFalse(goal.satisfied());
        String original = goal.fingerprint();
        state.getAsJsonObject("goal_evidence").getAsJsonObject("clearing_work").getAsJsonArray("tools")
                .get(0).getAsJsonObject().addProperty("preparation_recommended",false);
        goal.observe(state);
        assertFalse(goal.facts().contains("shovel对应49格"));
        assertEquals(original, goal.fingerprint());
        assertEquals(building(),goal.request());
    }
    @Test void fullSizeBuildingEvidenceRetainsWholeGoalCountsAndNeverAcceptsTheLastMissingCell() {
        var state=buildState(20);
        var evidence=state.getAsJsonObject("goal_evidence");
        evidence.addProperty("total",16384);
        evidence.getAsJsonObject("counts").addProperty("satisfied",16384);
        var spec=building();
        spec.getAsJsonObject("target").add("fill",json("""
                {"from":{"x":-32,"y":0,"z":0},"to":{"x":31,"y":31,"z":7},"block":"stone"}
                """));
        var goal=new WorldGoal(spec,state);
        assertTrue(goal.satisfied());
        evidence.addProperty("remaining",1);
        evidence.getAsJsonObject("counts").addProperty("satisfied",16383);
        evidence.getAsJsonObject("counts").addProperty("not_assessed",1);
        goal.observe(state);
        assertFalse(goal.satisfied(),"an optimistic success flag cannot hide the final missing cell");
        evidence.addProperty("total",16385);
        assertThrows(RuntimeException.class,()->goal.observe(state));
    }
    @Test void restoredWorldGoalUsesFreshBodyAndExactRevisionButKeepsDeliveryBaselines() {
        var original = new WorldGoal(building(), buildState(10));
        var fresh = buildState(20); fresh.addProperty("body_id", "body-after-rejoin");
        assertTrue(WorldGoal.restore(original.snapshot(), fresh).satisfied());
        fresh.getAsJsonObject("goal_evidence").addProperty("target_revision", "b".repeat(64));
        assertThrows(RuntimeException.class, () -> WorldGoal.restore(original.snapshot(), fresh));
        var delivery = new WorldGoal(delivery("additional"), deliveryState(5, 8));
        var sameContainer = deliveryState(8, 11);
        assertTrue(WorldGoal.restore(delivery.snapshot(), sameContainer).satisfied());
        sameContainer.getAsJsonObject("goal_evidence").addProperty("container_id", "new-container");
        assertThrows(RuntimeException.class, () -> WorldGoal.restore(delivery.snapshot(), sameContainer));
    }
    static final String REVISION = "a".repeat(64);
    static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    static JsonObject building() {
        return json("{\"type\":\"building\",\"target\":{\"anchor\":{\"x\":1,\"y\":70,\"z\":0},"
                + "\"fill\":{\"from\":{\"x\":0,\"y\":0,\"z\":0},\"to\":{\"x\":19,\"y\":0,\"z\":0},"
                + "\"block\":\"stone\"}},\"expected_revision\":\"" + REVISION + "\"}");
    }
    static JsonObject delivery(String mode) {
        return json("{\"type\":\"delivery\",\"container\":{\"x\":2,\"y\":70,\"z\":0},"
                + "\"item\":\"diamond\",\"count\":3,\"mode\":\"" + mode + "\"}");
    }
    static JsonObject body() {
        var state = AgentLoopGoalTest.state(5);
        state.addProperty("x", 0); state.addProperty("y", 70); state.addProperty("z", 0);
        state.addProperty("dimension", "minecraft:overworld");
        state.addProperty("body_id", "body-one"); state.addProperty("alive", true);
        return state;
    }
    static JsonObject buildState(int satisfied) {
        var state = body();
        var evidence = json("{\"target_revision\":\"" + REVISION + "\",\"total\":20,\"remaining\":" + (20-satisfied)
                + ",\"counts\":{\"satisfied\":" + satisfied + ",\"not_assessed\":" + (20-satisfied)
                + ",\"unknown\":0,\"unsupported\":0},\"observation_complete\":true,\"spatial_stable\":true,"
                + "\"budget_exhausted\":false,\"goal_satisfied\":" + (satisfied == 20)
                + ",\"goal_observation_key\":\"" + String.format("%064x", satisfied) + "\"}");
        state.add("goal_evidence", evidence);
        return state;
    }
    static JsonObject deliveryState(int count, long balance) {
        var state = body();
        var evidence = json("{\"container_id\":\"container-one\",\"container\":true,\"container_identity_known\":true,"
                + "\"contents_complete\":true,\"dimension\":\"minecraft:overworld\",\"item\":\"minecraft:diamond\","
                + "\"x\":2,\"y\":70,\"z\":0,\"slot_count\":27,\"item_total\":" + count
                + ",\"delivery_ledger_complete\":true,\"delivery_balance\":" + balance + "}");
        state.add("goal_evidence", evidence);
        return state;
    }
    @Test void buildingNeedsAllCellsAndFreshStableFactsRatherThanAnyLocalBatchClaim() {
        var goal = new WorldGoal(building(), buildState(16));
        assertFalse(goal.satisfied());
        var partial = buildState(16);
        partial.getAsJsonObject("goal_evidence").addProperty("goal_satisfied", true);
        goal.observe(partial);
        assertFalse(goal.satisfied());
        goal.observe(buildState(20));
        assertTrue(goal.satisfied());
        goal.observe(buildState(19));
        assertFalse(goal.satisfied(), "external damage must invalidate an earlier success");
    }
    @Test void buildingRejectsUnknownUnstableOverBudgetOrMismatchedRevisionEvenIfClaimedComplete() {
        for (String key : List.of("observation_complete", "spatial_stable", "budget_exhausted", "target_revision",
                "counts", "goal_observation_key")) {
            var state = buildState(20);
            var evidence = state.getAsJsonObject("goal_evidence");
            if (key.equals("budget_exhausted")) evidence.addProperty(key, true);
            else evidence.remove(key);
            assertThrows(RuntimeException.class, () -> new WorldGoal(building(), state), key);
        }
        var unsupported = buildState(19);
        var counts = unsupported.getAsJsonObject("goal_evidence").getAsJsonObject("counts");
        counts.addProperty("not_assessed", 0); counts.addProperty("unsupported", 1);
        assertFalse(new WorldGoal(building(), unsupported).satisfied());
    }
    @Test void deliveryRequiresBothActualNetTransfersAndNetContentsSinceRegistration() {
        var goal = new WorldGoal(delivery("additional"), deliveryState(5, 8));
        goal.observe(deliveryState(8, 8));
        assertEquals(0, goal.progress(), "another player's deposit earns no transfer credit");
        goal.observe(deliveryState(6, 18));
        assertEquals(1, goal.progress(), "moved totals do not replace retained container contents");
        goal.observe(deliveryState(8, 11));
        assertTrue(goal.satisfied());
        goal.observe(deliveryState(5, 8));
        assertFalse(goal.satisfied(), "withdrawing and redepositing cannot accumulate gross transfers");
        goal.observe(deliveryState(8, 11));
        assertEquals(3, goal.progress());
    }
    @Test void totalDeliveryCanReuseExistingContentsWithoutClaimingWhoSuppliedThem() {
        var state = deliveryState(5, 0);
        state.getAsJsonObject("goal_evidence").addProperty("delivery_ledger_complete", false);
        var total = new WorldGoal(delivery("total"), state);
        assertTrue(total.satisfied());
        assertTrue(total.facts().contains("当前容器总量"));
        assertThrows(RuntimeException.class, () -> new WorldGoal(delivery("additional"), state));
    }
    @Test void replacementWorldBodyCoordinatesMissingIdentityOrIncompleteSlotsFailClosed() {
        for (String key : List.of("container_id", "container_identity_known", "contents_complete", "item",
                "dimension", "x", "slot_count", "delivery_balance", "delivery_ledger_complete")) {
            var goal = new WorldGoal(delivery("additional"), deliveryState(0, 0));
            var after = deliveryState(10, 10);
            after.getAsJsonObject("goal_evidence").remove(key);
            assertThrows(RuntimeException.class, () -> goal.observe(after), key);
        }
        var goal = new WorldGoal(delivery("additional"), deliveryState(0, 0));
        for (String key : List.of("body_id", "dimension", "alive")) {
            var after = deliveryState(10, 10);
            after.remove(key);
            assertThrows(RuntimeException.class, () -> goal.observe(after));
        }
        var replacement = deliveryState(10, 10);
        replacement.getAsJsonObject("goal_evidence").addProperty("container_id", "replacement");
        assertThrows(RuntimeException.class, () -> goal.observe(replacement));
    }
    @Test void outOfReachRetainsTheConditionAndCannotReuseTheLastSuccess() {
        var goal = new WorldGoal(delivery("total"), deliveryState(5, 0));
        var far = body();
        far.addProperty("goal_evidence_unavailable", true);
        far.addProperty("goal_evidence_reason", "OUT_OF_REACH");
        goal.observe(far);
        assertFalse(goal.satisfied());
        assertEquals(0, goal.progress());
        assertTrue(goal.matches(delivery("total")));
        assertThrows(RuntimeException.class, () -> new WorldGoal(delivery("total"), far));
    }
    @Test void typedRequestsCannotChangeConditionsOrSmuggleExtraFields() {
        var goal = new WorldGoal(building(), buildState(0));
        var changed = building(); changed.addProperty("expected_revision", "b".repeat(64));
        assertFalse(goal.matches(changed));
        assertFalse(goal.matches(delivery("total")));
        var invalid = delivery("total"); invalid.addProperty("arbitrary", true);
        assertThrows(RuntimeException.class, () -> WorldGoal.request(invalid));
        for (String mode : List.of("other", "TOTAL", "")) {
            var request = delivery("total"); request.addProperty("mode", mode);
            assertThrows(RuntimeException.class, () -> WorldGoal.request(request));
        }
    }
    static JsonObject transfer(String direction, int before, int after) {
        var data = deliveryState(0, 0).getAsJsonObject("goal_evidence").deepCopy();
        data.addProperty("dir", direction);
        data.addProperty("transfer_evidence_known", true);
        data.addProperty("container_count_before", before); data.addProperty("container_count_after", after);
        data.addProperty("moved_items", Math.abs(after-before));
        return data;
    }
    static JsonObject transferArgs(String direction) {
        return json("{\"x\":2,\"y\":70,\"z\":0,\"dir\":\"" + direction + "\",\"item\":\"diamond\"}");
    }
    @Test void taskLedgerTracksPartialFailedTransfersAndBothDirectionsWithoutGrossInflation() {
        var ledger = new DeliveryLedger();
        ledger.started();
        ledger.terminal(transferArgs("in"), new ToolExecutor.ToolOutcome(false, "partial", transfer("in", 5, 8)));
        var evidence = deliveryState(0,0).getAsJsonObject("goal_evidence");
        ledger.describe(evidence);
        assertTrue(evidence.get("delivery_ledger_complete").getAsBoolean());
        assertEquals(3, evidence.get("delivery_balance").getAsLong());
        ledger.started();
        ledger.terminal(transferArgs("out"), new ToolExecutor.ToolOutcome(true, "actual", transfer("out", 8, 5)));
        ledger.describe(evidence);
        assertEquals(0, evidence.get("delivery_balance").getAsLong());
    }
    @Test void unobservedTimedOutOrUnfilteredTransferCannotBeErasedByLaterQueries() {
        var ledger = new DeliveryLedger();
        var evidence = deliveryState(0,0).getAsJsonObject("goal_evidence");
        ledger.started(); ledger.describe(evidence);
        assertFalse(evidence.get("delivery_ledger_complete").getAsBoolean());
        ledger.terminal(transferArgs("in"), new ToolExecutor.ToolOutcome(false, "unknown"));
        ledger.describe(evidence);
        assertFalse(evidence.get("delivery_ledger_complete").getAsBoolean());
        var unfiltered = new DeliveryLedger();
        var args = transferArgs("in"); args.remove("item");
        unfiltered.terminal(args, new ToolExecutor.ToolOutcome(true, "actual", transfer("in",0,3)));
        unfiltered.describe(evidence);
        assertFalse(evidence.get("delivery_ledger_complete").getAsBoolean());
    }
    @Test void ledgerCannotAcceptWrongCoordinatesDirectionOrItemAndStopsAtItsStorageBound() {
        for (String key : List.of("x", "dir", "item", "container_count_after", "moved_items")) {
            var ledger = new DeliveryLedger();
            var data = transfer("in", 0, 3); data.remove(key);
            ledger.terminal(transferArgs("in"), new ToolExecutor.ToolOutcome(true, "actual", data));
            var evidence = deliveryState(0,0).getAsJsonObject("goal_evidence");
            ledger.describe(evidence);
            assertFalse(evidence.get("delivery_ledger_complete").getAsBoolean());
        }
        var ledger = new DeliveryLedger();
        for (int i=0; i<129; i++) {
            var data = transfer("in",0,3); data.addProperty("container_id", "container-" + i);
            ledger.terminal(transferArgs("in"), new ToolExecutor.ToolOutcome(true,"actual",data));
        }
        var evidence = deliveryState(0,0).getAsJsonObject("goal_evidence");
        ledger.describe(evidence);
        assertFalse(evidence.get("delivery_ledger_complete").getAsBoolean());
    }
}
