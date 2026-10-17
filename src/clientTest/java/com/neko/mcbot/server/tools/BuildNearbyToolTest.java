package com.neko.mcbot.server.tools;

import com.google.gson.JsonParser;
import com.neko.mcbot.building.BuildTarget;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BuildNearbyToolTest {
    @BeforeAll static void bootstrap() { com.neko.mcbot.server.BlockActionFixtures.bootstrap(); }

    @Test void invalidOrChangedRevisionAndUnsafeLimitsAreRejectedBeforeBodyLookup() {
        String target = """
                {"anchor":{"x":0,"y":90,"z":0},"cells":[{"x":0,"y":0,"z":0,"block":"stone"}]}
                """;
        var input = JsonParser.parseString("{\"target\":" + target + "}").getAsJsonObject();
        var tool = new BuildNearbyTool();
        for (String bad : java.util.List.of("missing", "revision", "max_actions", "offset", "execute")) {
            var args = input.deepCopy();
            args.addProperty("expected_revision", BuildTarget.parse(args.getAsJsonObject("target")).revision());
            switch (bad) {
                case "missing" -> args.remove("expected_revision");
                case "revision" -> args.addProperty("expected_revision", "0".repeat(64));
                case "max_actions" -> args.addProperty("max_actions", 13);
                case "offset" -> args.addProperty("offset", 16);
                default -> args.addProperty("execute", true);
            }
            assertFalse(tool.runAuthorized(null, args, null, null, null).join().ok(), bad);
        }
        input.addProperty("expected_revision", BuildTarget.parse(input.getAsJsonObject("target")).revision());
        assertEquals(12, BuildNearbyTool.arguments(input).maxActions());
        assertEquals(200, tool.capTicks(input));
    }
}
