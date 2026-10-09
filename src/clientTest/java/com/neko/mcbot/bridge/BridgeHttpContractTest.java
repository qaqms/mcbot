package com.neko.mcbot.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.bridge.BridgeBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class BridgeHttpContractTest {
    private static final String TOKEN = "local-test-only";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private BridgeHttp bridge;

    private static final class Backend implements BridgeBackend {
        long tasks;
        String lastText;
        @Override public long submitTask(String text) { lastText = text; return ++tasks; }
        @Override public CompletableFuture<String> ask(String text) { return CompletableFuture.completedFuture("report"); }
        @Override public boolean answer(String id, String text) { return id.equals("q8"); }
        @Override public String statusJson() {
            return "{\"in_game\":true,\"brain_enabled\":true,\"companion\":\"steve\",\"current_task\":0}";
        }
        @Override public boolean cancel(long id) { return id == 0; }
    }

    private void start() throws Exception {
        bridge = new BridgeHttp(new Backend(), TOKEN, 0);
    }

    @AfterEach
    void close() {
        if (bridge != null) bridge.stop();
        client.close();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + bridge.port() + path))
                .timeout(Duration.ofSeconds(5));
    }

    private HttpResponse<String> send(String path, String body, boolean auth) throws Exception {
        var builder = request(path);
        if (auth) builder.header("Authorization", "Bearer " + TOKEN);
        if (body != null) builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }

    @Test
    void authRoutesAndBodyLimitHaveExplicitErrors() throws Exception {
        start();
        var unauthorized = send("/v1/status", null, false);
        assertEquals(401, unauthorized.statusCode());
        assertEquals("UNAUTHORIZED", json(unauthorized.body()).get("error_code").getAsString());
        assertEquals(401, send("/v1/status?token=" + TOKEN, null, false).statusCode());
        assertEquals(200, send("/v1/status", null, true).statusCode());
        var wrong = client.send(request("/v1/status").header("Authorization", "Bearer wrong").build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, wrong.statusCode());
        var missing = send("/unknown", null, true);
        assertEquals(404, missing.statusCode());
        assertEquals("NOT_FOUND", json(missing.body()).get("error_code").getAsString());
        var oversized = send("/v1/task", "x".repeat(65537), true);
        assertEquals(413, oversized.statusCode());
        assertEquals("BODY_TOO_LARGE", json(oversized.body()).get("error_code").getAsString());
        assertEquals(400, send("/v1/task", " ".repeat(65536), true).statusCode(),
                "exactly 65536 bytes passes the size gate and reaches JSON validation");
        assertEquals(413, send("/v1/task", "\u4e2d".repeat(22000), true).statusCode(),
                "the limit is UTF-8 bytes, not Java character count");
    }

    @Test
    void unicodeTaskValidationAndMcpPayloadsUseTheRealHttpAdapter() throws Exception {
        var backend = new Backend();
        bridge = new BridgeHttp(backend, TOKEN, 0);
        assertEquals(400, send("/v1/task", "{\"text\":true}", true).statusCode());
        String directive = "\u626b\u63cf\u9644\u8fd1\u5e76\u7b80\u62a5";
        var task = send("/v1/task", "{\"text\":\"" + directive + "\",\"wait_s\":0}", true);
        assertEquals(200, task.statusCode());
        assertEquals(1, json(task.body()).get("task_id").getAsInt());
        assertFalse(json(task.body()).get("done").getAsBoolean());
        assertFalse(json(task.body()).has("status"));
        assertEquals(directive, backend.lastText);
        var mcp = send("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":\"c1\",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"mcbot_cancel\",\"arguments\":{\"task_id\":0}}}", true);
        assertEquals(200, mcp.statusCode());
        var result = json(mcp.body());
        assertEquals("c1", result.get("id").getAsString());
        assertFalse(result.getAsJsonObject("result").get("isError").getAsBoolean());
    }

    @Test
    void sseReplayCarriesSessionMetadataAndThenDeliversLiveEventsInOrder() throws Exception {
        start();
        String session = json(send("/v1/status", null, true).body()).get("session_id").getAsString();
        bridge.event("state", json("{\"task_id\":0,\"text\":\"old\"}"));
        bridge.event("progress", json("{\"task_id\":1,\"text\":\"receipt\"}"));
        var response = client.send(request("/v1/events?token=" + TOKEN).header("Last-Event-ID", "1").build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("text/event-stream"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
             var body = response.body()) {
            var replay = executor.submit(() -> readFrame(reader)).get(5, TimeUnit.SECONDS);
            assertEquals("2", replay.get("id").getAsString());
            assertEquals(session, replay.getAsJsonObject("data").get("session_id").getAsString());
            assertEquals("progress", replay.getAsJsonObject("data").get("ev").getAsString());
            bridge.event("done", json("{\"task_id\":1,\"status\":\"completed\",\"text\":\"report\"}"));
            var live = executor.submit(() -> readFrame(reader)).get(5, TimeUnit.SECONDS);
            assertEquals("3", live.get("id").getAsString());
            assertEquals("done", live.get("event").getAsString());
            assertEquals("1.0", live.getAsJsonObject("data").get("contract_version").getAsString());
        }
    }

    @Test
    void aNewWorldBridgeChangesSessionAndStartsANewReplayRing() throws Exception {
        start();
        String first = json(send("/v1/status", null, true).body()).get("session_id").getAsString();
        bridge.event("state", json("{\"task_id\":0,\"text\":\"old world\"}"));
        bridge.stop();
        start();
        String second = json(send("/v1/status", null, true).body()).get("session_id").getAsString();
        assertNotEquals(first, second);
        bridge.event("state", json("{\"task_id\":0,\"text\":\"new world\"}"));
        var response = client.send(request("/v1/events").header("Authorization", "Bearer " + TOKEN).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
             var body = response.body()) {
            var frame = executor.submit(() -> readFrame(reader)).get(5, TimeUnit.SECONDS);
            assertEquals("1", frame.get("id").getAsString());
            assertEquals(second, frame.getAsJsonObject("data").get("session_id").getAsString());
            assertEquals("new world", frame.getAsJsonObject("data").get("text").getAsString());
        }
    }

    @Test
    void concurrentPublishersKeepReplayAndLiveFrameIdsOrdered() throws Exception {
        start();
        bridge.event("state", json("{\"task_id\":0,\"text\":\"seed\"}"));
        var response = client.send(request("/v1/events").header("Authorization", "Bearer " + TOKEN).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
             var body = response.body()) {
            var read = executor.submit(() -> {
                var ids = new ArrayList<Long>();
                for (int i = 0; i < 21; i++) ids.add(readFrame(reader).get("id").getAsLong());
                return ids;
            });
            Runnable publish = () -> {
                for (int i = 0; i < 10; i++)
                    bridge.event("state", json("{\"task_id\":0,\"text\":\"live\"}"));
            };
            var first = executor.submit(publish);
            var second = executor.submit(publish);
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            var ids = read.get(5, TimeUnit.SECONDS);
            for (int i = 0; i < ids.size(); i++) assertEquals(i + 1L, ids.get(i).longValue());
        }
    }

    @Test
    void sseReplayCannotRecoverEventsEvictedFromThe200ItemRing() throws Exception {
        start();
        for (int i = 0; i < 201; i++)
            bridge.event("state", json("{\"task_id\":0,\"text\":\"public\"}"));
        var response = client.send(request("/v1/events").header("Authorization", "Bearer " + TOKEN)
                .header("Last-Event-ID", "0").build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
             var body = response.body()) {
            var ids = executor.submit(() -> {
                var result = new ArrayList<Long>();
                for (int i = 0; i < 200; i++) result.add(readFrame(reader).get("id").getAsLong());
                return result;
            }).get(5, TimeUnit.SECONDS);
            assertEquals(2L, ids.getFirst().longValue());
            assertEquals(201L, ids.getLast().longValue());
        }
    }

    private static JsonObject readFrame(BufferedReader reader) throws Exception {
        JsonObject frame = new JsonObject();
        for (String line; (line = reader.readLine()) != null;) {
            if (line.isEmpty() && frame.has("data")) return frame;
            if (line.startsWith("id: ")) frame.addProperty("id", line.substring(4));
            if (line.startsWith("event: ")) frame.addProperty("event", line.substring(7));
            if (line.startsWith("data: ")) frame.add("data", json(line.substring(6)));
        }
        throw new IllegalStateException("SSE ended before a complete frame");
    }
}
