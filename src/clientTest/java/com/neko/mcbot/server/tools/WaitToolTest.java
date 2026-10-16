package com.neko.mcbot.server.tools;

import com.google.gson.JsonParser;
import com.neko.mcbot.task.TickTask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WaitToolTest {
    @Test void secondsAndTicksUseTheSameTickClockAndReturnActualDuration() {
        assertEquals(20, WaitTool.durationTicks(JsonParser.parseString("{\"seconds\":1}").getAsJsonObject()));
        assertEquals(7, WaitTool.durationTicks(JsonParser.parseString("{\"ticks\":7}").getAsJsonObject()));
        var task = new WaitTool.Task(7);
        for (int i = 0; i < 6; i++) assertInstanceOf(TickTask.Progress.Running.class, task.tickSafe(null));
        var done = assertInstanceOf(TickTask.Progress.Done.class, task.tickSafe(null));
        assertEquals(7, done.result().data().get("waited_ticks").getAsInt());
        assertEquals(7, done.result().data().get("target_ticks").getAsInt());
    }

    @Test void badDurationsAreRejectedBeforeAnyBodyOrSchedulerAccess() {
        for (String json : new String[]{"{}", "{\"seconds\":1,\"ticks\":3}", "{\"ticks\":0}",
                "{\"ticks\":1201}", "{\"ticks\":2.5}", "{\"ticks\":\"3\"}", "{\"seconds\":61}",
                "{\"seconds\":true}", "{\"ticks\":null}", "{\"ticks\":3,\"other\":1}"}) {
            var result = new WaitTool().runAsync(null, JsonParser.parseString(json).getAsJsonObject(), null).join();
            assertFalse(result.ok(), json);
            assertTrue(result.feedback().startsWith("DENIED:"));
        }
    }
}
