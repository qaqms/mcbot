package com.neko.mcbot.agentcore.loop;

import com.neko.mcbot.agentcore.llm.AssistantTurn;
import com.neko.mcbot.agentcore.llm.ChatEngine;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.prompt.PromptBuilder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ContextControlsTest {
    private static AssistantTurn text(String value) {
        return new AssistantTurn(value, List.of(), 0, 0, -1, "stop");
    }
    private static AgentLoop loop(List<CompletableFuture<AssistantTurn>> responses) {
        ChatEngine engine = (s, h, t) -> {
            var future = new CompletableFuture<AssistantTurn>(); responses.add(future); return future;
        };
        return new AgentLoop(engine, List.of(), (n, a) -> fail("no game tools"),
                AgentLoop.Config.defaults(), new AgentLoop.Listener() {}, () -> "SYS", 6000);
    }
    private static void history(AgentLoop loop) {
        for (int i = 0; i < 16; i++) loop.conversation().add(new Msg.User("history ".repeat(200)));
    }
    @Test void manualSummaryWaitsBeforeStartingANewDirective() {
        var responses = new ArrayList<CompletableFuture<AssistantTurn>>();
        var loop = loop(responses); history(loop);
        var compact = loop.compactNow();
        loop.submit(7, "next");
        assertEquals(1, responses.size());
        assertEquals(1, loop.queuedTasks());
        assertFalse(compact.isDone());
        responses.getFirst().complete(text("saved summary"));
        assertTrue(compact.join());
        assertEquals("saved summary", loop.conversation().summary());
        assertEquals(7, loop.currentTaskId());
        assertEquals(2, responses.size());
        loop.close();
    }
    @Test void clearingDuringSummaryCancelsItAndCannotRestoreOldHistory() {
        var responses = new ArrayList<CompletableFuture<AssistantTurn>>();
        var loop = loop(responses); history(loop);
        var compact = loop.compactNow();
        loop.submit(8, "queued");
        loop.clearContext();
        assertTrue(responses.getFirst().isCancelled());
        assertFalse(compact.join());
        assertTrue(loop.conversation().history().isEmpty());
        assertEquals(0, loop.conversation().contextTokens());
        assertFalse(loop.busy());
        loop.submit(9, "fresh");
        assertEquals(List.of(new Msg.User("fresh")), loop.conversation().history());
        loop.close();
    }
    @Test void manualSummaryRefusesToRewriteAnActiveToolOrModelTurn() {
        var responses = new ArrayList<CompletableFuture<AssistantTurn>>();
        var loop = loop(responses); loop.submit(1, "active");
        assertFalse(loop.compactNow().join());
        assertEquals(1, responses.size());
        loop.close();
    }
    @Test void automaticToggleDoesNotPreventManualCompactionOrLoseTheRecentTail() {
        var responses = new ArrayList<CompletableFuture<AssistantTurn>>();
        var loop = loop(responses); history(loop);
        var last = loop.conversation().history().getLast();
        loop.configureContext(3000, 900, false);
        assertFalse(loop.conversation().needsCompaction());
        var compact = loop.compactNow();
        responses.getFirst().complete(text("summary"));
        assertTrue(compact.join());
        assertEquals(last, loop.conversation().history().getLast());
        loop.close();
    }
    @Test void personaInvalidationAfterACleanBoundaryStillWaitsForTheNextDirective() {
        String[] source = {"old"};
        var prompt = new PromptBuilder(() -> source[0]);
        prompt.onDirectiveBoundary();
        source[0] = "new"; prompt.reloadSkills();
        assertEquals("old", prompt.get(), "mid-task save cannot rewrite the prefix");
        prompt.onDirectiveBoundary();
        assertEquals("new", prompt.get());
    }
}
