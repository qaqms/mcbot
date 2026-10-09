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
}
