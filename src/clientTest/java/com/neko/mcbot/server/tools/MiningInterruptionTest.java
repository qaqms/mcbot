package com.neko.mcbot.server.tools;

import com.neko.mcbot.server.BlockActionFixtures;
import com.neko.mcbot.server.BlockMining;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.task.TickTask;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MiningInterruptionTest {
    @BeforeAll static void bootstrap() { BlockActionFixtures.bootstrap(); }

    @Test void cancellationDuringPostRemovalDelayKeepsEffectButRemainsCancellation() {
        var access = new BlockActionFixtures.Mining();
        access.gain = 1;
        var task = new BreakBlockTool.Task(new BlockMining(access));
        assertInstanceOf(TickTask.Progress.Running.class, task.tickSafe(null));
        var stopped = task.interruptedResult(new ServerTool.Result(false, "CANCELLED:owner", null));
        task.onAbort();
        assertFalse(stopped.ok());
        assertTrue(stopped.feedback().startsWith("CANCELLED:"));
        assertTrue(stopped.data().get("removed").getAsBoolean());
        assertEquals(1, access.destroys);
    }

    @Test void safeResumeStillUsesFrozenTargetAndHandChecks() {
        var access = new BlockActionFixtures.Mining();
        var task = new BreakBlockTool.Task(new BlockMining(access));
        task.tickSafe(null);
        task.onSuspend();
        access.inventory.setSelectedSlot(1);
        assertTrue(task.onResume(null).feedback().startsWith("WRONG_TOOL:"));
        assertEquals(0, access.destroys);
        assertEquals(-1, access.cracks.getLast());
    }
}
