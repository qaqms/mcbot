package com.neko.mcbot.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.common.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunnerWorkflowTest {
    private final OfflineAgentClient client = new OfflineAgentClient();
    private final AgentRunner runner = client.create();
    private long task;
    private static final String LOAD = "{\"tool\":\"smelt\",\"args\":{\"x\":2,\"y\":90,\"z\":0,"
            + "\"action\":\"load\",\"input_slot\":0,\"input_count\":3,\"fuel_slot\":1,\"fuel_count\":1}}";
    private static final String WAIT = "{\"tool\":\"wait\",\"args\":{\"seconds\":30}}";
    private static final String TAKE = "{\"tool\":\"smelt\",\"args\":{\"x\":2,\"y\":90,\"z\":0,"
            + "\"action\":\"take\",\"count\":3}}";
    private static final String INVENTORY = "{\"tool\":\"inventory\",\"args\":{}}";

    @AfterEach void close() { runner.close(); client.drain(); }

    private void start(String directive, String args) {
        runner.start();
        task = runner.submitTask(directive);
        client.engine().tools(0, new ToolCall("flow", "workflow", args));
        client.drain();
    }
    private static String plan(String... steps) { return "{\"steps\":[" + String.join(",", steps) + "]}"; }
    private void receipt(boolean ok, String text, String data) {
        JsonObject body = new JsonObject();
        body.addProperty("seq", client.lastTool().num("seq", -1));
        body.addProperty("ok", ok);
        body.addProperty("feedback", text);
        body.add("data", JsonParser.parseString(data));
        runner.handleS2c(new Envelope("tool_result", body));
        client.drain();
    }
    private Msg.Tool result() {
        return client.engine().histories.getLast().stream().filter(Msg.Tool.class::isInstance)
                .map(Msg.Tool.class::cast).filter(t -> t.callId().equals("flow")).findFirst().orElseThrow();
    }
    private void ack(String job) {
        JsonObject body = new JsonObject();
        body.addProperty("seq", client.lastTool().num("seq", -1));
        body.addProperty("job_id", job);
        body.addProperty("cap_ticks", 1200);
        body.addProperty("text", "ACCEPTED:only accepted");
        runner.handleS2c(new Envelope("job_ack", body));
        client.drain();
    }
    private void terminal(long seq, String job, String phase, String data) {
        JsonObject body = new JsonObject();
        body.addProperty("seq", seq);
        body.addProperty("job_id", job);
        body.addProperty("phase", phase);
        body.addProperty("text", "actual " + phase);
        body.add("data", JsonParser.parseString(data));
        runner.handleS2c(new Envelope("job_event", body));
        client.drain();
    }

    @Test void smeltingUsesOneModelTurnAndWaitsForEveryRealReceipt() {
        start("smelt three ingots", plan(LOAD, WAIT, TAKE, INVENTORY));
        assertEquals("smelt", client.lastTool().str("tool"));
        receipt(true, "loaded three; autonomous furnace", "{\"loaded_input_count\":3,\"loaded_fuel_count\":1}");
        assertEquals("wait", client.lastTool().str("tool"));
        assertEquals(1, client.engine().responses.size());
        receipt(true, "wait finished, output unknown", "{}");
        assertEquals("smelt", client.lastTool().str("tool"));
        receipt(true, "taken three ingots", "{\"taken_count\":3}");
        assertEquals("inventory", client.lastTool().str("tool"));
        receipt(true, "iron ingot x3", "{}");
        assertEquals(4, client.toolCount());
        assertEquals(2, client.engine().responses.size());
        assertTrue(result().ok());
        assertTrue(result().content().contains("taken three ingots"));
        assertTrue(result().content().contains("4/4"));
    }

    @Test void gatheringAcceptedIsNotDoneAndDuplicateOrWrongSeqCannotStartCraft() {
        start("gather then craft", plan("{\"tool\":\"break_block\",\"args\":{\"x\":1,\"y\":90,\"z\":0}}",
                "{\"tool\":\"craft\",\"args\":{\"item\":\"oak_planks\",\"count\":4}}"));
        long seq = client.lastTool().num("seq", -1);
        ack("job");
        terminal(seq + 1, "job", "done", "{}");
        terminal(seq, "job", "progress", "{}");
        assertEquals(1, client.toolCount());
        terminal(seq, "job", "done", "{\"removed\":true,\"collected_count\":1}");
        assertEquals("craft", client.lastTool().str("tool"));
        terminal(seq, "job", "done", "{}");
        assertEquals(2, client.toolCount());
        receipt(true, "crafted four", "{\"crafted_count\":4}");
        assertTrue(result().ok());
        assertFalse(result().content().contains("ACCEPTED:"));
    }

    @Test void failureRetainsCommittedLoadAndNeverTakesOrRetries() {
        start("smelt", plan(LOAD, WAIT, TAKE));
        receipt(true, "loaded three", "{\"loaded_input_count\":3}");
        receipt(false, "TIMEOUT:wait uncertain", "{}");
        assertFalse(result().ok());
        assertTrue(result().content().contains("loaded three"));
        assertTrue(result().content().contains("TIMEOUT:wait uncertain"));
        assertEquals(2, client.toolCount());
    }

    @Test void successfulEmptySearchStopsBeforeAnyDig() {
        start("search", plan("{\"tool\":\"find_resource\",\"args\":{\"targets\":[\"#minecraft:logs\"]}}",
                "{\"tool\":\"break_block\",\"args\":{\"x\":1,\"y\":90,\"z\":0}}"));
        receipt(true, "search complete; unknown beyond samples", "{\"no_targets\":true}");
        assertEquals(1, client.toolCount());
        assertFalse(result().ok());
        assertTrue(result().content().startsWith("NO_TARGETS:"));
    }

    @Test void successfulCraftQueryWithInsufficientMaterialsStopsBeforeCraft() {
        start("craft", plan("{\"tool\":\"craft\",\"args\":{\"item\":\"stick\",\"query\":true}}",
                "{\"tool\":\"craft\",\"args\":{\"item\":\"stick\",\"count\":4}}"));
        receipt(true, "missing materials", "{\"can_craft\":false,\"crafted_count\":0}");
        assertFalse(result().ok());
        assertEquals(1, client.toolCount());
    }

    @Test void readonlyPlanIsRejectedEntirelyBeforeItsFirstObservation() {
        start("[只读] inspect", plan(INVENTORY, LOAD));
        assertEquals(0, client.toolCount());
        assertFalse(result().ok());
        assertTrue(result().content().contains("只读"));
    }

    @Test void readonlyQueriesAreAllowedAndDoNotCreateNewBridgeProtocol() {
        start("[只读] inspect", plan(INVENTORY, "{\"tool\":\"smelt\",\"args\":{\"x\":2,\"y\":90,\"z\":0}}"));
        receipt(true, "inventory", "{}");
        receipt(true, "machine state", "{}");
        assertTrue(result().ok());
        assertTrue(client.sent.stream().noneMatch(e -> e.kind().contains("workflow")));
    }

    @Test void invalidLaterArgumentsAndUnboundedToolsRejectTheWholePlan() {
        for (String invalid : List.of(
                "{\"tool\":\"craft\",\"args\":{\"item\":\"stick\"}}",
                "{\"tool\":\"smelt\",\"args\":{\"x\":2,\"y\":90,\"z\":0,\"action\":\"take\"}}",
                "{\"tool\":\"smelt\",\"args\":{\"x\":2,\"y\":90,\"z\":0,\"action\":\"query\",\"fuel_slot\":1}}",
                "{\"tool\":\"wait\",\"args\":{\"seconds\":0}}",
                "{\"tool\":\"equip\",\"args\":{\"slot\":1.5}}",
                "{\"tool\":\"break_block\",\"args\":{\"x\":\"1\",\"y\":90,\"z\":0}}",
                "{\"tool\":\"transfer\",\"args\":{}}", "{\"tool\":\"move_to\",\"args\":{}}",
                "{\"tool\":\"workflow\",\"args\":{}}", "{\"tool\":\"attack\",\"args\":{}}")) {
            assertThrows(RuntimeException.class, () -> BoundedWorkflow.parse(plan(INVENTORY, invalid)));
        }
        start("craft", plan(INVENTORY, "{\"tool\":\"craft\",\"args\":{\"item\":\"stick\"}}"));
        assertEquals(0, client.toolCount());
        assertFalse(result().ok());
    }

    @Test void countsAndStepsAndDeadlineHaveHardBoundaries() {
        String craft = "{\"tool\":\"craft\",\"args\":{\"item\":\"stick\",\"count\":64}}";
        assertEquals(128, BoundedWorkflow.parse(plan(craft, craft)).itemRequests());
        assertThrows(RuntimeException.class, () -> BoundedWorkflow.parse(plan(craft, craft, craft)));
        assertEquals(12, BoundedWorkflow.parse(plan(java.util.Collections.nCopies(12, INVENTORY).toArray(String[]::new))).steps().size());
        assertThrows(RuntimeException.class, () -> BoundedWorkflow.parse(plan(java.util.Collections.nCopies(13, INVENTORY).toArray(String[]::new))));
        assertThrows(RuntimeException.class, () -> BoundedWorkflow.parse("{\"steps\":[" + INVENTORY + "],\"timeout_seconds\":301}"));
    }

    @Test void exactDeadlineCancelsInflightAndDoesNotAcceptLateResult() {
        start("smelt", "{\"steps\":[" + LOAD + "," + WAIT + "," + TAKE + "],\"timeout_seconds\":1}");
        receipt(true, "committed load", "{\"loaded_input_count\":3}");
        long seq = client.lastTool().num("seq", -1);
        client.now += 1000;
        runner.tick();
        client.drain();
        assertEquals(2, client.toolCount());
        assertFalse(result().ok());
        assertTrue(result().content().startsWith("TIMEOUT:"));
        assertTrue(result().content().contains("committed load"));
        terminal(seq, "late", "done", "{}");
        assertEquals(2, client.toolCount());
        assertEquals(0, JsonParser.parseString(runner.statusJson()).getAsJsonObject().get("pending_tools").getAsInt());
    }

    @Test void cancellingAcceptedChildPreservesPriorReceiptsInPairedHistory() {
        start("smelt", plan(LOAD, WAIT, TAKE));
        receipt(true, "committed load", "{\"loaded_input_count\":3}");
        long seq = client.lastTool().num("seq", -1);
        ack("wait-job");
        assertTrue(runner.cancelTask(task));
        client.drain();
        terminal(seq, "wait-job", "done", "{}");
        runner.submitTask("check actual state");
        assertEquals(2, client.toolCount());
        assertTrue(result().content().contains("CANCELLED:"));
        assertTrue(result().content().contains("committed load"));
        assertTrue(result().content().contains("1/3"));
        assertEquals(0, JsonParser.parseString(runner.statusJson()).getAsJsonObject().get("pending_jobs").getAsInt());
    }

    @Test void reloadStopsOldSequenceAndOldTerminalCannotReachNewBrain() {
        start("smelt", plan(LOAD, WAIT));
        long seq = client.lastTool().num("seq", -1);
        ack("old");
        runner.reconfigure(OfflineAgentClient.NEXT);
        client.drain();
        terminal(seq, "old", "done", "{}");
        assertEquals(1, client.toolCount());
        assertEquals(2, client.engines.size());
        assertTrue(client.engine().responses.isEmpty());
    }

    @Test void needConfirmKeepsActualAuthorizationAndStopsWithoutAutoApproval() {
        start("craft", plan(LOAD, TAKE));
        receipt(false, "NEED_CONFIRM:actual scope", "{\"authorization_id\":\"scope\",\"authorization_summary\":\"server scope\"}");
        assertFalse(result().ok());
        assertEquals(1, client.toolCount());
        client.engine().tools(1, new ToolCall("ask", "ask_owner", "{\"text\":\"invented\",\"authorization_id\":\"scope\"}"));
        client.drain();
        assertTrue(client.chat.stream().anyMatch(s -> s.contains("server scope") && !s.contains("invented")));
        assertTrue(client.sent.stream().noneMatch(e -> e.kind().equals("authorize")));
    }

    @Test void oversizedReceiptsStopAndReturnBoundedSummary() {
        start("inspect", plan(INVENTORY, INVENTORY));
        receipt(true, "x".repeat(15000), "{}");
        assertFalse(result().ok());
        assertEquals(1, client.toolCount());
        assertTrue(result().content().startsWith("RESULT_LIMIT:"));
    }

    @Test void acceptedChildTimeoutCompletesWorkflowAndNeverStartsNextStep() {
        start("gather", plan("{\"tool\":\"break_block\",\"args\":{\"x\":1,\"y\":90,\"z\":0}}", INVENTORY));
        ack("timeout");
        client.now += 75001;
        runner.tick();
        client.drain();
        assertEquals(1, client.toolCount());
        assertFalse(result().ok());
        assertTrue(result().content().contains("TIMEOUT:"));
        assertEquals(0, JsonParser.parseString(runner.statusJson()).getAsJsonObject().get("pending_jobs").getAsInt());
    }

    @Test void queuedExpiredChildIsNotSentAfterDeadlineAndLateReceiptCannotExtendIt() {
        start("smelt", "{\"steps\":[" + LOAD + "," + WAIT + "],\"timeout_seconds\":1}");
        JsonObject body = new JsonObject();
        body.addProperty("seq", client.lastTool().num("seq", -1));
        body.addProperty("ok", true);
        body.addProperty("feedback", "load committed");
        runner.handleS2c(new Envelope("tool_result", body));
        // The next child's send is queued, but has not crossed the network boundary.
        client.now += 1000;
        runner.tick();
        client.drain();
        assertEquals(1, client.toolCount());
        assertFalse(result().ok());
        assertTrue(result().content().contains("load committed"));
    }

    @Test void shortTakeReportsActualQuantityRatherThanInventingRequestedOutput() {
        start("take", plan(TAKE));
        receipt(true, "taken only one", "{\"taken_count\":1}");
        assertTrue(result().ok());
        assertTrue(result().content().contains("taken only one"));
        assertFalse(result().content().contains("taken three"));
    }

    @Test void missingBatchFuelStopsBeforeWaitEvenThoughLoadWasCommitted() {
        start("smelt", plan(LOAD, WAIT, TAKE));
        receipt(true, "loaded three but only fuel for one", "{\"loaded_input_count\":3,"
                + "\"state\":\"COOKING\",\"fuel_sufficient_for_input\":false}");
        assertFalse(result().ok());
        assertTrue(result().content().startsWith("MACHINE_BLOCKED:"));
        assertTrue(result().content().contains("loaded three"));
        assertEquals(1, client.toolCount());
    }

    @Test void cancelDuringEarlyStreamingRetainsCommittedReceiptsWithoutInventingToolGroup() {
        runner.start();
        task = runner.submitTask("smelt");
        client.engine().early(0, new ToolCall("flow", "workflow", plan(LOAD, WAIT, TAKE)));
        client.drain();
        JsonObject body = new JsonObject();
        body.addProperty("seq", client.lastTool().num("seq", -1));
        body.addProperty("ok", true);
        body.addProperty("feedback", "early committed load");
        runner.handleS2c(new Envelope("tool_result", body));
        client.drain();
        assertEquals("wait", client.lastTool().str("tool"));
        assertTrue(runner.cancelTask(task));
        client.drain();
        assertTrue(client.engine().responses.getFirst().isCancelled());
        runner.submitTask("check before doing more");
        var history = client.engine().histories.getLast();
        assertTrue(history.stream().anyMatch(message -> message instanceof Msg.Nudge n
                && n.text().contains("early committed load") && n.text().contains("CANCELLED:")));
        assertTrue(history.stream().noneMatch(Msg.Tool.class::isInstance));
        assertEquals(2, client.toolCount());
    }
}
