package com.neko.mcbot.agentcore.llm;

import com.neko.mcbot.agentcore.loop.AgentLoop;
import com.neko.mcbot.agentcore.loop.ToolExecutor;
import com.neko.mcbot.agentcore.provider.OpenAiCompatProvider;
import com.neko.mcbot.agentcore.provider.StreamingTurnReader;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LlmStreamDispatchTest {
    @Test
    void realSseParserStartsNamedToolBeforeEofAndLoopRecordsItOnce() {
        var engine = new StreamEngine();
        var invocations = new AtomicInteger();
        var replies = new java.util.ArrayList<String>();
        var specs = List.of(
                ToolSpec.of("workflow", "", "{\"required\":[\"steps\"]}"),
                ToolSpec.of("status", "", "{}"),
                ToolSpec.of("move_to", "", "{\"required\":[\"x\",\"y\",\"z\"]}"));
        var loop = new AgentLoop(engine, specs, (name, args) -> {
            assertEquals("move_to", name);
            assertEquals("{\"x\":1,\"y\":2,\"z\":3}", args);
            invocations.incrementAndGet();
            return CompletableFuture.completedFuture(new ToolExecutor.ToolOutcome(true, "arrived"));
        }, AgentLoop.Config.defaults(), new AgentLoop.Listener() {
            @Override public void onReply(String text) { replies.add(text); }
        }, () -> "offline", 100_000);

        loop.submit("move");
        engine.body.onNext(List.of(frame("""
                {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c0","function":{
                "name":"move_to","arguments":"{\\"x\\":1,"}}]}}]}
                """)));
        assertEquals(0, invocations.get());
        engine.body.onNext(List.of(frame("""
                {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{
                "arguments":"\\"y\\":2,\\"z\\":3}"}}]}}]}
                """)));
        assertEquals(1, invocations.get(), "execution must start while SSE is still open");
        assertFalse(engine.body.getBody().toCompletableFuture().isDone());
        assertEquals(1, loop.conversation().history().size(), "a partial turn is not protocol history");

        engine.body.onNext(List.of(frame("""
                {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}
                """)));
        engine.body.onComplete();
        assertEquals(1, invocations.get());
        assertEquals(2, engine.requests);
        assertEquals(List.of("finished"), replies);
        var history = loop.conversation().history();
        assertEquals(4, history.size());
        var call = ((Msg.Assistant) history.get(1)).toolCalls().getFirst();
        var result = (Msg.Tool) history.get(2);
        assertEquals(call.id(), result.callId());
        assertEquals(call.name(), result.name());
        assertFalse(loop.busy());
        loop.close();
    }

    private static ByteBuffer frame(String json) {
        String compact = com.google.gson.JsonParser.parseString(json).toString();
        return ByteBuffer.wrap(("data: " + compact + "\n\n").getBytes(StandardCharsets.UTF_8));
    }

    private static final class StreamEngine implements ChatEngine {
        private LlmClient.SseBodySubscriber body;
        private int requests;

        @Override public CompletableFuture<AssistantTurn> chat(
                String system, List<Msg> history, List<ToolSpec> tools) {
            return chat(system, history, tools, null, true);
        }

        @Override public CompletableFuture<AssistantTurn> chat(
                String system, List<Msg> history, List<ToolSpec> tools, TurnSink sink, boolean accumulate) {
            if (++requests > 1) return CompletableFuture.completedFuture(
                    new AssistantTurn("finished", List.of(), 0, 0, -1, "stop"));
            var reader = new StreamingTurnReader(tools, accumulate);
            var provider = new OpenAiCompatProvider("test", "http://127.0.0.1", "synthetic", "offline");
            body = new LlmClient.SseBodySubscriber(provider, reader, sink, new TurnTimings());
            body.statusCode(200);
            body.onSubscribe(new Flow.Subscription() {
                @Override public void request(long count) { }
                @Override public void cancel() { }
            });
            return body.getBody().toCompletableFuture().thenApply(ignored -> reader.builder().build());
        }
    }
}
