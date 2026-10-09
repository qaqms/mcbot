package com.neko.mcbot.agentcore.bridge;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class BridgeServiceTest {

    /** 可编程假后端：记录调用，按需往环里发事件。 */
    private static final class Fake implements BridgeBackend {
        final List<String> submitted = new ArrayList<>();
        final EventRing ring;
        long seq;
        CompletableFuture<String> askFuture = new CompletableFuture<>();
        String lastAnswer;

        Fake(EventRing ring) {
            this.ring = ring;
        }

        void emit(String ev, long taskId, String text) {
            JsonObject d = new JsonObject();
            d.addProperty("ev", ev);
            d.addProperty("task_id", taskId);
            d.addProperty("text", text);
            ring.add(ev, d.toString());
        }

        @Override
        public long submitTask(String text) {
            submitted.add(text);
            return ++seq;
        }

        @Override
        public CompletableFuture<String> ask(String text) {
            return askFuture;
        }

        @Override
        public boolean answer(String questionId, String text) {
            lastAnswer = questionId + "=" + text;
            return questionId.equals("q1");
        }

        @Override
        public String statusJson() {
            return "{\"companion\":\"aimi\",\"in_game\":true}";
        }

        @Override
        public boolean cancel(long taskId) {
            return true;
        }
    }

    private static BridgeService svc(BridgeBackend be, EventRing ring) {
        return new BridgeService(be, ring, 250); // ask 超时缩到 250ms 便于测
    }

    @Test
    void restTaskCollectsFragmentsUntilDone() {
        var ring = new EventRing(50);
        var fake = new Fake(ring);
        var service = svc(fake, ring);
        // 让后端 submit 时同步发出进度与完成事件
        BridgeBackend wrap = new BridgeBackend() {
            @Override
            public long submitTask(String text) {
                long id = fake.submitTask(text);
                fake.emit("progress", id, "开始挖");
                fake.emit("progress", id, "挖到 2 铁矿");
                fake.emit("done", id, "拿到 2 个粗铁");
                return id;
            }

            @Override
            public CompletableFuture<String> ask(String t) {
                return fake.ask(t);
            }

            @Override
            public boolean answer(String q, String t) {
                return fake.answer(q, t);
            }

            @Override
            public String statusJson() {
                return fake.statusJson();
            }

            @Override
            public boolean cancel(long id) {
                return fake.cancel(id);
            }
        };
        var r = svc(wrap, ring).handle("POST", "/v1/task", "{\"text\":\"挖铁矿\",\"wait_s\":1}");
        assertEquals(200, r.status());
        var body = com.google.gson.JsonParser.parseString(r.body()).getAsJsonObject();
        assertEquals(1, body.get("task_id").getAsInt());
        assertTrue(body.get("done").getAsBoolean());
        var fr = body.getAsJsonArray("fragments");
        assertEquals(3, fr.size(), "两段 progress + 一句 done 都要在");
        assertEquals("拿到 2 个粗铁", fr.get(2).getAsString());
    }

    @Test
    void otherTaskEventsAreIgnored() {
        var ring = new EventRing(50);
        var fake = new Fake(ring);
        fake.emit("progress", 99, "别的任务的事");
        var r = svc(fake, ring).handle("POST", "/v1/task", "{\"text\":\"新活\",\"wait_s\":0}");
        var body = com.google.gson.JsonParser.parseString(r.body()).getAsJsonObject();
        assertEquals(1, body.get("task_id").getAsInt());
        assertEquals(0, body.getAsJsonArray("fragments").size());
    }

    @Test
    void parkedOrAnsweredStateIsNotTaskCompletion() {
        var ring = new EventRing(50);
        BridgeBackend backend = emittingBackend(ring, id -> {
            ring.add("state", "{\"ev\":\"state\",\"task_id\":" + id + ",\"parked\":true}");
            ring.add("state", "{\"ev\":\"state\",\"task_id\":" + id + ",\"text\":\"answered q1\"}");
        });
        var result = svc(backend, ring).handle("POST", "/v1/task", "{\"text\":\"work\",\"wait_s\":0}");
        var body = com.google.gson.JsonParser.parseString(result.body()).getAsJsonObject();
        assertFalse(body.get("done").getAsBoolean(),
                "waiting for an action or answering a question must not finish the task");
    }

    @Test
    void unscopedDoneDoesNotFinishAnotherTask() {
        var ring = new EventRing(50);
        BridgeBackend backend = emittingBackend(ring,
                id -> ring.add("done", "{\"ev\":\"done\",\"task_id\":0,\"text\":\"unrelated\"}"));
        var result = svc(backend, ring).handle("POST", "/v1/task", "{\"text\":\"work\",\"wait_s\":0}");
        var body = com.google.gson.JsonParser.parseString(result.body()).getAsJsonObject();
        assertFalse(body.get("done").getAsBoolean(), "only this task's terminal event can finish it");
    }

    @Test
    void failedTaskIsTerminalButNotSuccessful() {
        var ring = new EventRing(50);
        BridgeBackend backend = emittingBackend(ring, id -> ring.add("done",
                "{\"ev\":\"done\",\"task_id\":" + id + ",\"status\":\"failed\",\"text\":\"unavailable\"}"));
        var result = svc(backend, ring).handle("POST", "/v1/task", "{\"text\":\"work\",\"wait_s\":0}");
        var body = com.google.gson.JsonParser.parseString(result.body()).getAsJsonObject();
        assertTrue(body.get("done").getAsBoolean());
        assertEquals("failed", body.get("status").getAsString());
    }

    private static BridgeBackend emittingBackend(EventRing ring, java.util.function.LongConsumer emit) {
        var fake = new Fake(ring);
        return new BridgeBackend() {
            @Override public long submitTask(String text) {
                long id = fake.submitTask(text);
                emit.accept(id);
                return id;
            }
            @Override public CompletableFuture<String> ask(String text) { return fake.ask(text); }
            @Override public boolean answer(String q, String text) { return fake.answer(q, text); }
            @Override public String statusJson() { return fake.statusJson(); }
            @Override public boolean cancel(long id) { return fake.cancel(id); }
        };
    }

    @Test
    void statusAskAnswerRoutes() throws Exception {
        var ring = new EventRing(50);
        var fake = new Fake(ring);
        var service = svc(fake, ring);

        assertEquals(200, service.handle("GET", "/v1/status", null).status());
        assertTrue(service.handle("GET", "/v1/status", null).body().contains("aimi"));

        // ask 超时（后端 future 永不完成，svc 超时 250ms）
        var to = service.handle("POST", "/v1/ask", "{\"text\":\"聊什么\"}");
        assertEquals(504, to.status());
        assertTrue(fake.askFuture.isCancelled(), "an expired HTTP waiter must release its task reply slot");
        fake.askFuture = new CompletableFuture<>();
        fake.askFuture.complete("今天天气不错");
        var ok = service.handle("POST", "/v1/ask", "{\"text\":\"聊什么\"}");
        assertEquals(200, ok.status());
        assertTrue(ok.body().contains("今天天气不错"));

        assertEquals(200, service.handle("POST", "/v1/answer",
                "{\"question_id\":\"q1\",\"text\":\"用箱子那把镐\"}").status());
        assertEquals("q1=用箱子那把镐", fake.lastAnswer);
        assertEquals(404, service.handle("POST", "/v1/answer",
                "{\"question_id\":\"q404\",\"text\":\"...\"}").status());

        assertNull(service.handle("GET", "/nope", null), "未知路由交回适配层");
    }

    @Test
    void mcpHandshakeToolsListAndCall() {
        var ring = new EventRing(50);
        var fake = new Fake(ring);
        var service = svc(fake, ring);

        var init = service.handle("POST", "/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        var initBody = com.google.gson.JsonParser.parseString(init.body()).getAsJsonObject();
        assertEquals("2.0", initBody.get("jsonrpc").getAsString());
        assertEquals(1, initBody.get("id").getAsInt());
        assertEquals("2025-03-26", initBody.getAsJsonObject("result")
                .get("protocolVersion").getAsString());

        var list = com.google.gson.JsonParser.parseString(service.handle("POST", "/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}").body())
                .getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools");
        assertEquals(5, list.size());

        var call = com.google.gson.JsonParser.parseString(service.handle("POST", "/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"mcbot_status\",\"arguments\":{}}}").body())
                .getAsJsonObject().getAsJsonObject("result").getAsJsonArray("content")
                .get(0).getAsJsonObject();
        assertEquals("text", call.get("type").getAsString());
        assertTrue(call.get("text").getAsString().contains("aimi"));

        var err = com.google.gson.JsonParser.parseString(service.handle("POST", "/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"nope\",\"arguments\":{}}}").body())
                .getAsJsonObject().getAsJsonObject("result");
        assertTrue(err.get("isError").getAsBoolean());
    }

    @Test
    void eventRingEvictsAndReplays() {
        var ring = new EventRing(3);
        for (int i = 1; i <= 5; i++) {
            ring.add("progress", "{\"i\":" + i + "}");
        }
        assertEquals(5, ring.lastId());
        var since2 = ring.since(2);
        assertEquals(3, since2.size(), "容量 3：只剩 3/4/5");
        assertEquals(3, since2.get(0).id());
        assertEquals("progress", since2.get(0).type());
    }

    @Test
    void mcpAskFailureIsAnErrorNotASuccessfulAnswer() {
        var ring = new EventRing(50);
        var fake = new Fake(ring);
        fake.askFuture.completeExceptionally(new IllegalStateException("cancelled"));
        var result = svc(fake, ring).handle("POST", "/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"mcbot_ask\",\"arguments\":{\"text\":\"work\"}}}");
        var payload = com.google.gson.JsonParser.parseString(result.body()).getAsJsonObject()
                .getAsJsonObject("result");
        assertTrue(payload.get("isError").getAsBoolean());
    }

    @Test
    void taskCollectorStopsAtItsFirstTerminalEvent() {
        var ring = new EventRing(50);
        var backend = emittingBackend(ring, id -> {
            ring.add("done", "{\"task_id\":" + id + ",\"status\":\"cancelled\",\"text\":\"stopped\"}");
            ring.add("done", "{\"task_id\":" + id + ",\"status\":\"completed\",\"text\":\"late\"}");
        });
        var response = svc(backend, ring).handle("POST", "/v1/task", "{\"text\":\"work\",\"wait_s\":0}");
        var body = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals("cancelled", body.get("status").getAsString());
        assertEquals(1, body.getAsJsonArray("fragments").size());
    }

    @Test
    void restAndMcpForwardTheRequestedCancellationScope() {
        var ring = new EventRing(50);
        var seen = new ArrayList<Long>();
        var backend = new BridgeBackend() {
            @Override public long submitTask(String text) { return 1; }
            @Override public CompletableFuture<String> ask(String text) { return new CompletableFuture<>(); }
            @Override public boolean answer(String q, String text) { return false; }
            @Override public String statusJson() { return "{}"; }
            @Override public boolean cancel(long id) { seen.add(id); return id == 22 || id == 0; }
        };
        var service = svc(backend, ring);
        assertTrue(service.handle("POST", "/v1/task/22/cancel", "").body().contains("true"));
        assertTrue(service.handle("POST", "/v1/task/99/cancel", "").body().contains("false"));
        service.handle("POST", "/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"mcbot_cancel\",\"arguments\":{}}}");
        assertEquals(List.of(22L, 99L, 0L), seen);
    }

    @Test
    void cancelledAndSupersededAreTerminalTaskStatuses() {
        for (String status : List.of("cancelled", "superseded")) {
            var ring = new EventRing(50);
            var backend = emittingBackend(ring, id -> ring.add("done", "{\"task_id\":" + id
                    + ",\"status\":\"" + status + "\"}"));
            var response = svc(backend, ring).handle("POST", "/v1/task", "{\"text\":\"work\",\"wait_s\":0}");
            var body = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
            assertTrue(body.get("done").getAsBoolean());
            assertEquals(status, body.get("status").getAsString());
        }
    }

}
