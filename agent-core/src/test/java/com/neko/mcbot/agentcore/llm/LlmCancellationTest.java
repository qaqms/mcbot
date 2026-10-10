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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class LlmCancellationTest {
    private static final byte[] FRAME = ("data: {\"choices\":[{\"delta\":{\"content\":\"first\"}}]}\n\n")
            .getBytes(StandardCharsets.UTF_8);

    @Test void cancelSubscriberStopsSubscriptionAndIgnoresRemainingFrames() {
        var calls = new AtomicInteger();
        var canceled = new AtomicBoolean();
        var provider = new OpenAiCompatProvider("test", "http://127.0.0.1", "synthetic", "offline");
        var subscriber = new LlmClient.SseBodySubscriber(provider, new StreamingTurnReader(List.of(), true),
                new TurnSink() {
                    @Override public void onTextDelta(String delta) { calls.incrementAndGet(); }
                }, new TurnTimings());
        subscriber.statusCode(200);
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override public void request(long n) { }
            @Override public void cancel() { canceled.set(true); }
        });
        subscriber.onNext(List.of(ByteBuffer.wrap(FRAME)));
        assertEquals(1, calls.get());
        subscriber.cancel();
        subscriber.onNext(List.of(ByteBuffer.wrap(FRAME)));
        subscriber.onComplete();
        assertTrue(canceled.get());
        assertTrue(subscriber.getBody().toCompletableFuture().isCancelled());
        assertEquals(1, calls.get());
    }

    @Test void cancelBeforeSubscriptionCancelsLateSubscriptionWithoutRequestingBytes() {
        var provider = new OpenAiCompatProvider("test", "http://127.0.0.1", "synthetic", "offline");
        var subscriber = new LlmClient.SseBodySubscriber(provider, new StreamingTurnReader(List.of(), true),
                null, new TurnTimings());
        subscriber.cancel();
        var canceled = new AtomicBoolean();
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override public void request(long n) { fail("cancelled body cannot request data"); }
            @Override public void cancel() { canceled.set(true); }
        });
        assertTrue(canceled.get());
    }

    @Test void cancelBeforeHeadersPreventsHtmlFallbackRequest() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = java.util.concurrent.Executors.newCachedThreadPool();
        server.setExecutor(executor);
        var arrived = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var served = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        var completed = new AtomicInteger();
        server.createContext("/", exchange -> {
            attempts.incrementAndGet();
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                arrived.countDown();
                release.await(5, TimeUnit.SECONDS);
                exchange.getResponseHeaders().add("Content-Type", "text/html");
                byte[] html = "<html>offline</html>".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, html.length);
                exchange.getResponseBody().write(html);
            } catch (java.io.IOException closed) { }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { served.countDown(); }
        });
        server.start();
        try {
            var provider = new OpenAiCompatProvider("test", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "synthetic", "offline");
            var future = new LlmClient(provider, Duration.ofSeconds(10)).chat("sys", List.of(), List.of(),
                    new TurnSink() {
                        @Override public void onComplete(AssistantTurn turn, Throwable error) { completed.incrementAndGet(); }
                    }, true);
            assertTrue(arrived.await(5, TimeUnit.SECONDS));
            future.cancel(true);
            release.countDown();
            assertTrue(served.await(5, TimeUnit.SECONDS));
            assertTrue(future.isCancelled());
            assertEquals(1, attempts.get());
            assertEquals(0, completed.get());
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void cancelLiveLoopbackSseClosesTransportAndDoesNotEmitCompletion() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = java.util.concurrent.Executors.newCachedThreadPool();
        server.setExecutor(executor);
        var first = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        var disconnected = new CountDownLatch(1);
        var completed = new AtomicInteger();
        server.createContext("/chat/completions", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                var out = exchange.getResponseBody();
                out.write(FRAME);
                out.flush();
                if (!released.await(5, TimeUnit.SECONDS)) return;
                byte[] bytes = ("data: " + " ".repeat(65536) + "\n\n").getBytes(StandardCharsets.UTF_8);
                for (int i = 0; i < 128; i++) { out.write(bytes); out.flush(); }
            } catch (java.io.IOException closed) { disconnected.countDown(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var client = new LlmClient(new OpenAiCompatProvider("test", base, "synthetic", "offline"),
                    Duration.ofSeconds(10));
            var future = client.chat("sys", List.of(), List.of(), new TurnSink() {
                @Override public void onTextDelta(String delta) { first.countDown(); }
                @Override public void onComplete(AssistantTurn turn, Throwable error) { completed.incrementAndGet(); }
            }, true);
            assertTrue(first.await(5, TimeUnit.SECONDS));
            assertTrue(future.cancel(true));
            released.countDown();
            assertTrue(disconnected.await(5, TimeUnit.SECONDS), "server observes closed response socket");
            assertTrue(future.isCancelled());
            assertEquals(0, completed.get());
        } finally {
            released.countDown();
            server.stop(0);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
