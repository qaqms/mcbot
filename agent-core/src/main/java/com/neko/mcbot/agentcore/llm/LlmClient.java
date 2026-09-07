package com.neko.mcbot.agentcore.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.provider.ChatProvider;
import com.neko.mcbot.agentcore.provider.TurnBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 零第三方依赖的 SSE 流式客户端：JDK HttpClient + Gson。
 * 异步执行，不阻塞任何游戏线程；失败以异常完成 future（调用方决定重试策略）。
 */
public final class LlmClient implements ChatEngine {

    private final ChatProvider provider;
    private final HttpClient http;
    private final Duration timeout;

    public LlmClient(ChatProvider provider, Duration timeout) {
        this.provider = provider;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public CompletableFuture<AssistantTurn> chat(String systemPrompt, List<Msg> convo, List<ToolSpec> tools) {
        TurnBuilder builder = new TurnBuilder();
        JsonObject body = provider.buildBody(systemPrompt, convo, tools);

        HttpRequest request = HttpRequest.newBuilder(URI.create(provider.endpoint()))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .headers(flatten(provider.authHeaders()))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .thenCompose(resp -> {
                    if (resp.statusCode() != 200) {
                        StringBuilder err = new StringBuilder();
                        resp.body().limit(50).forEach(l -> err.append(l).append(' '));
                        return CompletableFuture.failedFuture(new IOException(
                                provider.name() + " HTTP " + resp.statusCode() + ": " + err));
                    }
                    resp.body().forEach(line -> onLine(line, builder));
                    return CompletableFuture.completedFuture(builder.build());
                });
    }

    private void onLine(String line, TurnBuilder builder) {
        String s = line.trim();
        if (s.isEmpty() || !s.startsWith("data:")) {
            return; // 注释行/事件名/心跳直接忽略
        }
        String payload = s.substring(5).trim();
        if (provider.isTerminalData(payload)) {
            return;
        }
        try {
            com.google.gson.JsonElement el = JsonParser.parseString(payload);
            if (el.isJsonObject()) {
                provider.acceptChunk(el.getAsJsonObject(), builder);
            }
        } catch (RuntimeException ignored) {
            // 个别端点会混入非 JSON 行，跳过比中断安全
        }
    }

    private static String[] flatten(java.util.Map<String, String> headers) {
        String[] out = new String[headers.size() * 2];
        int i = 0;
        for (var e : headers.entrySet()) {
            out[i++] = e.getKey();
            out[i++] = e.getValue();
        }
        return out;
    }
}
