package com.neko.mcbot.agentcore.convo;

import com.neko.mcbot.agentcore.llm.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ConversationCompactionEconomyTest {
    private static AssistantTurn text(String value) {
        return new AssistantTurn(value, List.of(), 0, 0, -1, "stop");
    }

    @Test void fixedDefinitionsCannotTriggerRepeatedSummariesOfShortHistory() {
        var c = new Conversation(6000);
        for (int i = 0; i < 30; i++) c.add(new Msg.User("short event " + i));
        var tools = List.of(ToolSpec.of("large_definition", "界".repeat(7000), "{}"));
        int request = c.requestTokens("system", tools, c.history());
        assertTrue(request > 6000);
        assertFalse(c.compactionDue(request, "system", tools, c.history()));
        c.noteRequest("system", tools, c.history());
        c.noteUsage(new AssistantTurn("", List.of(), request, 10, -1, "stop"));
        assertFalse(c.needsCompaction(), "chain end also excludes fixed input");
    }

    @Test void aSingleUserDirectiveCannotForceTheWholeToolHistoryToRemainVerbatim() {
        var c = new Conversation(6000);
        c.add(new Msg.User("collect resources"));
        for (int i = 0; i < 40; i++) {
            c.add(new Msg.Assistant("", List.of(new ToolCall("c" + i, "scan_area", "{}"))));
            c.add(new Msg.Tool("c" + i, "scan_area", "事实".repeat(120), true));
        }
        assertTrue(c.needsCompaction());
        int cut = Conversation.findCutIndex(c.history(), 1500);
        assertTrue(cut > 1);
        assertTrue(c.history().get(cut) instanceof Msg.Assistant);
        var tail = c.history().subList(cut, c.history().size());
        int before = c.approxTokens();
        c.compact((s, h, t) -> CompletableFuture.completedFuture(text("resources observed, not yet complete"))).join();
        assertEquals(tail, c.history().subList(1, c.history().size()));
        assertTrue(c.approxTokens() < before);
        assertFalse(c.needsCompaction());
    }

    @Test void anExpandingSummaryIsRejectedWithoutChangingHistoryOrPrefix() {
        var c = new Conversation(6000);
        c.configure(6000, 100, true);
        for (int i = 0; i < 10; i++) c.add(new Msg.User("event".repeat(50)));
        var before = c.history();
        c.compact((s, h, t) -> CompletableFuture.completedFuture(text("界".repeat(1000)))).join();
        assertEquals(before, c.history());
        assertEquals(0, c.prefixResets());
        assertEquals("", c.summary());
    }

    @Test void anExistingSummaryAloneIsNotNewCompressibleHistory() {
        var c = new Conversation(6000);
        c.add(new Msg.User("historical facts ".repeat(3000)));
        c.compact((s, h, t) -> CompletableFuture.completedFuture(text("界".repeat(1200)))).join();
        for (int i = 0; i < 12; i++) c.add(new Msg.Assistant("ok", List.of()));
        c.configure(6000, 100, true);
        assertFalse(c.hasUsefulCompaction());
        assertFalse(c.needsCompaction());
    }

    @Test void allMessagesWithinTheRecentBudgetHaveNoSummaryWork() {
        var history = List.<Msg>of(new Msg.User("task"),
                new Msg.Assistant("", List.of(new ToolCall("c1", "status", "{}"))),
                new Msg.Tool("c1", "status", "latest fact", true));
        assertEquals(0, Conversation.findCutIndex(history, 1500));
    }
}
