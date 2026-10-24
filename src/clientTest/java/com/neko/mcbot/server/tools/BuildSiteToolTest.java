package com.neko.mcbot.server.tools;

import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.loop.TaskPolicy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuildSiteToolTest {
    @BeforeAll static void bootstrap() { com.neko.mcbot.server.BlockActionFixtures.bootstrap(); }
    @Test void beginNeedsOnlyATargetAndAcceptsAMatchingRevisionButNeverIgnoresUnknownFields() {
        var args=JsonParser.parseString("""
                {"operation":"begin","target":{"anchor":{"x":4,"y":90,"z":0},
                "cells":[{"x":0,"y":0,"z":0,"block":"stone"}]}}
                """).getAsJsonObject();
        var input=BuildSiteTool.arguments(args);
        assertTrue(input.route().isEmpty()); assertNull(input.scope());
        args.addProperty("expected_revision",input.revision());
        assertEquals(input,BuildSiteTool.arguments(args));
        args.addProperty("max_digs",0);
        assertThrows(RuntimeException.class,()->BuildSiteTool.arguments(args));
        args.remove("max_digs"); args.addProperty("expected_revision","b".repeat(64));
        assertThrows(RuntimeException.class,()->BuildSiteTool.arguments(args));
    }
    @Test void queryIsStrictlyReadOnlyButBeginAndCheckpointRequireActionAuthority() {
        assertTrue(TaskPolicy.observation("build_site",JsonParser.parseString("{\"operation\":\"query\"}").getAsJsonObject()));
        for(String text:java.util.List.of("{}", "{\"operation\":\"begin\"}", "{\"operation\":\"checkpoint\"}",
                "{\"operation\":\"query\",\"target\":{}}","{\"operation\":true}")) {
            var args=JsonParser.parseString(text).getAsJsonObject();
            assertFalse(TaskPolicy.observation("build_site",args));
            assertThrows(RuntimeException.class,()->BuildSiteTool.arguments(args));
            assertFalse(new BuildSiteTool().runAuthorized(null,args,null,null,null).join().ok());
        }
    }
    @Test void temporaryIntentAndCleanupParametersCannotSilentlyFallBackToOrdinaryActions() {
        var bad=JsonParser.parseString("{\"x\":1,\"y\":90,\"z\":0,\"item\":\"stone\",\"temporary_purpose\":true}").getAsJsonObject();
        assertNull(PlaceBlockTool.arguments(bad));
        for(String text:java.util.List.of("{\"x\":1,\"y\":90,\"z\":0,\"temporary_cleanup\":true}",
                "{\"x\":1,\"y\":90,\"z\":0,\"site_revision\":\"bad\"}",
                "{\"x\":1,\"y\":90,\"z\":0,\"site_revision\":\""+"a".repeat(64)+"\",\"expected_block\":\"minecraft:dirt\",\"clear_obstructions\":true}")) {
            assertFalse(new BreakBlockTool().runAuthorized(null,JsonParser.parseString(text).getAsJsonObject(),null,null,null).join().ok());
        }
    }
}
