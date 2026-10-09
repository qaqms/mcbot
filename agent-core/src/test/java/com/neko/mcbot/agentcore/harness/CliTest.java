package com.neko.mcbot.agentcore.harness;

import com.neko.mcbot.agentcore.llm.AssistantTurn;
import com.neko.mcbot.agentcore.llm.ChatEngine;
import com.neko.mcbot.agentcore.llm.Msg;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CliTest {

    private record Request(List<Msg> history, CompletableFuture<AssistantTurn> response) {
    }

    private static AssistantTurn text(String text) {
        return new AssistantTurn(text, List.of(), 0, 0, -1, "stop");
    }

    @Test
    void everyInputWaitsForItsOwnReplyAndKeepsChatHistory() throws Exception {
        var requests = new LinkedBlockingQueue<Request>();
        ChatEngine engine = (s, h, t) -> {
            var response = new CompletableFuture<AssistantTurn>();
            requests.offer(new Request(List.copyOf(h), response));
            return response;
        };
        var output = new ByteArrayOutputStream();
        var session = CompletableFuture.runAsync(() -> {
            try {
                Cli.runSession(engine, new BufferedReader(new StringReader("first\nsecond\nexit\n")),
                        new PrintStream(output, true, StandardCharsets.UTF_8), Duration.ofSeconds(5));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        try {
            Request first = requests.poll(5, TimeUnit.SECONDS);
            assertNotNull(first);
            assertTrue(requests.isEmpty());
            first.response().complete(text("one"));
            Request second = requests.poll(5, TimeUnit.SECONDS);
            assertNotNull(second);
            assertFalse(session.isDone(), "the second input must not reuse the first turn's completion");
            assertEquals(List.of(new Msg.User("first"), new Msg.Assistant("one", List.of()),
                    new Msg.User("second")), second.history());
            second.response().complete(text("two"));
            session.get(5, TimeUnit.SECONDS);
            String transcript = output.toString(StandardCharsets.UTF_8);
            assertTrue(transcript.contains("bot> one"));
            assertTrue(transcript.contains("bot> two"));
            assertTrue(requests.isEmpty(), "exit must not reach the model");
        } finally {
            for (Request request : requests) {
                request.response().complete(text("cleanup"));
            }
            session.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void blankLinesAndExitDoNotCallTheModel() throws Exception {
        var output = new ByteArrayOutputStream();
        Cli.runSession((s, h, t) -> fail("no model request expected"),
                new BufferedReader(new StringReader("\n \nexit\n")),
                new PrintStream(output), Duration.ofSeconds(1));
    }

    @Test
    void timeoutEndsTheSessionInsteadOfMisassigningALateReply() throws Exception {
        var calls = new AtomicInteger();
        ChatEngine engine = (s, h, t) -> {
            calls.incrementAndGet();
            return new CompletableFuture<>();
        };
        var output = new ByteArrayOutputStream();
        Cli.runSession(engine, new BufferedReader(new StringReader("first\nsecond\n")),
                new PrintStream(output, true, StandardCharsets.UTF_8), Duration.ofMillis(20));
        assertEquals(1, calls.get());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("超时"));
    }
}
