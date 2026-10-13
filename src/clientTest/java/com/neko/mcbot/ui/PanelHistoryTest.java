package com.neko.mcbot.ui;

import com.neko.mcbot.agent.PanelHistory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PanelHistoryTest {
    @Test void displayKeepsFullQuestionTextAndGroupsRolesWithoutChatFormatting() {
        var history = new PanelHistory(); history.begin(3, "task");
        String scope = "server scope\n".repeat(100);
        history.add(3, "§9我> §rtask");
        history.add(3, "§d[同伴想问] §r" + scope + " §7（回答：@bot 答 <文本>）§r");
        history.add(3, "§7move_to ✔ result§r");
        history.add(3, "§b[同伴] §rdone");
        assertEquals(PanelHistory.Kind.USER, history.entries(3).getFirst().kind());
        assertEquals(scope.strip(), history.entries(3).get(1).text());
        assertEquals(PanelHistory.Kind.QUESTION, history.entries(3).get(1).kind());
        assertEquals(PanelHistory.Kind.TOOL, history.entries(3).get(2).kind());
        assertEquals("done", history.entries(3).getLast().text());
    }
    @Test void taskSwitchDoesNotMixAnswersAndArchivesRemainBounded() {
        var history = new PanelHistory();
        for (int i = 1; i <= 25; i++) {
            history.begin(i, "task " + i); history.add(i, "§9我> §rtask " + i); history.status(i, "completed");
        }
        assertEquals(20, history.tasks().size());
        assertEquals(25, history.tasks().getFirst().id());
        assertTrue(history.entries(1).isEmpty());
        assertEquals(1, history.entries(25).size());
        assertEquals("task 25", history.entries(25).getFirst().text());
    }
    @Test void timerNeverGoesNegativeAndRoundsUpTheLastSecond() {
        assertEquals("02:00", McbotPanelScreen.formatTime(120000));
        assertEquals("00:01", McbotPanelScreen.formatTime(1));
        assertEquals("00:00", McbotPanelScreen.formatTime(-1));
    }
}
