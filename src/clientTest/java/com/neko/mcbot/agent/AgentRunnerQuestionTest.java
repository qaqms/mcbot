package com.neko.mcbot.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.llm.AssistantTurn;
import com.neko.mcbot.agentcore.llm.ChatEngine;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.agentcore.llm.ToolSpec;
import com.neko.mcbot.agentcore.bridge.BridgeBackend;
import com.neko.mcbot.agentcore.bridge.BridgeService;
import com.neko.mcbot.agentcore.bridge.EventRing;
import com.neko.mcbot.agentcore.prompt.PromptBuilder;
import com.neko.mcbot.bridge.BridgeEventCapture;
import com.neko.mcbot.cfg.ClientConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunnerQuestionTest {
    private static final ClientConfig CONFIG =
            new ClientConfig("http://127.0.0.1", "offline-model", "synthetic", "");

    private static final class Engine implements ChatEngine {
        final List<List<Msg>> histories = new ArrayList<>();
        final List<CompletableFuture<AssistantTurn>> responses = new ArrayList<>();

        @Override
        public CompletableFuture<AssistantTurn> chat(String system, List<Msg> history, List<ToolSpec> tools) {
            histories.add(List.copyOf(history));
            var response = new CompletableFuture<AssistantTurn>();
            responses.add(response);
            return response;
        }

        void question(int turn, String callId, String text) {
            JsonObject args = new JsonObject();
            args.addProperty("text", text);
            responses.get(turn).complete(new AssistantTurn("", List.of(
                    new ToolCall(callId, "ask_owner", args.toString())), 0, 0, -1, "tool_calls"));
        }

        void reply(int turn, String text) {
            responses.get(turn).complete(new AssistantTurn(text, List.of(), 0, 0, -1, "stop"));
        }
    }

    private static final class Services implements AgentRunner.ClientServices {
        final List<Engine> engines = new ArrayList<>();
        final List<String> chat = new ArrayList<>();
        final List<ClientConfig> saved = new ArrayList<>();
        long now = 1_700_000_000_000L;
        int cancels;

        @Override public ChatEngine engine(ClientConfig cfg) {
            var engine = new Engine();
            engines.add(engine);
            return engine;
        }
        @Override public PromptBuilder prompt(ClientConfig cfg) { return new PromptBuilder(() -> "SYS"); }
        @Override public void showChat(String text) { chat.add(text); }
        @Override public void sendCancel(AgentRunner owner) { cancels++; }
        @Override public void saveConfig(ClientConfig cfg) { saved.add(cfg); }
        @Override public long nowMs() { return now; }
        @Override public boolean inGame() { return false; }
        @Override public void executeOnClient(Runnable action) { action.run(); }
        @Override public boolean canSend() { return false; }
        @Override public void send(com.neko.mcbot.common.Envelope envelope) {
            fail("question tests must not send game packets");
        }
        @Override public boolean isCurrentRunner(AgentRunner owner) { return true; }
    }

    private final Services services = new Services();
    private final BridgeEventCapture capture = new BridgeEventCapture();
    private final AgentRunner runner = new AgentRunner(CONFIG, services);

    @AfterEach
    void close() {
        runner.close();
        capture.close();
    }

    private Engine engine() { return services.engines.getLast(); }
    private JsonObject status() { return JsonParser.parseString(runner.statusJson()).getAsJsonObject(); }
    private List<JsonObject> events(String type) {
        return capture.events().stream().filter(e -> type.equals(e.get("ev").getAsString())).toList();
    }
    private String questionId() {
        return events("question").getLast().get("question_id").getAsString();
    }
    private long startQuestion() {
        runner.start();
        long task = runner.submitTask("choose a direction");
        engine().question(0, "ask-call", "Which direction?");
        assertEquals(1, status().get("pending_questions").getAsInt());
        return task;
    }
    private static Msg.Tool lastReceipt(Engine engine) {
        return assertInstanceOf(Msg.Tool.class, engine.histories.getLast().getLast());
    }
    private void assertTerminal(long task, String expected) {
        var done = events("done").stream().filter(e -> e.get("task_id").getAsLong() == task).toList();
        assertEquals(1, done.size());
        assertEquals(expected, done.getFirst().get("status").getAsString());
    }

    @Test
    void answerResumesExactlyItsTaskAndPreservesTheToolCallPair() {
        long task = startQuestion();
        String qid = questionId();
        assertEquals(task, events("question").getFirst().get("task_id").getAsLong());
        assertEquals(1, engine().responses.size());
        assertTrue(events("done").isEmpty());
        assertTrue(runner.answerQuestion(qid, "east"));
        assertEquals(2, engine().responses.size());
        assertEquals(task, status().get("current_task").getAsLong());
        assertEquals(0, status().get("pending_questions").getAsInt());
        var observed = capture.events();
        assertEquals("state", observed.get(observed.size() - 2).get("ev").getAsString(),
                "answer acknowledgement must precede resumed tool progress");
        assertTrue(observed.get(observed.size() - 2).get("text").getAsString().contains(qid));
        assertEquals("progress", observed.getLast().get("ev").getAsString());
        Msg.Tool receipt = lastReceipt(engine());
        assertEquals("ask-call", receipt.callId());
        assertEquals("ask_owner", receipt.name());
        assertTrue(receipt.ok());
        assertTrue(receipt.content().contains("east"));
        assertFalse(runner.answerQuestion(qid, "west"));
        assertEquals(2, engine().responses.size());
        engine().reply(1, "Going east");
        assertTerminal(task, "completed");
    }

    @Test
    void invalidOrUnknownAnswerDoesNotConsumeThePendingQuestion() {
        startQuestion();
        String qid = questionId();
        for (String invalid : new String[]{null, "", "unknown", "q", "q-4", "q999999999999999999999999"}) {
            assertFalse(runner.answerQuestion(invalid, "east"));
        }
        assertFalse(runner.answerQuestion("q+" + qid.substring(1), "east"));
        assertFalse(runner.answerQuestion("q0" + qid.substring(1), "east"));
        assertFalse(runner.answerQuestion(qid, null));
        assertFalse(runner.answerQuestion(qid, " "));
        assertEquals(1, status().get("pending_questions").getAsInt());
        assertEquals(1, engine().responses.size());
        assertTrue(runner.answerQuestion(qid, "east"));
        engine().reply(1, "Done");
    }

    @Test
    void queuedTaskCannotReceiveTheActiveTasksAnswer() {
        long task = startQuestion();
        String firstQuestion = questionId();
        long next = runner.submitTask("next choice");
        assertEquals(task, status().get("current_task").getAsLong());
        assertEquals(1, status().get("queued_tasks").getAsInt());
        assertTrue(runner.answerQuestion(firstQuestion, "east"));
        engine().reply(1, "First done");
        assertTerminal(task, "completed");
        assertEquals(next, status().get("current_task").getAsLong());
        engine().question(2, "next-ask", "Next direction?");
        String secondQuestion = questionId();
        assertNotEquals(firstQuestion, secondQuestion);
        assertFalse(runner.answerQuestion(firstQuestion, "late"));
        assertEquals(3, engine().responses.size());
        assertTrue(runner.answerQuestion(secondQuestion, "north"));
        assertEquals("next-ask", lastReceipt(engine()).callId());
        engine().reply(3, "Next done");
        assertTerminal(next, "completed");
    }

    @Test
    void cancellingTheTaskInvalidatesItsQuestionAndPairsHistoryForTheNextTask() {
        long task = startQuestion();
        String qid = questionId();
        assertTrue(runner.cancelTask(task));
        assertFalse(runner.cancelTask(task));
        assertEquals(1, services.cancels);
        assertEquals(0, status().get("pending_questions").getAsInt());
        assertEquals(1, engine().responses.size());
        assertFalse(runner.answerQuestion(qid, "late"));
        assertTerminal(task, "cancelled");
        long next = runner.submitTask("new task");
        var receipt = engine().histories.getLast().stream().filter(Msg.Tool.class::isInstance)
                .map(Msg.Tool.class::cast).findFirst().orElseThrow();
        assertEquals("ask-call", receipt.callId());
        assertFalse(receipt.ok());
        assertTrue(receipt.content().startsWith("CANCELLED:"));
        engine().reply(1, "New done");
        assertTerminal(next, "completed");
    }

    @Test
    void tickExpiresTheQuestionOnceAndTheOldAnswerCannotOverrideTheTimeout() {
        long task = startQuestion();
        String qid = questionId();
        services.now += 120_000;
        runner.tick();
        assertEquals(1, status().get("pending_questions").getAsInt());
        services.now++;
        runner.tick();
        assertEquals(0, status().get("pending_questions").getAsInt());
        assertEquals(2, engine().responses.size());
        Msg.Tool receipt = lastReceipt(engine());
        assertFalse(receipt.ok());
        assertTrue(receipt.content().startsWith("TIMEOUT:"));
        runner.tick();
        assertFalse(runner.answerQuestion(qid, "late"));
        assertEquals(receipt, lastReceipt(engine()));
        assertEquals(2, engine().responses.size());
        engine().reply(1, "No answer received");
        assertTerminal(task, "completed");
    }

    @Test
    void overdueAnswerIsRejectedEvenBeforeTheNextTick() {
        startQuestion();
        String qid = questionId();
        services.now += 120_001;
        assertFalse(runner.answerQuestion(qid, "late"));
        assertEquals(0, status().get("pending_questions").getAsInt());
        assertEquals(2, engine().responses.size());
        assertFalse(lastReceipt(engine()).ok());
        assertTrue(lastReceipt(engine()).content().startsWith("TIMEOUT:"));
        assertFalse(runner.answerQuestion(qid, "again"));
        engine().reply(1, "No answer received");
    }

    @Test
    void configurationReloadInvalidatesTheOldQuestionAndUsesFreshHistory() {
        long task = startQuestion();
        String qid = questionId();
        Engine oldEngine = engine();
        var nextConfig = new ClientConfig("http://127.0.0.1", "offline-next", "synthetic", "next");
        runner.reconfigure(nextConfig);
        assertEquals(List.of(nextConfig), services.saved);
        assertSame(nextConfig, runner.config());
        assertEquals(2, services.engines.size());
        assertEquals(0, status().get("pending_questions").getAsInt());
        assertEquals(0, status().get("current_task").getAsLong());
        assertFalse(runner.answerQuestion(qid, "old answer"));
        assertEquals(1, oldEngine.responses.size());
        assertTerminal(task, "cancelled");

        long next = runner.submitTask("fresh task");
        assertEquals(List.of(new Msg.User("fresh task")), engine().histories.getFirst());
        engine().question(0, "fresh-ask", "Fresh question?");
        assertNotEquals(qid, questionId());
        assertFalse(runner.answerQuestion(qid, "old answer"));
        assertEquals(1, engine().responses.size());
        assertTrue(runner.answerQuestion(questionId(), "fresh answer"));
        engine().reply(1, "Fresh done");
        assertTerminal(next, "completed");
    }

    @Test
    void closeInvalidatesQuestionsAndRejectsFutureAnswers() {
        long task = startQuestion();
        String qid = questionId();
        runner.close();
        int count = capture.events().size();
        int cancels = services.cancels;
        runner.close();
        assertFalse(runner.answerQuestion(qid, "late"));
        runner.tick();
        runner.start();
        assertEquals(count, capture.events().size());
        assertEquals(cancels, services.cancels);
        assertEquals(0, status().get("pending_questions").getAsInt());
        assertFalse(status().get("brain_enabled").getAsBoolean());
        assertEquals(1, services.engines.size());
        assertEquals(1, engine().responses.size());
        assertTerminal(task, "cancelled");
    }

    @Test
    void gameAnswerDirectiveResumesTheQuestionWithoutSubmittingAnotherTask() {
        long task = startQuestion();
        runner.onOwnerDirective("answer east");
        assertEquals(task, status().get("current_task").getAsLong());
        assertEquals(0, status().get("queued_tasks").getAsInt());
        assertEquals(2, engine().responses.size());
        assertTrue(lastReceipt(engine()).content().contains("east"));
        engine().reply(1, "Done");
    }

    @Test
    void repeatedGameAnswerDoesNotBecomeANewTask() {
        long task = startQuestion();
        runner.onOwnerDirective("answer east");
        engine().reply(1, "Done");
        int eventCount = events("done").size();
        runner.onOwnerDirective("answer east");
        assertEquals(0, status().get("current_task").getAsLong());
        assertEquals(2, engine().responses.size());
        assertEquals(eventCount, events("done").size());
        assertTerminal(task, "completed");
    }

    @Test
    void overdueGameAnswerDoesNotQueueANewDirective() {
        long task = startQuestion();
        services.now += 120_001;
        runner.onOwnerDirective("answer late");
        assertEquals(task, status().get("current_task").getAsLong());
        assertEquals(0, status().get("queued_tasks").getAsInt());
        assertEquals(0, status().get("pending_questions").getAsInt());
        assertEquals(2, engine().responses.size());
        assertTrue(lastReceipt(engine()).content().startsWith("TIMEOUT:"));
        engine().reply(1, "No answer received");
        assertTerminal(task, "completed");
    }

    @Test
    void legacyAskWaitsForTheTaskReportInsteadOfTheIntermediateAnswer() {
        runner.start();
        CompletableFuture<String> report = runner.askNext("choose a direction");
        long task = status().get("current_task").getAsLong();
        engine().question(0, "ask-call", "Which direction?");
        assertEquals(1, status().get("pending_asks").getAsInt());
        assertFalse(report.isDone());
        assertTrue(runner.answerQuestion(questionId(), "east"));
        assertFalse(report.isDone());
        engine().reply(1, "Final report");
        assertEquals("Final report", report.join());
        assertEquals(0, status().get("pending_asks").getAsInt());
        assertTerminal(task, "completed");
    }

    @Test
    void bridgeAnswerReturnsNotFoundForDuplicateOrOverdueQuestions() {
        var bridge = new BridgeService(new BridgeBackend() {
            @Override public long submitTask(String text) { return runner.submitTask(text); }
            @Override public CompletableFuture<String> ask(String text) { return runner.askNext(text); }
            @Override public boolean answer(String qid, String text) { return runner.answerQuestion(qid, text); }
            @Override public String statusJson() { return runner.statusJson(); }
            @Override public boolean cancel(long id) { return runner.cancelTask(id); }
        }, new EventRing(200));
        startQuestion();
        String qid = questionId();
        String body = "{\"question_id\":\"" + qid + "\",\"text\":\"east\"}";
        assertEquals(200, bridge.handle("POST", "/v1/answer", body).status());
        assertEquals(2, engine().responses.size());
        var duplicate = bridge.handle("POST", "/v1/answer", body);
        assertEquals(404, duplicate.status());
        assertEquals("NOT_FOUND", JsonParser.parseString(duplicate.body()).getAsJsonObject()
                .get("error_code").getAsString());
        engine().reply(1, "First done");

        runner.submitTask("another choice");
        engine().question(2, "another-ask", "Another direction?");
        services.now += 120_001;
        body = "{\"question_id\":\"" + questionId() + "\",\"text\":\"west\"}";
        var expired = bridge.handle("POST", "/v1/answer", body);
        assertEquals(404, expired.status());
        assertTrue(lastReceipt(engine()).content().startsWith("TIMEOUT:"));
        var mcp = bridge.handle("POST", "/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"mcbot_answer\",\"arguments\":" + body + "}}");
        var result = JsonParser.parseString(mcp.body()).getAsJsonObject().getAsJsonObject("result");
        assertTrue(result.get("isError").getAsBoolean());
        assertEquals("NOT_FOUND", JsonParser.parseString(result.getAsJsonArray("content").get(0)
                .getAsJsonObject().get("text").getAsString()).getAsJsonObject()
                .get("error_code").getAsString());
        assertEquals(4, engine().responses.size());
        engine().reply(3, "Timed out");
    }
}
