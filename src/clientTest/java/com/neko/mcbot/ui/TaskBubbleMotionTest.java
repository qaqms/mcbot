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

    @Test void returningFromToolsKeepsTheCompletedWaitingMessageAndSpinnerClock() {
        var motion = new TaskBubbleMotion();
        var waiting = state(7, "");
        motion.sample(waiting, waiting.action(), 1000);
        assertFalse(motion.sample(waiting, waiting.action(), 1900).typing());
        for (long now : new long[]{2000, 4000, 6000}) {
            var mining = new TaskBubbleState.Snapshot(7, "task", TaskBubbleState.Tone.WORKING,
                    "正在挖掘", "", null, 0);
            assertEquals("正", motion.sample(mining, mining.action(), now).text());
            var returned = motion.sample(waiting, waiting.action(), now + 200);
            assertEquals(waiting.action(), returned.text());
            assertFalse(returned.typing());
            assertFalse(returned.caret());
            assertEquals(1, returned.opacity());
            assertEquals(TaskBubbleMotion.rotation(now + 200 - 1000), returned.rotation());
        }
    }

    @Test void briefToolTransitionsResumeTheFirstRevealInsteadOfStartingAgain() {
        var motion = new TaskBubbleMotion();
        var waiting = state(7, "");
        motion.sample(waiting, waiting.action(), 1000);
        var observing = new TaskBubbleState.Snapshot(7, "task", TaskBubbleState.Tone.WORKING,
                "正在核对状态", "", null, 0);
        motion.sample(observing, observing.action(), 1050);
        assertEquals("模型", motion.sample(waiting, waiting.action(), 1140).text());
        assertEquals(waiting.action(), motion.sample(waiting, waiting.action(), 1900).text());
    }

    @Test void compactionAndQuestionsDoNotRestartEitherWaitingMessage() {
        var motion = new TaskBubbleMotion();
        var waiting = state(7, "");
        motion.sample(waiting, waiting.action(), 0);
        var compacting = new TaskBubbleState.Snapshot(7, "task", TaskBubbleState.Tone.WORKING,
                "正在整理上下文", "", null, 0, true);
        assertEquals("正", motion.sample(compacting, compacting.action(), 2000).text());
        assertEquals(waiting.action(), motion.sample(waiting, waiting.action(), 4000).text());
        var question = new TaskBubbleState.Snapshot(7, "task", TaskBubbleState.Tone.QUESTION,
                "想问你一下", "继续？", null, 120000);
        assertEquals("想", motion.sample(question, question.action(), 5000).text());
        assertEquals(compacting.action(), motion.sample(compacting, compacting.action(), 6000).text());
        assertEquals(waiting.action(), motion.sample(waiting, waiting.action(), 7000).text());
    }

    @Test void newTasksAndClearedIdentityDiscardEarlierWaitingMessages() {
        var motion = new TaskBubbleMotion();
        motion.sample(state(7, ""), "模型正在思考", 0);
        motion.sample(state(7, ""), "模型正在思考", 1000);
        assertEquals("模", motion.sample(state(8, ""), "模型正在思考", 2000).text());
        assertEquals("模", motion.sample(state(7, ""), "模型正在思考", 3000).text());
        motion.sample(state(7, ""), "模型正在思考", 4000);
        motion.clear();
        assertEquals("模", motion.sample(state(7, ""), "模型正在思考", 5000).text());
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
