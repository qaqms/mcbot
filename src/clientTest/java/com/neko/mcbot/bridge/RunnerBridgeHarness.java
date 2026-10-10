package com.neko.mcbot.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agent.AgentRunner;
import com.neko.mcbot.agentcore.bridge.BridgeBackend;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Real HTTP adapter and runner backend, with no player token or Minecraft access. */
public final class RunnerBridgeHarness implements AutoCloseable {
    private static final String TOKEN = "local-lifecycle-only";
    private final BridgeBackend backend;
    private final BridgeHttp bridge;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private boolean closed;

    public RunnerBridgeHarness(AgentRunner runner, Supplier<AgentRunner> current, Executor client)
            throws java.io.IOException {
        backend = new BridgeHttp.RunnerBackend(runner, current, client, () -> current.get() != null);
        bridge = new BridgeHttp(backend, TOKEN, 0);
        BridgeEvents.attach(bridge);
    }

    public BridgeBackend backend() { return backend; }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + bridge.port() + path))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + TOKEN);
    }

    public HttpResponse<String> send(String path, String body) throws Exception {
        var request = request(path);
        if (body != null) request.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    public JsonObject status() throws Exception { return json(send("/v1/status", null).body()); }

    public String questionId() throws Exception {
        return framesUntil(frame -> "question".equals(frame.getAsJsonObject("data").get("ev").getAsString()))
                .getLast().getAsJsonObject("data").get("question_id").getAsString();
    }

    public List<JsonObject> framesThroughTask(long task) throws Exception {
        return framesUntil(frame -> {
            JsonObject data = frame.getAsJsonObject("data");
            return "done".equals(data.get("ev").getAsString()) && data.get("task_id").getAsLong() == task;
        });
    }

    private List<JsonObject> framesUntil(java.util.function.Predicate<JsonObject> finished) throws Exception {
        var response = http.send(request("/v1/events").build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var body = response.body()) {
            // Close the stream before joining the reader task if the bounded wait expires.
            var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
            return executor.submit(() -> {
                var frames = new ArrayList<JsonObject>();
                var frame = new JsonObject();
                for (String line; (line = reader.readLine()) != null;) {
                    if (line.isEmpty() && frame.has("data")) {
                        frames.add(frame);
                        if (finished.test(frame)) return List.copyOf(frames);
                        frame = new JsonObject();
                    }
                    if (line.startsWith("id: ")) frame.addProperty("id", line.substring(4));
                    if (line.startsWith("data: ")) frame.add("data", json(line.substring(6)));
                }
                throw new IllegalStateException("SSE ended without a complete frame");
            }).get(5, TimeUnit.SECONDS);
        }
    }

    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        bridge.stop();
        http.close();
    }
}
