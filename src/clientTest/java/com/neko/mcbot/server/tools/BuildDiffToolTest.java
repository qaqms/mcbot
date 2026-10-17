package com.neko.mcbot.server.tools;

import com.google.gson.JsonParser;
import com.neko.mcbot.building.BuildTarget;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuildDiffToolTest {
    @BeforeAll static void bootstrap() { com.neko.mcbot.server.BlockActionFixtures.bootstrap(); }
    private static final String TARGET = """
            {"anchor":{"x":0,"y":90,"z":0},"fill":{"from":{"x":0,"y":0,"z":0},
            "to":{"x":7,"y":0,"z":7},"block":"stone"}}
            """;

    @Test void invalidOrForgedModificationArgumentsAreRejectedBeforeAnyWorldAccess() {
        for (String extra : java.util.List.of(",\"execute\":true", ",\"offset\":1",
                ",\"offset\":16", ",\"material_offset\":16",
                ",\"expected_revision\":\"invalid\"")) {
            var input = JsonParser.parseString("{\"target\":" + TARGET + extra + "}").getAsJsonObject();
            var result = BuildDiffTool.report(input, target -> {
                fail("invalid input must be rejected before body/world access");
                return null;
            }, () -> 0);
            assertFalse(result.ok());
            assertTrue(result.feedback().startsWith("DENIED:"), result.feedback());
        }
    }

    @Test void changedTargetCannotReuseARevisionFromAnotherPageEvenAtTheSameAnchor() {
        var input = JsonParser.parseString("{\"target\":" + TARGET + ",\"offset\":16}").getAsJsonObject();
        var target = input.getAsJsonObject("target");
        String previous = BuildTarget.parse(target).revision();
        target.getAsJsonObject("fill").addProperty("block", "dirt");
        input.addProperty("expected_revision", previous);
        var result = BuildDiffTool.report(input, ignored -> {
            fail("mismatched revision must be rejected before body/world access");
            return null;
        }, () -> 0);
        assertFalse(result.ok());
        assertTrue(result.feedback().startsWith("TARGET_REVISION_CHANGED:"));
    }
}
