package com.neko.mcbot.ui;

import com.neko.mcbot.agent.TaskBubbleState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskBubbleMotionTest {
    private static TaskBubbleState.Snapshot state(long task, String detail) {
        return new TaskBubbleState.Snapshot(task, "task", TaskBubbleState.Tone.WORKING,
                "模型正在思考", detail, null, 0, true);
    }

    @Test void lettersAppearOnceAndHoldWhileTheSpinnerContinues() {
        var motion = new TaskBubbleMotion();
        assertEquals("模", motion.sample(state(7, ""), "模型正在思考", 1000).text());
        assertEquals("模型", motion.sample(state(7, "1 秒"), "模型正在思考", 1140).text());
        var complete = motion.sample(state(7, "2 秒"), "模型正在思考", 1900);
        assertEquals("模型正在思考", complete.text());
        assertFalse(complete.typing());
        assertFalse(complete.caret());
        var later = motion.sample(state(7, "30 秒"), "模型正在思考", 31000);
        assertEquals(complete.text(), later.text());
        assertEquals(1, later.opacity());
        assertNotEquals(complete.rotation(), later.rotation());
    }

    @Test void unicodeIsWholeAndNewTasksOrActivitiesRestartTheReveal() {
        var motion = new TaskBubbleMotion();
        assertEquals("猫", motion.sample(state(1, ""), "猫😀在等你", 0).text());
        assertEquals("猫😀", motion.sample(state(1, ""), "猫😀在等你", 140).text());
        assertEquals("猫", motion.sample(state(2, ""), "猫😀在等你", 500).text());
        assertEquals("正", motion.sample(state(2, ""), "正在挖掘", 700).text());
        motion.clear();
        assertEquals("正", motion.sample(state(2, ""), "正在挖掘", 1400).text());
    }

    @Test void questionCountdownDoesNotRetypeButAChangedQuestionDoes() {
        var motion = new TaskBubbleMotion();
        var first = new TaskBubbleState.Snapshot(7, "", TaskBubbleState.Tone.QUESTION,
                "想问你一下", "继续？", null, 120000);
        motion.sample(first, first.action(), 0);
        var tick = new TaskBubbleState.Snapshot(7, "", first.tone(), first.action(), first.detail(), null, 118000);
        assertEquals(first.action(), motion.sample(tick, tick.action(), 2000).text());
        var next = new TaskBubbleState.Snapshot(7, "", first.tone(), first.action(), "换位置？", null, 120000);
        assertEquals("想", motion.sample(next, next.action(), 2100).text());
    }

    @Test void spinnerTakesOverTwoSecondsAndWrapsAtItsOriginalPeriod() {
        assertEquals(TaskBubbleMotion.rotation(0), TaskBubbleMotion.rotation(2400));
        assertEquals((float) Math.PI, TaskBubbleMotion.rotation(1200), .0001f);
        assertTrue(TaskBubbleMotion.rotation(-1) >= 0);
    }
}
