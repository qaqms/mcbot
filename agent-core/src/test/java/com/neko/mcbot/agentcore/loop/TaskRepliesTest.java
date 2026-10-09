package com.neko.mcbot.agentcore.loop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskRepliesTest {
    @Test
    void unrelatedTaskCannotConsumeAnAskReply() {
        var replies = new TaskReplies();
        var answer = replies.register(22);
        replies.finish(11, AgentLoop.TaskStatus.COMPLETED, "A reply");
        assertFalse(answer.isDone());
        replies.finish(22, AgentLoop.TaskStatus.COMPLETED, "B reply");
        assertEquals("B reply", answer.join());
        assertEquals(0, replies.size());
    }

    @Test
    void completionOrderDoesNotChangePairing() {
        var replies = new TaskReplies();
        var first = replies.register(11);
        var second = replies.register(22);
        replies.finish(22, AgentLoop.TaskStatus.COMPLETED, "second");
        assertEquals("second", second.join());
        assertFalse(first.isDone());
        replies.finish(11, AgentLoop.TaskStatus.COMPLETED, "first");
        assertEquals("first", first.join());
    }

    @Test
    void cancelledFailedAndSupersededRepliesAreNotSuccess() {
        for (var status : new AgentLoop.TaskStatus[] {AgentLoop.TaskStatus.CANCELLED,
                AgentLoop.TaskStatus.FAILED, AgentLoop.TaskStatus.SUPERSEDED}) {
            var replies = new TaskReplies();
            var answer = replies.register(11);
            replies.finish(11, status, "not done");
            assertTrue(answer.isCompletedExceptionally());
            assertEquals(0, replies.size());
            replies.finish(11, AgentLoop.TaskStatus.COMPLETED, "late");
            assertTrue(answer.isCompletedExceptionally());
        }
    }

    @Test
    void expiredWaiterCanBeReleasedWithoutCancellingTheTask() {
        var replies = new TaskReplies();
        var answer = replies.register(11);
        answer.cancel(false);
        assertEquals(0, replies.size());
        replies.finish(11, AgentLoop.TaskStatus.COMPLETED, "late reply");
        assertTrue(answer.isCancelled());
    }
}
