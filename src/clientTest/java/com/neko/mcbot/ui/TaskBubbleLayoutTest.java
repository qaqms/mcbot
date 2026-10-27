package com.neko.mcbot.ui;

import com.neko.mcbot.agent.TaskBubbleState;
import com.neko.mcbot.agentcore.loop.ItemGoal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskBubbleLayoutTest {
    @Test void thinkingIsOneStripWithReservedSpinnerAndCatSpace() {
        for (var tone : TaskBubbleState.Tone.values()) {
            var state = new TaskBubbleState.Snapshot(7, "long task", tone,
                    "模型正在思考".repeat(80), "", null, 0, true);
            var layout = TaskBubbleLayout.create(state, TaskBubbleLayoutTest::width);
            boolean visible = tone == TaskBubbleState.Tone.WORKING;
            assertEquals(visible, layout.spinning());
            assertEquals(TaskBubbleLayout.ROW_HEIGHT, layout.height());
            var action = layout.message();
            assertEquals(TaskBubbleLayout.PADDING, action.x());
            if (visible) {
                assertTrue(action.x() + width(action.text()) < layout.spinnerX() - 5);
                assertTrue(layout.spinnerX() + 5 < layout.width() - TaskBubbleCat.WIDTH - 5);
            }
            assertInside(layout);
        }
    }
    private static int width(String text) {
        return text.codePoints().map(c -> c < 128 ? 6 : 9).sum();
    }
    @Test void longChineseNamesQuestionsAndTitlesStayInsideFixedGeometry() {
        for (var tone : TaskBubbleState.Tone.values()) {
            var state = new TaskBubbleState.Snapshot(7, "长任务名称中文".repeat(80), tone,
                    "正在沿路标返回", "非常长的待答清单以及失败原因".repeat(80), null, 1);
            var layout = TaskBubbleLayout.create(state, TaskBubbleLayoutTest::width);
            assertInside(layout);
            boolean detail = tone == TaskBubbleState.Tone.QUESTION || tone == TaskBubbleState.Tone.FAILED;
            assertEquals(detail ? 42 : 28, layout.height());
            assertTrue(layout.lines().stream().noneMatch(l -> l.text().contains("长任务名称")));
            assertEquals(detail ? 1 : 0, layout.lines().stream().filter(l -> l.y() == 25).count());
        }
    }
    @Test void quantitiesStayInlineAndKeepTheirActualScopeAndCountingBasis() {
        for (boolean additional : new boolean[]{false, true}) {
            var goal = new ItemGoal.Progress("minecraft:dirt", 5000, 4096, additional, ItemGoal.Scope.SUBTASK, false);
            var state = new TaskBubbleState.Snapshot(7, "制作最终成品", TaskBubbleState.Tone.WORKING,
                    "正在挖掘", "", goal, 0);
            var layout = TaskBubbleLayout.create(state, TaskBubbleLayoutTest::width);
            assertEquals(28, layout.height());
            assertTrue(layout.lines().stream().anyMatch(l -> l.text().equals("本段" + (additional ? "净增" : "总量") + " 5000/4096")));
            assertInside(layout);
            assertTrue(layout.message().x() + width(layout.message().text()) + 8 <= layout.lines().get(1).x());
        }
    }
    @Test void unknownProgressIsOmittedAndAlternativeSourcesHaveAnExplicitLabel() {
        var progress = new ItemGoal.Progress("minecraft:planks", 1, 4, true, ItemGoal.Scope.SUBTASK, true);
        var state = new TaskBubbleState.Snapshot(1, "最终目标", TaskBubbleState.Tone.WORKING, "正在采集", "", progress, 0);
        var layout = TaskBubbleLayout.create(state, TaskBubbleLayoutTest::width);
        assertTrue(layout.lines().stream().anyMatch(l -> l.text().contains("本段来源合计 1/4")));
        assertTrue(layout.lines().stream().noneMatch(l -> l.text().contains("木板")));
        var unknown = new TaskBubbleState.Snapshot(1, "task", TaskBubbleState.Tone.WORKING, "正在采集", "", null, 0);
        assertEquals(1, TaskBubbleLayout.create(unknown, TaskBubbleLayoutTest::width).lines().size());
    }
    @Test void clippingPreservesUnicodeBoundariesAndRealQuestionCountdown() {
        String clipped = TaskBubbleLayout.fit("a😀b😀c😀d", 32, TaskBubbleLayoutTest::width);
        assertTrue(width(clipped) <= 32);
        assertFalse(clipped.codePoints().anyMatch(c -> c >= 0xD800 && c <= 0xDFFF));
        var state = new TaskBubbleState.Snapshot(1, "", TaskBubbleState.Tone.QUESTION, "想问你一下", "", null, 7850);
        assertTrue(TaskBubbleLayout.create(state, TaskBubbleLayoutTest::width)
                .lines().stream().anyMatch(l -> l.text().equals("0:08")));
    }
    @Test void revealingTextKeepsGeometryAndChangingWaitDetailsCannotAddRows() {
        var goal = new ItemGoal.Progress("minecraft:dirt", 2, 5, false, ItemGoal.Scope.SUBTASK, false);
        var state = new TaskBubbleState.Snapshot(7, "你好", TaskBubbleState.Tone.WORKING,
                "模型正在思考", "等待模型响应 · 999 秒", goal, 0, true);
        var full = TaskBubbleLayout.create(state, TaskBubbleLayoutTest::width);
        assertEquals(1, full.lines().size());
        assertEquals(28, full.height());
        for (String text : new String[]{"", "模", "模型", "模型正在思考"}) {
            var shown = full.reveal(text, TaskBubbleLayoutTest::width);
            assertEquals(full.width(), shown.width());
            assertEquals(full.height(), shown.height());
            assertEquals(full.spinnerX(), shown.spinnerX());
            assertEquals(text, shown.message().text());
            assertTrue(shown.caretX() < shown.spinnerX() - 5);
        }
    }
    private static void assertInside(TaskBubbleLayout.Layout layout) {
        assertTrue(layout.width() >= TaskBubbleLayout.MIN_WIDTH && layout.width() <= TaskBubbleLayout.MAX_WIDTH);
        for (var line : layout.lines()) {
            assertTrue(line.x() >= TaskBubbleLayout.PADDING);
            assertTrue(line.x() + width(line.text()) <= layout.width() - TaskBubbleLayout.PADDING);
            assertTrue(line.y() >= 0 && line.y() + 9 <= layout.height());
        }
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
