package com.neko.mcbot.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.bridge.BridgeEventCapture;
import com.neko.mcbot.common.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunnerLifecycleTest {
    enum Wait { MODEL, TOOL, EARLY_ACCEPT, PARK, QUESTION }
    private static final ToolCall MOVE = new ToolCall("move-call", "move_to", "{\"x\":12,\"y\":63,\"z\":4}");
    private static final ToolCall STATUS = new ToolCall("status-call", "status", "{}");
    private final OfflineAgentClient client = new OfflineAgentClient();
    private final AgentRunner runner = client.create();
    private final BridgeEventCapture events = new BridgeEventCapture();
    private long task;
    private long seq;
    private String question;

    @AfterEach void close() {
        runner.close();
        client.drain();
        events.close();
    }

    private JsonObject status() { return JsonParser.parseString(runner.statusJson()).getAsJsonObject(); }
    private void assertEmpty() {
        for (String key : new String[]{"current_task", "queued_tasks", "pending_asks", "pending_tools",
                "pending_jobs", "pending_questions", "parked_jobs"}) {
            assertEquals(0, status().get(key).getAsLong(), key);
        }
        assertFalse(status().get("parked").getAsBoolean());
    }
    private void terminal(long id, String expected) {
        var done = events.events().stream().filter(e -> "done".equals(e.get("ev").getAsString())
                && id == e.get("task_id").getAsLong()).toList();
        assertEquals(1, done.size());
        assertEquals(expected, done.getFirst().get("status").getAsString());
    }
    private void prepare(Wait wait) {
        runner.start();
        task = runner.submitTask("old task");
        switch (wait) {
            case MODEL -> { }
            case TOOL -> {
                client.engine().tools(0, STATUS);
                client.drain();
                seq = client.lastTool().num("seq", -1);
                assertEquals(1, status().get("pending_tools").getAsInt());
            }
            case EARLY_ACCEPT, PARK -> {
                if (wait == Wait.EARLY_ACCEPT) client.engine().early(0, MOVE);
                else client.engine().tools(0, MOVE);
                client.drain();
                seq = client.lastTool().num("seq", -1);
                runner.handleS2c(ack(seq));
                assertEquals(1, status().get("pending_jobs").getAsInt());
                assertEquals(wait == Wait.PARK, status().get("parked").getAsBoolean());
            }
            case QUESTION -> {
                client.engine().tools(0, new ToolCall("ask-call", "ask_owner", "{\"text\":\"Which way?\"}"));
                client.drain();
                question = events.events().stream().filter(e -> "question".equals(e.get("ev").getAsString()))
                        .toList().getLast().get("question_id").getAsString();
                assertEquals(1, status().get("pending_questions").getAsInt());
            }
        }
    }
    static Envelope ack(long seq) {
        var body = new JsonObject();
        body.addProperty("seq", seq);
        body.addProperty("job_id", "old-job");
        body.addProperty("cap_ticks", 3600);
        body.addProperty("text", "ACCEPTED:waiting");
        return new Envelope("job_ack", body);
    }
    static Envelope result(long seq) {
        var body = new JsonObject();
        body.addProperty("seq", seq);
        body.addProperty("ok", true);
        body.addProperty("feedback", "old receipt");
        return new Envelope("tool_result", body);
    }
    static Envelope job(long seq, String phase) {
        var body = new JsonObject();
        body.addProperty("seq", seq);
        body.addProperty("job_id", "old-job");
        body.addProperty("tool", "move_to");
        body.addProperty("phase", phase);
        body.addProperty("text", "old job event");
        return new Envelope("job_event", body);
    }
    private void late(OfflineAgentClient.Engine old) {
        old.early(0, STATUS);
        old.tools(0, MOVE);
        runner.handleS2c(result(seq));
        runner.handleS2c(ack(seq));
        runner.handleS2c(job(seq, "progress"));
        runner.handleS2c(job(seq, "done"));
        if (question != null) assertFalse(runner.answerQuestion(question, "late"));
        client.now += 300_000;
        runner.tick();
        client.drain();
    }

    @ParameterizedTest @EnumSource(Wait.class)
    void reloadCancelsEveryWaitAndIsolatesTheNewLoop(Wait wait) {
        prepare(wait);
        var old = client.engine();
        // PARK submission supersedes, so queued cancellation is covered in the other phases.
        var queued = wait == Wait.PARK ? null : runner.askNext("queued report");
        long queuedTask = wait == Wait.PARK ? 0 : task + 1;
        runner.reconfigure(OfflineAgentClient.NEXT);
        client.drain();
        assertEmpty();
        terminal(task, "cancelled");
        if (queued != null) {
            assertTrue(queued.isCompletedExceptionally());
            terminal(queuedTask, "cancelled");
        }
        assertEquals(1, client.cancelCount());
        assertEquals(2, client.engines.size());
        long next = runner.submitTask("fresh task");
        var fresh = client.engine();
        assertEquals(java.util.List.of(new Msg.User("fresh task")), fresh.histories.getFirst());
        assertEquals("next", fresh.prompts.getFirst());
        int count = events.events().size();
        long sends = client.toolCount();
        late(old);
        assertEquals(count, events.events().size(), "old callbacks must not publish into the new task");
        assertEquals(sends, client.toolCount());
        assertEquals(next, status().get("current_task").getAsLong());
        assertEquals(1, fresh.responses.size());
        fresh.reply(0, "fresh report");
        client.drain();
        terminal(next, "completed");
        assertEmpty();
    }

    @ParameterizedTest @EnumSource(Wait.class)
    void closeCancelsEveryWaitAndRejectsOldCallbacks(Wait wait) {
        prepare(wait);
        var old = client.engine();
        runner.close();
        client.drain();
        assertEmpty();
        terminal(task, "cancelled");
        int count = events.events().size();
        long sends = client.toolCount();
        runner.close();
        runner.start();
        late(old);
        assertEquals(count, events.events().size());
        assertEquals(sends, client.toolCount());
        assertEquals(1, client.cancelCount());
        assertEquals(1, client.engines.size());
        assertFalse(status().get("brain_enabled").getAsBoolean());
    }

    @Test void modelCallbacksAlreadyQueuedBeforeReloadCannotDispatch() {
        prepare(Wait.MODEL);
        var old = client.engine();
        old.early(0, MOVE);
        old.tools(0, MOVE);
        runner.reconfigure(OfflineAgentClient.NEXT);
        client.drain();
        assertEquals(0, client.toolCount());
        assertEmpty();
        terminal(task, "cancelled");
    }

    @Test void toolSendAlreadyQueuedBeforeReloadIsDroppedAndCancelPrecedesNewSend() {
        prepare(Wait.MODEL);
        client.engine().early(0, MOVE);
        client.queue.removeFirst().run();
        assertEquals(1, status().get("pending_tools").getAsInt());
        runner.reconfigure(OfflineAgentClient.NEXT);
        long next = runner.submitTask("new action");
        client.engine().tools(0, STATUS);
        client.drain();
        assertEquals(java.util.List.of("cancel", "tool_call"),
                client.sent.stream().map(Envelope::kind).toList());
        assertFalse(client.lastTool().bool("accept"));
        runner.handleS2c(result(client.lastTool().num("seq", -1)));
        client.engine().reply(1, "done");
        client.drain();
        terminal(task, "cancelled");
        terminal(next, "completed");
    }

    @Test void closeDrainsPanelInspectionAndDropsItsQueuedDisplayAndSend() {
        runner.start();
        runner.inspect("status");
        assertEquals(1, status().get("pending_tools").getAsInt());
        runner.close();
        client.drain();
        assertEmpty();
        assertEquals(0, client.toolCount());
        assertTrue(client.chat.isEmpty());
    }

    @Test void reloadDrainsPanelInspectionWithoutAddingItsOldReceiptToChat() {
        runner.start();
        runner.inspect("status");
        client.drain();
        long oldSeq = client.lastTool().num("seq", -1);
        int chat = client.chat.size();
        runner.reconfigure(OfflineAgentClient.NEXT);
        client.drain();
        assertEquals(chat + 1, client.chat.size(), "only the reload confirmation belongs to the new brain");
        chat = client.chat.size();
        runner.handleS2c(result(oldSeq));
        client.drain();
        assertEquals(chat, client.chat.size());
        assertEmpty();
    }

    @Test void inspectionDisplayAlreadyQueuedBeforeReloadDoesNotUpdateTheNewBrain() {
        runner.start();
        runner.inspect("status");
        client.drain();
        runner.handleS2c(result(client.lastTool().num("seq", -1)));
        int chat = client.chat.size();
        runner.reconfigure(OfflineAgentClient.NEXT);
        client.drain();
        assertEquals(chat + 1, client.chat.size());
        assertFalse(client.chat.stream().anyMatch(text -> text.contains("old receipt")));
        assertEmpty();
    }

    @Test void disabledConfigurationClearsActiveAndQueuedAskWaiters() {
        runner.start();
        var active = runner.askNext("active report");
        task = status().get("current_task").getAsLong();
        var queued = runner.askNext("queued report");
        runner.reconfigure(new com.neko.mcbot.cfg.ClientConfig("", "", "", ""));
        client.drain();
        assertTrue(active.isCompletedExceptionally());
        assertTrue(queued.isCompletedExceptionally());
        assertEmpty();
        assertFalse(status().get("brain_enabled").getAsBoolean());
        assertEquals(1, client.engines.size());
        terminal(task, "cancelled");
        terminal(task + 1, "cancelled");
        terminal(runner.submitTask("disabled task"), "failed");
        assertEmpty();
    }

    @Test void closedRunnerCannotSaveConfigurationOrEmitReloadFeedback() {
        runner.start();
        runner.close();
        client.drain();
        int chat = client.chat.size();
        runner.reconfigure(OfflineAgentClient.NEXT);
        client.drain();
        assertTrue(client.saved.isEmpty());
        assertEquals(chat, client.chat.size());
        assertEquals(1, client.engines.size());
        assertSame(OfflineAgentClient.CONFIG, runner.config());
    }

    @Test void connectionLossBeforeQueuedSendReturnsOneFailureWithoutSending() {
        prepare(Wait.MODEL);
        client.engine().early(0, STATUS);
        client.queue.removeFirst().run();
        client.connected = false;
        client.engine().tools(0, STATUS);
        client.drain();
        assertEquals(0, client.toolCount());
        assertEquals(0, status().get("pending_tools").getAsInt());
        var receipt = assertInstanceOf(Msg.Tool.class, client.engine().histories.getLast().getLast());
        assertFalse(receipt.ok());
        assertTrue(receipt.content().startsWith("DENIED:"));
        client.engine().reply(1, "connection lost");
        client.drain();
        terminal(task, "completed");
    }

    @Test void failedOldModelAfterReloadCannotFailOrCancelTheNewTask() {
        prepare(Wait.MODEL);
        var old = client.engine();
        runner.reconfigure(OfflineAgentClient.NEXT);
        client.drain();
        long next = runner.submitTask("fresh task");
        int eventCount = events.events().size();
        old.responses.getFirst().completeExceptionally(new IllegalStateException("offline model failure"));
        client.drain();
        assertEquals(eventCount, events.events().size());
        assertEquals(next, status().get("current_task").getAsLong());
        assertEquals(1, client.cancelCount());
        client.engine().reply(0, "fresh report");
        client.drain();
        terminal(next, "completed");
    }
}
