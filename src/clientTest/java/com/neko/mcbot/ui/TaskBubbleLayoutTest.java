package com.neko.mcbot.ui;

import com.neko.mcbot.agent.TaskBubbleState;
import com.neko.mcbot.agentcore.loop.ItemGoal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskBubbleLayoutTest {
    @Test void spinnerReservesFixedSpaceAndCannotOverlapLongActionTextOrOtherRows() {
        for (var tone : TaskBubbleState.Tone.values()) {
            var state = new TaskBubbleState.Snapshot(7, "long task", tone,
                    "等待下一步".repeat(80), "等待模型响应", null, 0, true);
            var layout = TaskBubbleLayout.create(state, "Bot", "", TaskBubbleLayoutTest::width);
            boolean visible = tone == TaskBubbleState.Tone.WORKING;
            assertEquals(visible, layout.spinning());
            var action = layout.lines().stream().filter(line -> line.y() == 22).findFirst().orElseThrow();
            assertEquals(TaskBubbleLayout.PADDING + (visible ? 14 : 0), action.x());
            assertTrue(action.x() + width(action.text()) <= TaskBubbleLayout.WIDTH - TaskBubbleLayout.PADDING);
            assertTrue(layout.lines().stream().filter(line -> line.y() != 22)
                    .allMatch(line -> line.x() >= TaskBubbleLayout.PADDING));
        }
    }
    private static int width(String text) {
        return text.codePoints().map(c -> c < 128 ? 6 : 9).sum();
    }
    @Test void longChineseNamesQuestionsAndTitlesStayInsideFixedGeometry() {
        for (var tone : TaskBubbleState.Tone.values()) {
            var state = new TaskBubbleState.Snapshot(7, "长任务名称中文".repeat(80), tone,
                    "正在沿路标返回", "非常长的待答清单以及失败原因".repeat(80), null, 1);
            var layout = TaskBubbleLayout.create(state, "很长的名字".repeat(20), "", TaskBubbleLayoutTest::width);
            for (var line : layout.lines()) {
                assertTrue(line.x() >= TaskBubbleLayout.PADDING);
                assertTrue(line.x() + width(line.text()) <= TaskBubbleLayout.WIDTH - TaskBubbleLayout.PADDING);
                assertTrue(line.y() >= 0 && line.y() + 9 <= TaskBubbleLayout.HEIGHT);
            }
            assertEquals(2, layout.lines().stream().filter(l -> l.y() == 36 || l.y() == 46).count());
            assertEquals(-1, layout.progress());
        }
    }
    @Test void quantityBarIsBoundedAndClearlyScopedAsInventoryRatherThanOverallTask() {
        for (boolean additional : new boolean[]{false, true}) {
            var goal = new ItemGoal.Progress("minecraft:dirt", 5000, 4096, additional, ItemGoal.Scope.SUBTASK, false);
            var state = new TaskBubbleState.Snapshot(7, "制作最终成品", TaskBubbleState.Tone.WORKING,
                    "正在挖掘", "", goal, 0);
            var layout = TaskBubbleLayout.create(state, "Bot", "泥土".repeat(30), TaskBubbleLayoutTest::width);
            assertEquals(1, layout.progress());
            assertTrue(layout.lines().stream().anyMatch(l -> l.text().startsWith("本段")));
            assertTrue(layout.lines().stream().anyMatch(l -> l.text().equals((additional ? "净增" : "总量") + " 5000/4096")));
            for (var line : layout.lines())
                assertTrue(line.x() + width(line.text()) <= TaskBubbleLayout.WIDTH - TaskBubbleLayout.PADDING);
        }
    }
    @Test void unknownProgressIsOmittedAndAlternativeSourcesHaveAnExplicitLabel() {
        var progress = new ItemGoal.Progress("minecraft:planks", 1, 4, true, ItemGoal.Scope.SUBTASK, true);
        var state = new TaskBubbleState.Snapshot(1, "最终目标", TaskBubbleState.Tone.WORKING, "正在采集", "", progress, 0);
        var layout = TaskBubbleLayout.create(state, "Bot", "木板", TaskBubbleLayoutTest::width);
        assertTrue(layout.lines().stream().anyMatch(l -> l.text().contains("配方来源合计")));
        assertTrue(layout.lines().stream().noneMatch(l -> l.text().contains("木板")));
        assertEquals(.25f, layout.progress());
    }
    @Test void clippingPreservesUnicodeBoundariesAndRealQuestionCountdown() {
        String clipped = TaskBubbleLayout.fit("a😀b😀c😀d", 32, TaskBubbleLayoutTest::width);
        assertTrue(width(clipped) <= 32);
        assertFalse(clipped.codePoints().anyMatch(c -> c >= 0xD800 && c <= 0xDFFF));
        var state = new TaskBubbleState.Snapshot(1, "", TaskBubbleState.Tone.QUESTION, "想问你一下", "", null, 7850);
        assertTrue(TaskBubbleLayout.create(state, "Bot", "", TaskBubbleLayoutTest::width)
                .lines().stream().anyMatch(l -> l.text().equals("0:08")));
    }
    @Test void distanceVisibilityAndNearScaleDoNotCreateAScreenFallback() {
        assertEquals(1, TaskBubbleVisibility.opacity(20, true, true, true));
        assertEquals(.5f, TaskBubbleVisibility.opacity(22, true, true, true));
        for (double distance : new double[]{-1, 24, 100, Double.NaN, Double.POSITIVE_INFINITY})
            assertEquals(0, TaskBubbleVisibility.opacity(distance, true, true, true));
        assertEquals(0, TaskBubbleVisibility.opacity(4, false, true, true));
        assertEquals(0, TaskBubbleVisibility.opacity(4, true, false, true));
        assertEquals(0, TaskBubbleVisibility.opacity(4, true, true, false));
        assertEquals(.003f, TaskBubbleVisibility.scale(1), .000001);
        assertEquals(.012f, TaskBubbleVisibility.scale(4), .000001);
        assertEquals(TaskBubbleVisibility.scale(4), TaskBubbleVisibility.scale(20));
    }
}
