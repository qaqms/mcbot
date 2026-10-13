package com.neko.mcbot.agent;

import com.google.gson.JsonObject;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.cfg.ClientConfig;
import com.neko.mcbot.common.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunnerPanelTest {
    private final OfflineAgentClient client = new OfflineAgentClient();
    private final AgentRunner runner = client.create();
    @AfterEach void close() { runner.close(); client.drain(); }
    private long ask() {
        runner.start();
        long task = runner.submitTask("choose a direction");
        client.engine().tools(0, new ToolCall("ask", "ask_owner", "{\"text\":\"Which direction?\"}"));
        client.drain();
        return task;
    }
    @Test void personaSavePreservesThePendingQuestionAndUpdatesOnlyTheNextDirective() {
        long task = ask();
        var question = runner.questionSnapshot();
        runner.reconfigure(runner.config().withConnection(runner.config().baseUrl, runner.config().model,
                runner.config().apiKey, "next persona"));
        assertEquals(1, client.engines.size());
        assertEquals(task, runner.currentTaskId());
        assertEquals(question.id(), runner.questionSnapshot().id());
        assertEquals(0, client.cancelCount());
        assertTrue(runner.answerFromPanel(question.id(), "east"));
        client.drain();
        assertEquals("initial", client.engine().prompts.getLast());
        client.engine().reply(1, "done"); client.drain();
        runner.submitTask("next");
        assertEquals("next persona", client.engine().prompts.getLast());
    }
    @Test void settingsSavePreservesQuestionAndValidatesContextPolicy() {
        long task = ask();
        var q = runner.questionSnapshot();
        runner.reconfigure(runner.config().withPreferences(
                new ClientConfig.Preferences(64000, 48000, 8000, false, false, true, false)));
        assertEquals(task, runner.currentTaskId());
        assertEquals(q.id(), runner.questionSnapshot().id());
        assertEquals(1, client.engines.size());
        assertFalse(runner.compactContext().join());
        assertEquals(1, client.engine().responses.size());
    }
    @Test void expiredDraftCannotAnswerANewerQuestionOrCreateANewTask() {
        ask();
        var old = runner.questionSnapshot();
        assertEquals(120000, old.remainingMs());
        client.now += 120001;
        assertNull(runner.questionSnapshot());
        assertFalse(runner.answerFromPanel(old.id(), "east"));
        client.drain();
        client.engine().tools(1, new ToolCall("next-ask", "ask_owner", "{\"text\":\"New question?\"}"));
        client.drain();
        var next = runner.questionSnapshot();
        assertNotEquals(old.id(), next.id());
        assertFalse(runner.answerFromPanel(old.id(), "late"));
        assertEquals(next.id(), runner.questionSnapshot().id());
        assertEquals(1, runner.panelHistory().tasks().size());
    }
    @Test void clearingContextCancelsQuestionButPreservesTheDisplayArchive() {
        long task = ask();
        var q = runner.questionSnapshot();
        var before = runner.panelHistory().entries(task);
        runner.clearContext(); client.drain();
        assertNull(runner.questionSnapshot());
        assertFalse(runner.answerFromPanel(q.id(), "late"));
        assertEquals(0, runner.contextSnapshot().used());
        assertTrue(runner.panelHistory().entries(task).containsAll(before));
        assertEquals("cancelled", runner.panelHistory().tasks().getFirst().status());
    }
    @Test void panelObservationReadsActualDetailsWithoutAModelAndReturnsDefensiveCopies() {
        name("aimi");
        runner.refreshCompanion(); client.drain();
        assertEquals("status", client.lastTool().str("tool"));
        assertTrue(client.lastTool().obj("args").get("details").getAsBoolean());
        receipt(client.lastTool().num("seq", -1), 19);
        client.drain();
        assertEquals(19, runner.companionSnapshot().get("hp").getAsInt());
        runner.companionSnapshot().addProperty("hp", 999);
        assertEquals(19, runner.companionSnapshot().get("hp").getAsInt());
        assertTrue(client.engines.isEmpty());
        assertTrue(runner.transcriptSnapshot().isEmpty());
    }
    @Test void lateBodyReadCannotCrossACompanionChangeEvenIfItsNameChangesBack() {
        name("aimi"); runner.refreshCompanion(); client.drain();
        long old = client.lastTool().num("seq", -1);
        name("other"); name("aimi");
        receipt(old, 19); client.drain();
        assertTrue(runner.companionSnapshot().isEmpty());
        runner.refreshCompanion(); client.drain();
        receipt(client.lastTool().num("seq", -1), 20); client.drain();
        assertEquals(20, runner.companionSnapshot().get("hp").getAsInt());
    }
    private void name(String name) {
        var body = new JsonObject(); body.addProperty("companion", name); body.addProperty("text", "world sync");
        runner.handleS2c(new Envelope("companion_state", body));
    }
    private void receipt(long seq, int hp) {
        var data = new JsonObject(); data.addProperty("hp", hp); data.addProperty("dimension", "minecraft:overworld");
        var body = new JsonObject(); body.addProperty("seq", seq); body.addProperty("ok", true);
        body.addProperty("feedback", "body details"); body.add("data", data);
        runner.handleS2c(new Envelope("tool_result", body));
    }
}
