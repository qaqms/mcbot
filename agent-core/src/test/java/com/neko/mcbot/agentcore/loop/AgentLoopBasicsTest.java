package com.neko.mcbot.agentcore.loop;

import com.neko.mcbot.agentcore.llm.AssistantTurn;
import com.neko.mcbot.agentcore.llm.ChatEngine;
import com.neko.mcbot.agentcore.llm.LlmFailure;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.agentcore.llm.ToolSpec;
import com.neko.mcbot.agentcore.llm.TurnSink;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopBasicsTest {

    @Test
    void invalidModelFutureFailsCleanlyAndDoesNotWedgeTheQueue() {
        var calls = new AtomicInteger();
        var replies = new ArrayList<String>();
        var loop = loop((s, h, t) -> calls.getAndIncrement() == 0 ? null
                : CompletableFuture.completedFuture(text("hello")), (n, a) -> fail("no tools"), replies);
        assertDoesNotThrow(() -> loop.submit("first"));
        loop.submit("second");
        assertEquals(2, replies.size());
        assertEquals("hello", replies.get(1));
    }

    @Test
    void modelErrorsDoNotEchoCredentialsOrRequestDetails() {
        var replies = new ArrayList<String>();
        var loop = loop((s, h, t) -> CompletableFuture.failedFuture(
                new IllegalStateException("Authorization=private-test-credential")),
                (n, a) -> fail("no tools"), replies);
        loop.submit("first");
        assertEquals(1, replies.size());
        assertFalse(replies.getFirst().contains("private-test-credential"));
        assertFalse(replies.getFirst().contains("Authorization"));
    }

    @Test
    void knownHttpFailureRetainsUsefulStatusWithoutChangingTaskTermination() {
        var replies = new ArrayList<String>();
        var loop = loop((s, h, t) -> CompletableFuture.failedFuture(
                new LlmFailure(LlmFailure.Kind.HTTP, 401)), (n, a) -> fail("no tools"), replies);
        loop.submit("first");
        assertTrue(replies.getFirst().contains("HTTP 401"));
        assertTrue(replies.getFirst().contains("密钥"));
        assertEquals(0, loop.currentTaskId());
    }

    private static AssistantTurn text(String text) {
        return new AssistantTurn(text, List.of(), 0, 0, -1, "stop");
    }

    private static AssistantTurn tools(String... names) {
        var calls = new ArrayList<ToolCall>();
        for (int i = 0; i < names.length; i++) {
            calls.add(new ToolCall("c" + i, names[i], "{}"));
        }
        return new AssistantTurn("", calls, 0, 0, -1, "tool_calls");
    }

    private static AgentLoop loop(ChatEngine engine, ToolExecutor executor, List<String> replies) {
        return new AgentLoop(engine, List.of(), executor, AgentLoop.Config.defaults(),
                new AgentLoop.Listener() {
                    @Override
                    public void onReply(String text) {
                        replies.add(text);
                    }
                }, () -> "SYS", 1_000_000);
    }

    private static List<Msg.Tool> receipts(AgentLoop loop) {
        return loop.conversation().history().stream()
                .filter(Msg.Tool.class::isInstance).map(Msg.Tool.class::cast).toList();
    }

    @Test
    void queuedChatKeepsHistoryAndWaitsForThePreviousReply() {
        var responses = new ArrayList<CompletableFuture<AssistantTurn>>();
        var requests = new ArrayList<List<Msg>>();
        ChatEngine engine = (sys, history, specs) -> {
            requests.add(List.copyOf(history));
            var response = new CompletableFuture<AssistantTurn>();
            responses.add(response);
            return response;
        };
        var replies = new ArrayList<String>();
        var loop = loop(engine, (n, a) -> fail("chat must not execute tools"), replies);

        loop.submit("first");
        loop.submit("second");
        assertEquals(1, requests.size());
        responses.get(0).complete(text("first reply"));
        assertEquals(2, requests.size());
        assertEquals(List.of(new Msg.User("first"), new Msg.Assistant("first reply", List.of()),
                new Msg.User("second")), requests.get(1));
        responses.get(1).complete(text("second reply"));
        assertEquals(List.of("first reply", "second reply"), replies);
    }

    @Test
    void toolsAreDispatchedOnlyAfterThePreviousReceipt() {
        var calls = new AtomicInteger();
        ChatEngine engine = (s, h, t) -> CompletableFuture.completedFuture(
                calls.getAndIncrement() == 0 ? tools("dig", "move") : text("done"));
        var first = new CompletableFuture<ToolOutcome>();
        var second = new CompletableFuture<ToolOutcome>();
        var executed = new ArrayList<String>();
        var replies = new ArrayList<String>();
        var loop = loop(engine, (n, a) -> {
            executed.add(n);
            return n.equals("dig") ? first : second;
        }, replies);

        loop.submit("work");
        assertEquals(List.of("dig"), executed, "the second action must not collide with the first");
        first.complete(new ToolOutcome(true, "dug"));
        assertEquals(List.of("dig", "move"), executed);
        assertTrue(replies.isEmpty());
        second.complete(new ToolOutcome(true, "moved"));
        assertEquals(List.of("c0", "c1"), receipts(loop).stream().map(Msg.Tool::callId).toList());
        assertEquals(List.of("done"), replies);
    }

    @Test
    void synchronousToolFailureIsRecordedAndTheModelCanRecover() {
        assertToolFailureRecovers((n, a) -> {
            throw new IllegalStateException("broken tool");
        });
    }

    @Test
    void asynchronousToolFailureIsRecordedAndTheModelCanRecover() {
        assertToolFailureRecovers((n, a) ->
                CompletableFuture.failedFuture(new IllegalStateException("broken tool")));
    }

    private static void assertToolFailureRecovers(ToolExecutor executor) {
        var calls = new AtomicInteger();
        ChatEngine engine = (s, h, t) -> {
            if (calls.getAndIncrement() == 0) {
                return CompletableFuture.completedFuture(tools("dig"));
            }
            assertTrue(h.get(h.size() - 1) instanceof Msg.Tool, "failure must answer the tool call");
            return CompletableFuture.completedFuture(text("recovered"));
        };
        var replies = new ArrayList<String>();
        var loop = loop(engine, executor, replies);

        loop.submit("work");
        assertEquals(List.of("recovered"), replies);
        assertEquals(1, receipts(loop).size());
        assertFalse(receipts(loop).get(0).ok());
        assertTrue(receipts(loop).get(0).content().startsWith("INTERNAL:"));
    }

    @Test
    void synchronousModelFailureDoesNotWedgeTheNextChat() {
        var calls = new AtomicInteger();
        ChatEngine engine = (s, h, t) -> {
            if (calls.getAndIncrement() == 0) {
                throw new IllegalArgumentException("invalid endpoint");
            }
            return CompletableFuture.completedFuture(text("hello"));
        };
        var replies = new ArrayList<String>();
        var loop = loop(engine, (n, a) -> fail("no tools"), replies);

        assertDoesNotThrow(() -> loop.submit("first"));
        loop.submit("second");
        assertEquals(2, replies.size());
        assertTrue(replies.get(0).contains("模型调用失败"));
        assertEquals("hello", replies.get(1));
    }

    @Test
    void asynchronousModelFailureDrainsTheQueuedChat() {
        var first = new CompletableFuture<AssistantTurn>();
        var calls = new AtomicInteger();
        ChatEngine engine = (s, h, t) -> calls.getAndIncrement() == 0
                ? first : CompletableFuture.completedFuture(text("hello"));
        var replies = new ArrayList<String>();
        var loop = loop(engine, (n, a) -> fail("no tools"), replies);

        loop.submit("first");
        loop.submit("second");
        first.completeExceptionally(new IllegalStateException("endpoint unavailable"));
        assertEquals(2, calls.get());
        assertEquals("hello", replies.get(1));
    }

    @Test
    void cancelDoesNotStartTheRemainingToolsAndKeepsEveryCallPaired() {
        var first = new CompletableFuture<ToolOutcome>();
        var calls = new AtomicInteger();
        ChatEngine engine = (s, h, t) -> CompletableFuture.completedFuture(
                calls.getAndIncrement() == 0 ? tools("dig", "move") : text("hello"));
        var executed = new ArrayList<String>();
        var replies = new ArrayList<String>();
        var loop = loop(engine, (n, a) -> {
            executed.add(n);
            return first;
        }, replies);

        loop.submit("work");
        loop.cancelDirective();
        first.complete(new ToolOutcome(false, "CANCELLED:stopped"));
        assertEquals(List.of("dig"), executed);
        assertEquals(2, receipts(loop).size(), "even the unstarted call needs a receipt");
        assertTrue(receipts(loop).get(1).content().startsWith("CANCELLED:"));
        assertEquals(1, calls.get(), "cancel must not ask the model again");
        loop.submit("new chat");
        assertEquals("hello", replies.get(replies.size() - 1));
    }

    @Test
    void cancelWhileWaitingForAcceptanceDoesNotParkForever() {
        var acknowledgement = new CompletableFuture<ToolOutcome>();
        var calls = new AtomicInteger();
        ChatEngine engine = (s, h, t) -> CompletableFuture.completedFuture(
                calls.getAndIncrement() == 0 ? tools("dig") : text("hello"));
        var replies = new ArrayList<String>();
        var loop = loop(engine, (n, a) -> acknowledgement, replies);

        loop.submit("work");
        loop.cancelDirective();
        acknowledgement.complete(ToolOutcome.accepted("j1", "ACCEPTED:started"));
        assertEquals(1, replies.size(), "cancel must finish even when the ACK arrives after it");
        assertTrue(receipts(loop).get(0).content().startsWith("CANCELLED:"));
        loop.submit("new chat");
        assertEquals("hello", replies.get(replies.size() - 1));
    }

    @Test
    void cancelDuringStreamingPreventsLaterEarlyDispatch() {
        var response = new CompletableFuture<AssistantTurn>();
        var sinks = new ArrayList<TurnSink>();
        ChatEngine engine = new ChatEngine() {
            @Override
            public CompletableFuture<AssistantTurn> chat(String s, List<Msg> h, List<ToolSpec> t) {
                return response;
            }

            @Override
            public CompletableFuture<AssistantTurn> chat(String s, List<Msg> h, List<ToolSpec> t,
                                                         TurnSink sink, boolean accumulate) {
                sinks.add(sink);
                return response;
            }
        };
        var executed = new ArrayList<String>();
        var replies = new ArrayList<String>();
        var loop = loop(engine, (n, a) -> {
            executed.add(n);
            return CompletableFuture.completedFuture(new ToolOutcome(true, "done"));
        }, replies);

        loop.submit("work");
        loop.cancelDirective();
        sinks.get(0).onToolCallReady(0, new ToolCall("c0", "dig", "{}"));
        response.complete(tools("dig"));
        assertTrue(executed.isEmpty(), "the stop flag must also guard streaming dispatch");
        assertTrue(replies.get(0).contains("停手"));
    }
}
