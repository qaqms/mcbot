package com.neko.mcbot.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.bridge.RunnerBridgeHarness;
import com.neko.mcbot.common.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ClientSessionLifecycleTest {
    private final OfflineAgentClient client = new OfflineAgentClient();
    private final List<RunnerBridgeHarness> bridges = new ArrayList<>();
    private final List<String> order = new ArrayList<>();
    private final ClientSession session = new ClientSession(client::create, this::startBridge, this::stopBridge);
    private RunnerBridgeHarness bridge;

    private void startBridge() {
        try {
            order.add("bridge-start");
            bridge = new RunnerBridgeHarness(session.runner(), session::runner, Runnable::run);
            bridges.add(bridge);
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }
    private void stopBridge() {
        order.add("bridge-stop");
        assertNotNull(session.runner(), "close must retain ownership until the bridge is stopped");
        assertFalse(json(session.runner().statusJson()).get("brain_enabled").getAsBoolean());
        assertEquals(0, json(session.runner().statusJson()).get("current_task").getAsLong());
        bridge.close();
    }
    private void join() {
        client.current = session::runner;
        client.connected = true;
        session.join();
        client.drain();
    }
    @AfterEach void close() {
        session.disconnect();
        client.drain();
        bridges.forEach(RunnerBridgeHarness::close);
    }
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static void assertIdle(JsonObject status) {
        for (String key : new String[]{"current_task", "queued_tasks", "pending_asks", "pending_tools",
                "pending_jobs", "pending_questions", "parked_jobs"})
            assertEquals(0, status.get(key).getAsLong(), key);
        assertFalse(status.get("parked").getAsBoolean());
    }

    @Test void reloadKeepsHttpSessionAndPublishesCancellationBeforeFreshTask() throws Exception {
        join();
        String sessionId = bridge.status().get("session_id").getAsString();
        var submitted = bridge.send("/v1/task", "{\"text\":\"old task\",\"wait_s\":0}");
        assertEquals(200, submitted.statusCode());
        long oldTask = json(submitted.body()).get("task_id").getAsLong();
        var oldEngine = client.engine();
        var queued = bridge.backend().ask("queued report");
        session.runner().reconfigure(OfflineAgentClient.NEXT);
        client.drain();
        assertTrue(queued.isCompletedExceptionally());
        assertIdle(bridge.status());
        assertEquals(sessionId, bridge.status().get("session_id").getAsString());
        assertEquals(1, order.size(), "reload must not restart the bridge");

        long next = json(bridge.send("/v1/task", "{\"text\":\"fresh task\",\"wait_s\":0}").body())
                .get("task_id").getAsLong();
        assertTrue(next > oldTask);
        oldEngine.early(0, new ToolCall("late-call", "status", "{}"));
        oldEngine.reply(0, "old reply");
        client.drain();
        assertEquals(next, bridge.status().get("current_task").getAsLong());
        assertFalse(client.chat.contains("old reply"));
        client.engine().reply(0, "fresh report");
        client.drain();
        assertIdle(bridge.status());
        var frames = bridge.framesThroughTask(next);
        var frame = frames.getFirst();
        assertEquals("1", frame.get("id").getAsString());
        assertEquals(sessionId, frame.getAsJsonObject("data").get("session_id").getAsString());
        var data = frames.stream().map(f -> f.getAsJsonObject("data")).toList();
        var oldDone = data.stream().filter(e -> "done".equals(e.get("ev").getAsString())
                && e.get("task_id").getAsLong() == oldTask).toList();
        assertEquals(1, oldDone.size());
        assertEquals("cancelled", oldDone.getFirst().get("status").getAsString());
        int oldDoneIndex = data.indexOf(oldDone.getFirst());
        int newStartedIndex = java.util.stream.IntStream.range(0, data.size())
                .filter(i -> data.get(i).get("task_id").getAsLong() == next).findFirst().orElseThrow();
        assertTrue(oldDoneIndex < newStartedIndex);
        assertEquals("completed", data.getLast().get("status").getAsString());
    }

    @Test void reconnectDropsOldNetworkCallbacksChangesBridgeSessionAndResetsSseIds() throws Exception {
        join();
        var oldRunner = session.runner();
        var oldBridge = bridge;
        String oldSession = bridge.status().get("session_id").getAsString();
        long oldTask = bridge.backend().submitTask("old move");
        var oldEngine = client.engine();
        oldEngine.tools(0, new ToolCall("old-move", "move_to", "{\"x\":2,\"y\":63,\"z\":4}"));
        client.drain();
        long oldSeq = client.lastTool().num("seq", -1);
        session.receive(AgentRunnerLifecycleTest.ack(oldSeq), Runnable::run);
        assertTrue(bridge.status().get("parked").getAsBoolean());
        // These callbacks were captured by the old connection but remain in the client queue.
        session.receive(AgentRunnerLifecycleTest.job(oldSeq, "done"), client::executeOnClient);
        var state = new JsonObject();
        state.addProperty("companion", "old-world");
        state.addProperty("text", "old world state");
        session.receive(new Envelope("companion_state", state), client::executeOnClient);
        client.connected = false;
        session.disconnect();
        assertNull(session.runner());
        assertIdle(json(oldRunner.statusJson()));
        session.disconnect();
        join();
        assertNotSame(oldRunner, session.runner());
        assertIdle(bridge.status());
        assertEquals(0, client.cancelCount(), "queued old cancellation must not stop the new connection");
        String newSession = bridge.status().get("session_id").getAsString();
        assertNotEquals(oldSession, newSession);
        assertEquals("", bridge.status().get("companion").getAsString());
        assertThrows(java.util.concurrent.CompletionException.class, () -> oldBridge.backend().cancel(oldTask));
        assertFalse(json(oldBridge.backend().statusJson()).get("brain_enabled").getAsBoolean());
        long next = bridge.backend().submitTask("fresh move");
        assertTrue(next > oldTask);
        assertEquals(List.of(new Msg.User("fresh move")), client.engine().histories.getFirst());
        oldEngine.early(0, new ToolCall("stale-call", "status", "{}"));
        oldEngine.reply(0, "stale reply");
        client.engine().tools(0, new ToolCall("new-move", "move_to", "{\"x\":8,\"y\":63,\"z\":4}"));
        client.drain();
        long newSeq = client.lastTool().num("seq", -1);
        assertTrue(newSeq > oldSeq);
        session.receive(AgentRunnerLifecycleTest.ack(newSeq), Runnable::run);
        session.receive(AgentRunnerLifecycleTest.result(oldSeq), Runnable::run);
        session.receive(AgentRunnerLifecycleTest.ack(oldSeq), Runnable::run);
        session.receive(AgentRunnerLifecycleTest.job(oldSeq, "done"), Runnable::run);
        assertTrue(bridge.status().get("parked").getAsBoolean(), "old seq cannot finish a reused job id");
        assertEquals(1, client.engine().responses.size());
        session.receive(AgentRunnerLifecycleTest.job(newSeq, "done"), Runnable::run);
        assertFalse(bridge.status().get("parked").getAsBoolean());
        client.engine().reply(1, "fresh report");
        client.drain();
        assertIdle(bridge.status());
        var frames = bridge.framesThroughTask(next);
        assertEquals("1", frames.getFirst().get("id").getAsString());
        assertTrue(frames.stream().allMatch(f -> newSession.equals(
                f.getAsJsonObject("data").get("session_id").getAsString())));
        assertFalse(frames.stream().anyMatch(f -> oldTask == f.getAsJsonObject("data").get("task_id").getAsLong()));
        assertEquals("completed", frames.getLast().getAsJsonObject("data").get("status").getAsString());
        assertEquals(List.of("bridge-start", "bridge-stop", "bridge-start"), order);
    }

    @Test void repeatedJoinClosesThePreviousRunnerAndBridgeBeforeReplacement() throws Exception {
        join();
        var old = session.runner();
        var ask = bridge.backend().ask("active report");
        var queued = bridge.backend().ask("queued report");
        String oldSession = bridge.status().get("session_id").getAsString();
        session.join();
        client.drain();
        assertEquals(List.of("bridge-start", "bridge-stop", "bridge-start"), order);
        assertTrue(ask.isCompletedExceptionally());
        assertTrue(queued.isCompletedExceptionally());
        assertNotSame(old, session.runner());
        assertNotEquals(oldSession, bridge.status().get("session_id").getAsString());
        assertIdle(bridge.status());
        assertEquals(2, client.engines.size());
    }

    @Test void queuedOldBridgeSubmissionCannotRunAfterReconnect() throws Exception {
        join();
        var commands = new LinkedBlockingQueue<Runnable>();
        try (var guarded = new RunnerBridgeHarness(session.runner(), session::runner, commands::add);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var request = CompletableFuture.supplyAsync(() -> guarded.backend().submitTask("stale task"), executor);
            Runnable scheduled = commands.poll(3, TimeUnit.SECONDS);
            assertNotNull(scheduled);
            session.disconnect();
            join();
            scheduled.run();
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> request.get(3, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals(0, client.engine().responses.size());
            assertIdle(bridge.status());
        }
    }

    @Test void queuedOldBridgeAnswerCannotConsumeTheNewRunnersQuestion() throws Exception {
        join();
        bridge.backend().submitTask("old question");
        client.engine().tools(0, new ToolCall("ask-old", "ask_owner", "{\"text\":\"Old direction?\"}"));
        client.drain();
        String oldQuestion = bridge.questionId();
        var commands = new LinkedBlockingQueue<Runnable>();
        try (var guarded = new RunnerBridgeHarness(session.runner(), session::runner, commands::add);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var request = CompletableFuture.supplyAsync(() -> guarded.backend().answer(oldQuestion, "east"), executor);
            Runnable scheduled = commands.poll(3, TimeUnit.SECONDS);
            assertNotNull(scheduled);
            session.disconnect();
            join();
            bridge.backend().submitTask("new question");
            client.engine().tools(0, new ToolCall("ask-new", "ask_owner", "{\"text\":\"New direction?\"}"));
            client.drain();
            scheduled.run();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> request.get(3, TimeUnit.SECONDS));
            assertEquals(1, bridge.status().get("pending_questions").getAsInt());
            assertEquals(1, client.engine().responses.size());
            String newQuestion = bridge.questionId();
            assertNotEquals(oldQuestion, newQuestion);
            assertEquals(404, bridge.send("/v1/answer",
                    "{\"question_id\":\"" + oldQuestion + "\",\"text\":\"east\"}").statusCode());
            assertEquals(200, bridge.send("/v1/answer",
                    "{\"question_id\":\"" + newQuestion + "\",\"text\":\"west\"}").statusCode());
            assertEquals(0, bridge.status().get("pending_questions").getAsInt());
            client.engine().reply(1, "new report");
            client.drain();
        }
    }
}
