package com.neko.mcbot.agent;

import com.neko.mcbot.agentcore.loop.AgentLoop.TaskStatus;
import com.neko.mcbot.agentcore.loop.ItemGoal;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TaskBubbleStateTest {
    private final TaskBubbleState state = new TaskBubbleState();
    private TaskBubbleState.Snapshot snapshot(long active, long now) {
        return state.snapshot(active, TaskBubbleState.Activity.MODEL, "", null, null, now);
    }
    @Test void idleAndUnrelatedTaskHaveNoBubble() {
        assertNull(snapshot(0, 0));
        state.begin(7, "task");
        assertNull(snapshot(9, 0));
        assertEquals(7, snapshot(7, 0).taskId());
    }
    @Test void terminalLifetimesExpireWithoutRenderingOrTicks() {
        for (var pair : new Object[][]{
                {TaskStatus.COMPLETED, 6000L}, {TaskStatus.FAILED, 10000L},
                {TaskStatus.CANCELLED, 4000L}, {TaskStatus.SUPERSEDED, 4000L}}) {
            state.begin(7, "task");
            state.finish(7, (TaskStatus) pair[0], "reason", null, 1000);
            long deadline = 1000 + (long) pair[1];
            assertNotNull(snapshot(0, deadline - 1));
            assertNull(snapshot(0, deadline));
        }
    }
    @Test void newTaskAndQueuedCancellationCannotReplaceActiveOrReviveOldTerminal() {
        state.begin(7, "old");
        state.finish(7, TaskStatus.COMPLETED, "", null, 1000);
        state.begin(8, "new");
        state.finish(7, TaskStatus.FAILED, "late", null, 1100);
        state.finish(9, TaskStatus.CANCELLED, "queued", null, 1100);
        assertEquals(TaskBubbleState.Tone.WORKING, snapshot(8, 1100).tone());
        assertEquals("new", snapshot(8, 1100).title());
    }
    @Test void questionOverridesPrimitiveAndPreservesRealTimeoutAndQuantity() {
        state.begin(7, "task");
        var quantity = new ItemGoal.Progress("minecraft:dirt", 2, 5, true, ItemGoal.Scope.SUBTASK, false);
        var q = new AgentRunner.QuestionSnapshot("q1", 7, "§c确认\n这些方块？", 7850, true);
        var display = state.snapshot(7, TaskBubbleState.Activity.MINING, "", quantity, q, 0);
        assertEquals(TaskBubbleState.Tone.QUESTION, display.tone());
        assertEquals("这一步需要确认", display.action());
        assertEquals(7850, display.remainingMs());
        assertEquals("确认 这些方块？", display.detail());
        assertEquals(quantity, display.progress());
        var stale = new AgentRunner.QuestionSnapshot("q2", 9, "old", 500, false);
        assertEquals(TaskBubbleState.Tone.WORKING,
                state.snapshot(7, TaskBubbleState.Activity.MINING, "", null, stale, 0).tone());
    }
    @Test void overlappingPrimitivesSettleOnlyTheirOwnSequenceAndTask() {
        state.begin(7, "task");
        state.sent(7, 20, TaskBubbleState.Activity.MINING);
        state.sent(0, 22, TaskBubbleState.Activity.OBSERVING);
        state.sent(7, 21, TaskBubbleState.Activity.MOVING);
        state.settled(8, 21);
        state.settled(7, 20);
        assertEquals(TaskBubbleState.Activity.MOVING, state.primitive());
        state.settled(7, 21);
        assertNull(state.primitive());
        state.sent(7, 23, TaskBubbleState.Activity.MINING);
        state.clear();
        assertNull(snapshot(7, 0));
        assertNull(state.primitive());
    }
    @Test void completionDoesNotInventQuantityVerificationAndCountsAreImmutable() {
        var goal = new ItemGoal(new ItemGoal.Target("minecraft:dirt", 4, true), BoundedGatherTest.state(10));
        goal.observe(BoundedGatherTest.state(13));
        var before = goal.snapshot();
        goal.observe(BoundedGatherTest.state(14));
        assertEquals(3, before.current());
        state.begin(7, "task");
        state.finish(7, TaskStatus.COMPLETED, "model said 100%", null, 0);
        assertEquals("任务已结束", snapshot(0, 1).action());
        assertNull(snapshot(0, 1).progress());
        state.begin(8, "next");
        state.finish(8, TaskStatus.COMPLETED, "", goal.snapshot(), 0);
        assertEquals(4, snapshot(0, 1).progress().current());
        assertEquals("物品目标已核对", snapshot(0, 1).action());
    }
    @Test void materialAlternativesAreFlaggedWithoutPretendingToBeTheFinalItem() {
        var goal = new ItemGoal(new ItemGoal.Target("minecraft:planks", 4, true),
                BoundedGatherTest.state(0), ItemGoal.Scope.SUBTASK,
                Set.of("minecraft:dirt", "minecraft:oak_log"));
        goal.observe(BoundedGatherTest.state(2));
        assertTrue(goal.snapshot().alternatives());
        assertEquals(ItemGoal.Scope.SUBTASK, goal.snapshot().scope());
        assertEquals(2, goal.snapshot().current());
    }
    @Test void unsupportedOrQueryToolsDoNotPretendToPerformAnAction() {
        assertEquals(TaskBubbleState.Activity.RECIPES, TaskBubbleState.Activity.tool("craft", true));
        assertEquals(TaskBubbleState.Activity.CRAFTING, TaskBubbleState.Activity.tool("craft", false));
        assertEquals(TaskBubbleState.Activity.RECIPES, TaskBubbleState.Activity.tool("smelt", true));
        assertEquals(TaskBubbleState.Activity.OBSERVING, TaskBubbleState.Activity.tool("place_block", true));
        assertEquals(TaskBubbleState.Activity.PLACING, TaskBubbleState.Activity.tool("place_block", false));
        assertEquals(TaskBubbleState.Activity.PROCESSING, TaskBubbleState.Activity.tool("unknown", false));
    }
}
