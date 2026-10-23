package com.neko.mcbot.agentcore.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ModelConnectionTestTest {
    private static AssistantTurn reply(String text, List<ToolCall> calls) {
        return new AssistantTurn(text, calls, 0, 0, -1, "stop");
    }

    @Test
    void aToolReceiptIsPairedBeforeTheSecondRequest() {
        AtomicInteger requests = new AtomicInteger();
        ChatEngine engine = (system, history, tools) -> {
            assertEquals("connection_probe", tools.getFirst().name());
            if (requests.getAndIncrement() == 0) {
                return CompletableFuture.completedFuture(reply("", List.of(
                        new ToolCall("test-call", "connection_probe", "{}"))));
            }
            assertEquals(new Msg.Tool("test-call", "connection_probe", "OK", true), history.getLast());
            return CompletableFuture.completedFuture(reply("OK", List.of()));
        };
        assertTrue(ModelConnectionTest.run(engine).join());
        assertEquals(2, requests.get());
    }

    @Test
    void ordinaryTextCannotBeMisreportedAsToolCompatibility() {
        ChatEngine engine = (s, h, t) -> CompletableFuture.completedFuture(reply("OK", List.of()));
        assertFalse(ModelConnectionTest.run(engine).join());
    }

    @Test
    void hallucinatedGameToolsAreNeverExecutedByTheProbe() {
        ChatEngine engine = (s, h, t) -> CompletableFuture.completedFuture(reply("", List.of(
                new ToolCall("call", "break_block", "{}"))));
        assertFalse(ModelConnectionTest.run(engine).join());
    }

    @Test
    void failureOfTheToolReplyRoundTripRemainsAFailure() {
        AtomicInteger requests = new AtomicInteger();
        ChatEngine engine = (s, h, t) -> requests.getAndIncrement() == 0
                ? CompletableFuture.completedFuture(reply("", List.of(
                        new ToolCall("call", "connection_probe", "{}"))))
                : CompletableFuture.failedFuture(new LlmFailure(LlmFailure.Kind.HTTP, 401));
        assertThrows(CompletionException.class, () -> ModelConnectionTest.run(engine).join());
    }

    @Test
    void taskSchemasAreCheckedInBothRequestsWithoutExecutingAnyTaskTool() {
        var tool = ToolSpec.of("break_block", "synthetic tool", "{\"type\":\"object\"}");
        AtomicInteger requests = new AtomicInteger();
        ChatEngine engine = (system, history, tools) -> {
            assertEquals(List.of("connection_probe", "break_block"), tools.stream().map(ToolSpec::name).toList());
            assertEquals(tool, tools.get(1));
            return requests.getAndIncrement() == 0
                    ? CompletableFuture.completedFuture(reply("", List.of(
                    new ToolCall("probe", "connection_probe", "{}"))))
                    : CompletableFuture.completedFuture(reply("OK", List.of()));
        };
        assertTrue(ModelConnectionTest.run(engine, List.of(tool)).join());
        assertEquals(2, requests.get());
        requests.set(0);
        ChatEngine unsafe = (system, history, tools) -> {
            requests.incrementAndGet();
            return CompletableFuture.completedFuture(reply("", List.of(new ToolCall("game", "break_block", "{}"))));
        };
        assertFalse(ModelConnectionTest.run(unsafe, List.of(tool)).join());
        assertEquals(1, requests.get(), "a game tool must not receive a fabricated receipt");
    }

    @Test
    void rejectedTaskSchemasCannotPassTheConnectionCheck() {
        AtomicInteger requests = new AtomicInteger();
        ChatEngine rejecting = (system, history, tools) -> {
            requests.incrementAndGet();
            return CompletableFuture.failedFuture(new LlmFailure(LlmFailure.Kind.HTTP, 400));
        };
        assertThrows(CompletionException.class, () -> ModelConnectionTest.run(rejecting,
                List.of(ToolSpec.of("wait", "synthetic tool", "{\"type\":\"object\"}"))).join());
        assertEquals(1, requests.get());
    }
}
