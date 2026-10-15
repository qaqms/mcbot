package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SearchPagesTest {
    static String args(int offset) {
        return "{\"targets\":[\"oak_log\"],\"r\":16,\"offset\":" + offset
                + ",\"scan_center\":{\"x\":0,\"y\":80,\"z\":0}}";
    }

    static JsonObject page(int offset) {
        var data = JsonParser.parseString(args(offset)).getAsJsonObject();
        data.remove("targets");
        data.remove("r");
        data.addProperty("radius", 16);
        data.addProperty("samples_planned", 17077);
        data.addProperty("samples_examined", Math.min(4096, 17077 - offset));
        data.addProperty("next_offset", offset == 16384 ? -1 : offset + 4096);
        data.addProperty("no_targets", true);
        return data;
    }

    private boolean query(SearchPages pages, int offset) {
        return pages.continues("find_resource", args(offset), new ToolOutcome(true, "page", page(offset)));
    }

    @Test void fiveAdvancingPagesAreOneSearchAndStatusDoesNotBreakTheChain() {
        var pages = new SearchPages();
        assertFalse(query(pages, 0));
        for (int offset = 4096; offset <= 16384; offset += 4096) {
            assertFalse(pages.continues("status", "{}", new ToolOutcome(true, "state")));
            assertTrue(query(pages, offset));
        }
        assertFalse(query(pages, 16384));
        assertFalse(query(pages, 0));
        assertFalse(query(pages, 0));
    }

    @Test void skippedRepeatedAndChangedQueriesDoNotEarnAContinuation() {
        for (String changed : java.util.List.of(
                args(4096).replace("oak_log", "birch_log"),
                args(4096).replace("\"r\":16", "\"r\":15"),
                args(4096).replace("\"y\":80", "\"y\":81"))) {
            var pages = new SearchPages();
            query(pages, 0);
            assertFalse(pages.continues("find_resource", changed,
                    new ToolOutcome(true, "page", page(4096))));
        }
        var pages = new SearchPages();
        query(pages, 0);
        assertFalse(query(pages, 8192));
        assertFalse(query(pages, 12288));
        query(pages, 0);
        assertTrue(query(pages, 4096));
        assertFalse(query(pages, 4096));
        assertFalse(query(pages, 8192));
    }

    @Test void malformedOrFailedReceiptsAndIndependentSearchesBreakTheChain() {
        for (String key : java.util.List.of("offset", "radius", "samples_planned", "samples_examined", "next_offset")) {
            var pages = new SearchPages();
            query(pages, 0);
            var data = page(4096);
            data.addProperty(key, 7.5);
            assertFalse(pages.continues("find_resource", args(4096), new ToolOutcome(true, "invalid", data)));
            assertFalse(query(pages, 8192));
        }
        var pages = new SearchPages();
        query(pages, 0);
        assertFalse(pages.continues("find_resource", args(4096), new ToolOutcome(false, "failed", page(4096))));
        assertFalse(query(pages, 4096));
        query(pages, 0);
        pages.continues("scan_area", "{}", new ToolOutcome(true, "independent"));
        assertFalse(query(pages, 4096));
        query(pages, 0);
        pages.reset();
        assertFalse(query(pages, 4096));
    }
}
