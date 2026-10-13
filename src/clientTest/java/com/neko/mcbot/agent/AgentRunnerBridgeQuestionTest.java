package com.neko.mcbot.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.bridge.RunnerBridgeHarness;
import com.neko.mcbot.common.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunnerBridgeQuestionTest {
    private final OfflineAgentClient client = new OfflineAgentClient();
    private final AgentRunner runner = client.create();
    private RunnerBridgeHarness bridge;

    @BeforeEach void start() throws Exception {
        runner.start();
        bridge = new RunnerBridgeHarness(runner, () -> runner, Runnable::run);
    }

    @AfterEach void close() {
        runner.close();
        client.drain();
        if (bridge != null) bridge.close();
    }

    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }

    private long submit(String text) throws Exception {
        var body = new JsonObject();
        body.addProperty("text", text);
        body.addProperty("wait_s", 0);
        var response = bridge.send("/v1/task", body.toString());
        assertEquals(200, response.statusCode());
        var result = json(response.body());
        assertFalse(result.get("done").getAsBoolean());
        client.drain();
        return result.get("task_id").getAsLong();
    }

    private void call(int turn, String name, JsonObject args) {
        client.engine().tools(turn, new ToolCall("c" + turn, name, args.toString()));
        client.drain();
    }

    private JsonObject question(long task, int turn, String text) throws Exception {
        var args = new JsonObject();
        args.addProperty("text", text);
        call(turn, "ask_owner", args);
        var frame = bridge.questionFrame(task);
        assertTrue(Long.parseLong(frame.get("id").getAsString()) > 0);
        var data = frame.getAsJsonObject("data");
        assertEquals("1.0", data.get("contract_version").getAsString());
        assertEquals(bridge.status().get("session_id").getAsString(), data.get("session_id").getAsString());
        assertEquals(task, data.get("task_id").getAsLong());
        assertEquals(text, data.get("text").getAsString());
        return data;
    }

    private HttpResponse<String> answer(String questionId, String text) throws Exception {
        var args = new JsonObject();
        args.addProperty("question_id", questionId);
        args.addProperty("text", text);
        var response = bridge.send("/v1/answer", args.toString());
        client.drain();
        return response;
    }

    private void notFound(HttpResponse<String> response) {
        assertEquals(404, response.statusCode());
        assertFalse(json(response.body()).get("ok").getAsBoolean());
        assertEquals("NOT_FOUND", json(response.body()).get("error_code").getAsString());
    }

    private void finish(int turn, long task, String text) throws Exception {
        client.engine().reply(turn, text);
        client.drain();
        var data = bridge.framesThroughTask(task).stream().map(f -> f.getAsJsonObject("data")).toList();
        var done = data.stream().filter(e -> "done".equals(e.get("ev").getAsString())
                && e.get("task_id").getAsLong() == task).toList();
        assertEquals(1, done.size());
        assertEquals("completed", done.getFirst().get("status").getAsString());
        assertEquals(text, done.getFirst().get("text").getAsString());
    }

    @Test void taskAndQuestionStayExactAcrossHttpSseAndOnlyOneAnswerResumes() throws Exception {
        String original = "  [只读]先问主人「东/西？」\n不要自行选择；回答后仅查背包。  ";
        long task = submit(original);
        assertEquals(List.of(new Msg.User(original)), client.engine().histories.getFirst());
        String qid = question(task, 0, "请选择「东」或「西」。\n回答后仅查背包。")
                .get("question_id").getAsString();
        assertEquals(1, client.engine().responses.size());
        assertEquals(200, answer(qid, "西，保持只读").statusCode());
        assertEquals(2, client.engine().responses.size());
        assertEquals(task, bridge.status().get("current_task").getAsLong());
        assertEquals(0, bridge.status().get("pending_questions").getAsInt());
        var receipt = assertInstanceOf(Msg.Tool.class, client.engine().histories.get(1).getLast());
        assertEquals("c0", receipt.callId());
        assertTrue(receipt.ok());
        assertEquals("主人说：西，保持只读", receipt.content());
        notFound(answer(qid, "东"));
        var mcp = json(bridge.send("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"mcbot_answer\",\"arguments\":{\"question_id\":\"" + qid
                + "\",\"text\":\"east\"}}}").body()).getAsJsonObject("result");
        assertTrue(mcp.get("isError").getAsBoolean());
        assertEquals("NOT_FOUND", json(mcp.getAsJsonArray("content").get(0).getAsJsonObject()
                .get("text").getAsString()).get("error_code").getAsString());
        client.drain();
        assertEquals(2, client.engine().responses.size());
        finish(1, task, "仅查看背包后结束。");
        assertEquals(0, bridge.status().get("current_task").getAsLong());
    }

    @Test void fullServerAuthorizationScopeSurvivesSseAndApprovalWaitsForReceipt() throws Exception {
        long task = submit("move only after the owner approves the exact route");
        call(0, "move_to", json("{\"x\":2,\"y\":90,\"z\":0}"));
        String authorizationId = "rg4-server-plan";
        String summary = "目的地2,90,0；具体清单：\n"
                + "dig@1,90,0 minecraft:stone；原状态必须匹配。\n".repeat(20)
                + "尾项：place@2,89,0 minecraft:cobblestone。";
        assertTrue(summary.length() > 400);
        var data = new JsonObject();
        data.addProperty("authorization_id", authorizationId);
        data.addProperty("authorization_summary", summary);
        receipt(client.lastTool().num("seq", -1), false, "NEED_CONFIRM:" + authorizationId, data);
        var args = new JsonObject();
        args.addProperty("text", "model substitute must not replace the server scope");
        args.addProperty("authorization_id", authorizationId);
        call(1, "ask_owner", args);
        var event = bridge.questionFrame(task).getAsJsonObject("data");
        assertEquals(task, event.get("task_id").getAsLong());
        assertEquals(bridge.status().get("session_id").getAsString(), event.get("session_id").getAsString());
        assertEquals(summary + "\n批准请回答「确认」，其他回答不授予路线改动权限。",
                event.get("text").getAsString());
        String qid = event.get("question_id").getAsString();
        assertEquals(200, answer(qid, "确认").statusCode());
        notFound(answer(qid, "确认"));
        var grants = client.sent.stream().filter(e -> "authorize".equals(e.kind())).toList();
        assertEquals(1, grants.size());
        var grant = grants.getFirst();
        assertEquals(task, grant.num("task_id", -1));
        assertEquals(authorizationId, grant.str("authorization_id"));
        assertEquals(2, client.engine().responses.size(), "HTTP acceptance is not a server grant");
        receipt(grant.num("seq", -1), true, "approved exact scope", null);
        assertEquals(3, client.engine().responses.size());
        var resumed = assertInstanceOf(Msg.Tool.class, client.engine().histories.get(2).getLast());
        assertEquals("c1", resumed.callId());
        assertTrue(resumed.ok());
        receipt(grant.num("seq", -1), true, "duplicate grant receipt", null);
        assertEquals(3, client.engine().responses.size());
        finish(2, task, "授权回执已取得，本夹具不执行真实路线。");
    }

    @Test void overdueHttpAnswerCannotAuthorizeOrConsumeTheNextTasksQuestion() throws Exception {
        long oldTask = submit("first choice");
        String oldQuestion = question(oldTask, 0, "First direction?").get("question_id").getAsString();
        client.now += 120_001;
        notFound(answer(oldQuestion, "late"));
        var expired = assertInstanceOf(Msg.Tool.class, client.engine().histories.get(1).getLast());
        assertFalse(expired.ok());
        assertTrue(expired.content().startsWith("TIMEOUT:"));
        finish(1, oldTask, "First question expired");

        long nextTask = submit("second choice");
        assertTrue(nextTask > oldTask);
        String nextQuestion = question(nextTask, 2, "Second direction?").get("question_id").getAsString();
        assertNotEquals(oldQuestion, nextQuestion);
        notFound(answer(oldQuestion, "stale answer"));
        assertEquals(1, bridge.status().get("pending_questions").getAsInt());
        assertEquals(3, client.engine().responses.size());
        assertEquals(200, answer(nextQuestion, "north").statusCode());
        assertEquals(4, client.engine().responses.size());
        var resumed = assertInstanceOf(Msg.Tool.class, client.engine().histories.get(3).getLast());
        assertEquals("c2", resumed.callId());
        assertEquals("主人说：north", resumed.content());
        notFound(answer(nextQuestion, "again"));
        assertEquals(4, client.engine().responses.size());
        assertTrue(client.sent.stream().noneMatch(e -> "authorize".equals(e.kind())));
        finish(3, nextTask, "Second question answered");
    }

    private void receipt(long seq, boolean ok, String feedback, JsonObject data) {
        var body = new JsonObject();
        body.addProperty("seq", seq);
        body.addProperty("ok", ok);
        body.addProperty("feedback", feedback);
        if (data != null) body.add("data", data);
        runner.handleS2c(new Envelope("tool_result", body));
        client.drain();
    }
}
