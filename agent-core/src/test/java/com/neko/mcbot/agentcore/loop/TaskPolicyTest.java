package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskPolicyTest {
    @Test void ownerReadOnlyMarkersOnlyRestrict() {
        for (String text : new String[]{"[只读]探查", "仅扫描四周", "不要移动", "[READ-ONLY] inspect"}) {
            assertTrue(TaskPolicy.readOnlyDirective(text));
        }
        assertFalse(TaskPolicy.readOnlyDirective("mine three blocks"));
    }

    @Test void queryModesRequireActualTypes() {
        var empty = JsonParser.parseString("{}").getAsJsonObject();
        assertTrue(TaskPolicy.observation("smelt", empty));
        assertFalse(TaskPolicy.observation("craft", empty));
        assertFalse(TaskPolicy.observation("craft", JsonParser.parseString("{\"query\":\"true\"}").getAsJsonObject()));
        assertTrue(TaskPolicy.observation("craft", JsonParser.parseString("{\"query\":true}").getAsJsonObject()));
        assertFalse(TaskPolicy.observation("smelt", JsonParser.parseString("{\"action\":null}").getAsJsonObject()));
        for (String name : new String[]{"move_to", "break_block", "place_block", "equip", "attack", "unknown"}) {
            assertFalse(TaskPolicy.observation(name, empty));
        }
    }

    @Test void denialOrAmbiguousRepliesCannotAuthorize() {
        for (String text : new String[]{"不可以", "不同意", "yes but don't dig", "不知道", "取消", "maybe"}) {
            assertFalse(TaskPolicy.affirmative(text));
        }
        assertTrue(TaskPolicy.affirmative("确认"));
        assertTrue(TaskPolicy.affirmative(" YES "));
    }
}
