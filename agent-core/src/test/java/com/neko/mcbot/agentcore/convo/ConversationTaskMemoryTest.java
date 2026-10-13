package com.neko.mcbot.agentcore.convo;

import com.neko.mcbot.agentcore.llm.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ConversationTaskMemoryTest {
    @Test void sameToolNameDoesNotEraseDifferentActionFacts() {
        var conversation = new Conversation(10000);
        conversation.add(new Msg.Tool("first", "break_block", "dug at coordinate-one", true));
        for (int i = 0; i < 13; i++) conversation.add(new Msg.User("event"));
        conversation.add(new Msg.Tool("second", "break_block", "dug at coordinate-two", true));
        conversation.onStepBoundary();
        assertEquals("dug at coordinate-one", ((Msg.Tool) conversation.outboundHistory().getFirst()).content());
    }

    @Test void blankOrToolBearingSummariesCannotReplaceHistory() {
        for (var turn : List.of(new AssistantTurn("", List.of(), 0, 0, -1, "stop"),
                new AssistantTurn("summary", List.of(new ToolCall("bad", "status", "{}")), 0, 0, -1, "tool_calls"))) {
            var c = new Conversation(1000);
            c.configure(1000, 100, true);
            c.add(new Msg.User("old".repeat(1500)));
            var before = c.history();
            c.compact((s, h, t) -> CompletableFuture.completedFuture(turn)).join();
            assertEquals(before, c.history());
        }
    }

    @Test void lateSummaryCannotReplaceClearedOrNewHistory() {
        var c = new Conversation(1000);
        c.configure(1000, 100, true);
        c.add(new Msg.User("old".repeat(1500)));
        var response = new CompletableFuture<AssistantTurn>();
        var pending = c.compact((s, h, t) -> response);
        c.clear();
        c.add(new Msg.User("new task"));
        response.complete(new AssistantTurn("stale", List.of(), 0, 0, -1, "stop"));
        pending.join();
        assertEquals(List.of(new Msg.User("new task")), c.history());
    }

    @Test void existingSummaryIsProvidedOnceOutsideTheNewEventSpan() {
        var c = new Conversation(1000);
        c.configure(1000, 100, true);
        c.add(new Msg.User("old".repeat(1500)));
        c.compact((s, h, t) -> CompletableFuture.completedFuture(
                new AssistantTurn("coordinate42 inventory7", List.of(), 0, 0, -1, "stop"))).join();
        c.add(new Msg.User("new".repeat(1500)));
        c.compact((s, h, t) -> {
            String prompt = ((Msg.User) h.getFirst()).text();
            assertEquals(1, prompt.split("coordinate42 inventory7", -1).length - 1);
            assertFalse(prompt.contains("[对话前情提要]"));
            return CompletableFuture.completedFuture(new AssistantTurn("updated", List.of(), 0, 0, -1, "stop"));
        }).join();
        assertEquals("updated", c.summary());
    }
}
