package com.neko.mcbot.agentcore.llm;

import com.neko.mcbot.agentcore.provider.OpenAiCompatProvider;
import com.neko.mcbot.agentcore.provider.StreamingTurnReader;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class StreamDeadlineTest {
    @Test void trafficExtendsIdleButCannotExtendTotalDeadline() {
        var clock = new AtomicLong();
        var deadline = new StreamDeadline(Duration.ofSeconds(180), clock::get);
        clock.set(Duration.ofSeconds(89).toNanos());
        assertNull(deadline.expired());
        deadline.received();
        clock.set(Duration.ofSeconds(178).toNanos());
        assertNull(deadline.expired());
        deadline.received();
        clock.set(Duration.ofSeconds(180).toNanos());
        assertEquals(LlmFailure.Kind.DEADLINE, deadline.expired());
        assertEquals(2000, deadline.idleMillis());
        assertEquals(89000, deadline.longestGapMillis());
    }

    @Test void stalledBodyAndHeadersHaveAnIdleDeadline() {
        var clock = new AtomicLong();
        var deadline = new StreamDeadline(Duration.ofSeconds(180), clock::get);
        clock.set(Duration.ofSeconds(90).toNanos());
        assertEquals(LlmFailure.Kind.STREAM_IDLE, deadline.expired());
        deadline.received();
        assertNull(deadline.expired());
    }

    @Test void expirationFinishesBodyOnceAndRejectsLateBytesAndSubscription() {
        var calls = new AtomicInteger();
        var provider = new OpenAiCompatProvider("test", "http://127.0.0.1", "synthetic", "offline");
        var body = new LlmClient.SseBodySubscriber(provider, new StreamingTurnReader(List.of(), true),
                new TurnSink() {
                    @Override public void onTextDelta(String delta) { calls.incrementAndGet(); }
                }, new TurnTimings());
        body.statusCode(200);
        assertTrue(body.expire(LlmFailure.Kind.DEADLINE));
        assertFalse(body.expire(LlmFailure.Kind.STREAM_IDLE));
        assertTrue(body.getBody().toCompletableFuture().isDone());
        var cancelled = new AtomicInteger();
        body.onSubscribe(new Flow.Subscription() {
            @Override public void request(long n) { fail("expired subscriber"); }
            @Override public void cancel() { cancelled.incrementAndGet(); }
        });
        body.onNext(List.of(ByteBuffer.wrap(
                "data: {\"choices\":[{\"delta\":{\"content\":\"late\"}}]}\n\n".getBytes(StandardCharsets.UTF_8))));
        body.onComplete();
        assertEquals(0, calls.get());
        assertEquals(1, cancelled.get());
        assertEquals(LlmFailure.Kind.DEADLINE, ((LlmFailure) body.failure()).kind());
    }

    @Test void watchdogTerminatesAnOpenSseBodyWithoutRetryingOrReportingSuccess() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = java.util.concurrent.Executors.newCachedThreadPool();
        server.setExecutor(executor);
        var first = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var clock = new AtomicLong();
        var attempts = new AtomicInteger();
        var completions = new AtomicInteger();
        server.createContext("/", exchange -> {
            attempts.incrementAndGet();
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"waiting\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                release.await(5, TimeUnit.SECONDS);
            } catch (java.io.IOException closed) { }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        server.start();
        try {
            var client = new LlmClient(new OpenAiCompatProvider("test",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "synthetic", "offline"),
                    Duration.ofSeconds(180), Duration.ofSeconds(10), clock::get);
            var result = client.chat("sys", List.of(), List.of(), new TurnSink() {
                @Override public void onTextDelta(String delta) { first.countDown(); }
                @Override public void onComplete(AssistantTurn turn, Throwable error) {
                    assertNull(turn);
                    assertInstanceOf(LlmFailure.class, error);
                    completions.incrementAndGet();
                }
            }, true);
            assertTrue(first.await(5, TimeUnit.SECONDS));
            clock.set(Duration.ofSeconds(180).toNanos());
            var error = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> result.get(5, TimeUnit.SECONDS));
            assertEquals(LlmFailure.Kind.DEADLINE, ((LlmFailure) error.getCause()).kind());
            assertEquals(1, attempts.get());
            assertEquals(1, completions.get());
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
