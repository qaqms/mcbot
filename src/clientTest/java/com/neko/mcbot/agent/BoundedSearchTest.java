package com.neko.mcbot.agent;

import com.google.gson.*;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class BoundedSearchTest {
    @Test void terrainMutationMidScanStopsAtTheChangedPageWithoutRestartingTheSearch() {
        var queue = new ArrayDeque<Runnable>();
        int[] calls = {0};
        var search = new BoundedSearch(json("{\"targets\":[\"stone\"]}"), (name, text) -> {
            int offset = json(text).get("offset").getAsInt();
            var data = page(offset, false);
            data.add("spatial_facts", json("{\"dimension\":\"minecraft:overworld\","
                    + "\"chunks\":{\"0,0\":\"stamp:" + ++calls[0] + "\"}}"));
            return CompletableFuture.completedFuture(new ToolOutcome(true, "page", data));
        }, queue::add, () -> 0, () -> {});
        var result = search.start();
        while (!queue.isEmpty()) queue.removeFirst().run();
        assertFalse(result.join().ok());
        assertTrue(result.join().feedback().startsWith("STATE_CHANGED:"));
        assertFalse(result.join().data().get("coverage_complete").getAsBoolean());
        assertEquals(2, calls[0]);
    }
    static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    static JsonObject page(int offset, boolean found) {
        var data = json("{\"radius\":16,\"samples_planned\":17077,\"unknown\":0,"
                + "\"scan_center\":{\"x\":0,\"y\":70,\"z\":0},\"targets\":[]}");
        int size = Math.min(4096, 17077 - offset);
        data.addProperty("offset", offset);
        data.addProperty("samples_examined", size);
        data.addProperty("cells_read", size);
        data.addProperty("next_offset", offset + size < 17077 ? offset + size : -1);
        data.addProperty("matches_observed", found ? 1 : 0);
        if (found) data.getAsJsonArray("targets").add(json("{\"block\":\"minecraft:oak_log\",\"x\":15,\"y\":70,\"z\":0}"));
        return data;
    }
    @Test void oneCallCompletesFivePagesAndReturnsTheLastPageTarget() {
        var queue = new ArrayDeque<Runnable>();
        var offsets = new ArrayList<Integer>();
        var search = new BoundedSearch(json("{\"targets\":[\"#minecraft:logs\"]}"), (name, text) -> {
            var args = json(text);
            int offset = args.get("offset").getAsInt();
            offsets.add(offset);
            if (offset > 0) assertEquals(json("{\"x\":0,\"y\":70,\"z\":0}"), args.get("scan_center"));
            return CompletableFuture.completedFuture(new ToolOutcome(true, "page", page(offset, offset == 16384)));
        }, queue::add, () -> 0, () -> fail("unexpected cancellation"));
        var result = search.start();
        while (!queue.isEmpty()) queue.removeFirst().run();
        assertEquals(java.util.List.of(0,4096,8192,12288,16384), offsets);
        assertTrue(result.join().ok(), result.join().feedback());
        assertEquals(17077, result.join().data().get("cells_read").getAsInt());
        assertEquals(1, result.join().data().getAsJsonArray("targets").size());
        assertTrue(result.join().data().get("coverage_complete").getAsBoolean());
    }
    @Test void changedCenterAndUnknownCoverageAreNotMisreportedAsCompleteKnownTerrain() {
        for (boolean changed : new boolean[]{false, true}) {
            var queue = new ArrayDeque<Runnable>();
            var search = new BoundedSearch(json("{\"targets\":[\"dirt\"],\"r\":16}"), (name, text) -> {
                int offset = json(text).get("offset").getAsInt();
                var data = page(offset, false);
                if (changed && offset > 0) data.getAsJsonObject("scan_center").addProperty("x", 1);
                if (!changed) { data.addProperty("unknown", 1); data.addProperty("cells_read", data.get("cells_read").getAsInt()-1); }
                return CompletableFuture.completedFuture(new ToolOutcome(true, "page", data));
            }, queue::add, () -> 0, () -> {});
            var result = search.start();
            while (!queue.isEmpty()) queue.removeFirst().run();
            assertEquals(!changed, result.join().ok());
            assertFalse(result.join().data().get("coverage_complete").getAsBoolean());
        }
    }
    @Test void laterEligibleCandidateIsKeptWhenEarlierPagesContainOnlyToolRefusals() {
        var queue = new ArrayDeque<Runnable>();
        var search = new BoundedSearch(json("{\"targets\":[\"iron_ore\",\"oak_log\"],\"work\":true}"), (name, text) -> {
            int offset = json(text).get("offset").getAsInt();
            var data = page(offset, offset == 16384);
            if (offset < 16384) {
                for (int i = 0; i < 16; i++) data.getAsJsonArray("targets").add(json(
                        "{\"block\":\"minecraft:iron_ore\",\"harvestable_with_hand\":false}"));
                data.addProperty("matches_observed", 16);
            }
            return CompletableFuture.completedFuture(new ToolOutcome(true, "page", data));
        }, queue::add, () -> 0, () -> fail("unexpected cancellation"));
        var result = search.start();
        while (!queue.isEmpty()) queue.removeFirst().run();
        assertTrue(result.join().ok());
        assertEquals(5, result.join().data().get("search_pages").getAsInt());
        assertEquals("minecraft:oak_log", result.join().data().getAsJsonArray("targets").get(0)
                .getAsJsonObject().get("block").getAsString());
        assertTrue(result.join().data().get("results_truncated").getAsBoolean());
    }
    @Test void cancellationAndTimeoutIgnoreLatePages() {
        for (boolean timeout : new boolean[]{false,true}) {
            long[] time = {0};
            var queue = new ArrayDeque<Runnable>();
            var response = new CompletableFuture<ToolOutcome>();
            int[] calls = {0}, cancels = {0};
            var search = new BoundedSearch(json("{\"targets\":[\"dirt\"]}"), (name, text) -> {
                calls[0]++; return response;
            }, queue::add, () -> time[0], () -> cancels[0]++);
            var result = search.start();
            queue.removeFirst().run();
            if (timeout) { time[0]=30000; search.tick(); } else search.stop("CANCELLED:owner");
            response.complete(new ToolOutcome(true, "late", page(0,false)));
            while (!queue.isEmpty()) queue.removeFirst().run();
            assertFalse(result.join().ok());
            assertEquals(1, calls[0]); assertEquals(1, cancels[0]);
        }
    }

    @Test void unsafeCandidatesCannotHideALaterSafeResourceAcrossPages() {
        var queue = new ArrayDeque<Runnable>();
        var search = new BoundedSearch(json("{\"targets\":[\"stone\",\"oak_log\"],\"work\":true}"), (name, text) -> {
            int offset = json(text).get("offset").getAsInt();
            var data = page(offset, offset == 16384);
            if (offset < 16384) {
                for (int i = 0; i < 16; i++) data.getAsJsonArray("targets").add(json(
                        "{\"block\":\"minecraft:stone\",\"dry_work_safe\":false}"));
                data.addProperty("matches_observed", 16);
            }
            return CompletableFuture.completedFuture(new ToolOutcome(true, "page", data));
        }, queue::add, () -> 0, () -> fail("unexpected cancellation"));
        var result = search.start();
        while (!queue.isEmpty()) queue.removeFirst().run();
        assertTrue(result.join().ok());
        assertEquals("minecraft:oak_log", result.join().data().getAsJsonArray("targets").get(0)
                .getAsJsonObject().get("block").getAsString());
    }
}
