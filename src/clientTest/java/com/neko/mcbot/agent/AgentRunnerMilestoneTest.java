package com.neko.mcbot.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.bridge.RunnerBridgeHarness;
import com.neko.mcbot.bridge.BridgeEventCapture;
import com.neko.mcbot.common.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class AgentRunnerMilestoneTest {
    private final OfflineAgentClient client = new OfflineAgentClient();
    private final AgentRunner runner = client.create();
    @AfterEach void close() { runner.close(); client.drain(); }
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private JsonObject body(int count) {
        var state = BoundedGatherTest.state(count);
        state.addProperty("body_id", "body-one"); state.addProperty("alive", true);
        state.addProperty("on_ground", true);
        return state;
    }
    private void reply(JsonObject data) {
        var receipt = new JsonObject();
        receipt.addProperty("seq", client.lastTool().num("seq", -1));
        receipt.addProperty("ok", true); receipt.addProperty("feedback", "actual"); receipt.add("data", data);
        runner.handleS2c(new Envelope("tool_result", receipt)); client.drain();
    }
    private void begin() {
        client.liveObservation = true;
        runner.start(); identity();
        runner.submitTask("完成长任务并汇报关键节点"); client.drain(); reply(body(5));
    }
    private void identity() {
        runner.handleS2c(new Envelope("companion_state",
                json("{\"companion\":\"testbot\",\"body_id\":\"body-one\",\"body_state\":\"alive\"}")));
        client.drain();
    }
    private String item() { return "{\"condition\":{\"type\":\"item\",\"item\":\"dirt\",\"count\":2,\"mode\":\"additional\"}}"; }
    private JsonObject crafted(String item) {
        return json("""
                {"query":false,"can_craft":true,"crafted_count":1,"output_inventory_count":1,
                "output_per_batch":{"item":"%s","count":1,"mining_tool":true,"max_damage":59},
                "body_delta":{"body_id":"body-one","same_world":true,"observation_known":true,
                "dimension_after":"minecraft:overworld"}}
                """.formatted(item));
    }
    private void until(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            client.drain();
            java.util.concurrent.locks.LockSupport.parkNanos(100000);
        }
        assertTrue(condition.getAsBoolean(), "program did not settle");
    }

    @Test void milestoneUsesFreshInventoryDeduplicatesAndDoesNotChangeFinalGoalOrTask() {
        begin();
        client.engine().tools(0, new ToolCall("goal", "set_goal", "{\"item\":\"dirt\",\"count\":10,\"lifecycle\":\"long_term\"}"));
        client.drain(); reply(body(5));
        long task = runner.currentTaskId();
        var report = runner.execute("report_milestone", item()); client.drain();
        assertFalse(report.isDone());
        assertEquals("status", client.lastTool().str("tool"));
        reply(body(6));
        assertFalse(report.join().ok());
        var actual = runner.execute("report_milestone", item()); client.drain(); reply(body(7));
        assertTrue(actual.join().ok(), actual.join().feedback());
        assertTrue(actual.join().data().has("milestone"));
        assertFalse(actual.join().data().get("goal_satisfied").getAsBoolean());
        long calls = client.toolCount();
        var duplicate = runner.execute("report_milestone", item()).join();
        assertTrue(duplicate.data().get("already_reported").getAsBoolean());
        assertEquals(calls, client.toolCount());
        assertEquals(task, runner.currentTaskId());
        assertEquals(1, client.sent.stream().filter(e -> e.kind().equals("task_begin")).count());
        assertEquals(0, client.sent.stream().filter(e -> e.kind().equals("task_end")).count());
        client.engine().tools(1, new ToolCall("query", "set_goal", "{\"operation\":\"query\"}"));
        client.drain(); reply(body(7));
        assertTrue(client.engine().histories.getLast().toString().contains("\"count\":10"));
    }

    @Test void luaCanReportAndContinueWithinTheSameProgram() {
        begin();
        var run = runner.execute("run_program", """
                {"code":"local r=mc.call('report_milestone',{condition={type='item',item='dirt',count=2,mode='additional'}}); return mc.call('inventory',{}).data"}
                """);
        until(() -> client.lastTool().str("tool").equals("status") && client.toolCount() >= 2);
        reply(body(7));
        until(() -> client.lastTool().str("tool").equals("inventory"));
        reply(body(7).getAsJsonObject("inventory"));
        until(run::isDone);
        assertTrue(run.join().ok(), run.join().feedback());
        assertTrue(run.join().feedback().contains("report_milestone"));
        assertNotEquals(0, runner.currentTaskId());
    }

    @Test void cancelledLateObservationCannotAnnounceAStageInAnotherTask() {
        begin();
        var report = runner.execute("report_milestone", item()); client.drain();
        long seq = client.lastTool().num("seq", -1);
        runner.cancelTask(0); client.drain();
        runner.submitTask("新的任务"); client.drain();
        var late = json("{\"ok\":true,\"feedback\":\"late\"}");
        late.addProperty("seq", seq); late.add("data", body(9));
        runner.handleS2c(new Envelope("tool_result", late)); client.drain();
        assertFalse(report.join().ok());
        assertTrue(runner.panelHistory().tasks().stream().flatMap(t -> runner.panelHistory().entries(t.id()).stream())
                .noneMatch(entry -> entry.text().contains("关键进展")));
    }

    @Test void wireCarriesOneStructuredMilestoneBeforeFinalResult() throws Exception {
        client.liveObservation = true; runner.start(); identity();
        try (var bridge = new RunnerBridgeHarness(runner, () -> runner, Runnable::run)) {
            long task = json(bridge.send("/v1/task", "{\"text\":\"有限长任务\",\"wait_s\":0}").body()).get("task_id").getAsLong();
            client.drain(); reply(body(5));
            var report = runner.execute("report_milestone", item()); client.drain(); reply(body(7));
            assertTrue(report.join().ok());
            runner.execute("report_milestone", item()).join();
            client.engine().reply(0, "普通回答"); client.drain(); reply(body(7));
            var frames = bridge.framesThroughTask(task).stream().map(f -> f.getAsJsonObject("data")).toList();
            var stages = frames.stream().filter(f -> f.has("milestone")).toList();
            assertEquals(1, stages.size());
            assertEquals(task, stages.getFirst().get("task_id").getAsLong());
            assertEquals("progress", stages.getFirst().get("ev").getAsString());
            assertTrue(stages.getFirst().getAsJsonObject("milestone").get("verified").getAsBoolean());
            assertEquals(bridge.status().get("session_id"), stages.getFirst().get("session_id"));
            assertEquals("done", frames.getLast().get("ev").getAsString());
        }
    }

    @Test void buildingStageChecksItsOwnWholeGridWhileKeepingTheLargerWorksite() {
        begin();
        String revision = "a".repeat(64);
        var condition = json("{\"type\":\"building\",\"target\":{\"anchor\":{\"x\":0,\"y\":89,\"z\":0},"
                + "\"cells\":[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"stone\"}]},\"expected_revision\":\"" + revision + "\"}");
        var args = new JsonObject(); args.add("condition", condition);
        var report = runner.execute("report_milestone", args.toString()); client.drain(); reply(body(5));
        assertEquals("build_diff", client.lastTool().str("tool"));
        assertTrue(client.lastTool().obj("args").get("verify_only").getAsBoolean());
        reply(json("{\"worksite_revision\":\"" + "b".repeat(64) + "\",\"target_revision\":\"" + revision
                + "\",\"total\":1,\"remaining\":0,\"counts\":{\"satisfied\":1,\"unknown\":0,\"unsupported\":0},"
                + "\"observation_complete\":true,\"spatial_stable\":true,\"budget_exhausted\":false,"
                + "\"goal_satisfied\":true,\"goal_observation_key\":\"" + "c".repeat(64) + "\"}"));
        assertTrue(report.join().ok(), report.join().feedback());
        assertEquals("building", report.join().data().getAsJsonObject("milestone").get("kind").getAsString());
        assertTrue(client.sent.stream().noneMatch(e -> e.kind().equals("tool_call") && e.str("tool").equals("build_site")));
    }

    @Test void schemaRejectsProseBeforeRequestingAnyObservation() {
        begin();
        long calls = client.toolCount();
        for (String args : java.util.List.of("{\"text\":\"我完成了\"}",
                "{\"condition\":{\"type\":\"item\",\"item\":\"dirt\",\"count\":1,\"label\":\"完成制作\"}}",
                "{\"condition\":{\"type\":\"wrong\",\"item\":\"dirt\",\"count\":1}}"))
            assertFalse(runner.execute("report_milestone", args).join().ok());
        assertEquals(calls, client.toolCount());
        var help = ToolCatalog.read("{\"names\":[\"report_milestone\"]}");
        assertTrue(help.ok()); assertTrue(help.feedback().contains("already_reported"));
    }

    @Test void nestedMilestoneReadsShareTheExplicitProgramPrimitiveBudget() {
        begin();
        var run = runner.execute("run_program", """
                {"code":"mc.call('report_milestone',{condition={type='item',item='dirt',count=2,mode='additional'}}); return mc.call('inventory',{}).data","max_calls":2}
                """);
        until(() -> client.toolCount() >= 2);
        reply(body(7));
        until(() -> client.lastTool().str("tool").equals("inventory"));
        reply(body(7).getAsJsonObject("inventory"));
        until(run::isDone);
        assertTrue(run.join().ok(), run.join().feedback());
        assertEquals(2, run.join().data().get("primitive_calls").getAsInt());
    }

    @Test void automaticCollectionUsesItsAlreadyVerifiedFinalObservationWithoutAnotherQuery() {
        begin();
        try (var events = new BridgeEventCapture()) {
            var gather = runner.execute("gather", """
                    {"item":"dirt","targets":["dirt"],"count":5,"mode":"total","scope":"subtask"}
                    """);
            client.drain();
            long calls = client.toolCount();
            reply(body(5));
            assertTrue(gather.isDone());
            assertTrue(gather.join().ok(), gather.join().feedback());
            assertTrue(gather.join().data().get("milestone_reported").getAsBoolean());
            assertEquals(calls, client.toolCount(), "must reuse the final inventory; no reporting query");
            assertEquals(1, events.events().stream().filter(e -> e.has("milestone")).count());
            assertNotEquals(0, runner.currentTaskId());
            var explicit = runner.execute("report_milestone",
                    "{\"condition\":{\"type\":\"item\",\"item\":\"dirt\",\"count\":5,\"mode\":\"total\"}}").join();
            assertTrue(explicit.data().get("already_reported").getAsBoolean());
            assertEquals(calls, client.toolCount());
        }
    }

    @Test void automaticCraftReportsInsideAnUnfinishedLuaProgramAndContinuesWithoutWaitingForTheHost() {
        begin();
        try (var events = new BridgeEventCapture()) {
            var run = runner.execute("run_program", """
                    {"code":"mc.call('craft',{item='wooden_pickaxe'}); return mc.call('wait',{seconds=60})","max_calls":2}
                    """);
            until(() -> client.lastTool().str("tool").equals("craft"));
            reply(crafted("minecraft:wooden_pickaxe"));
            until(() -> client.lastTool().str("tool").equals("wait"));
            assertFalse(run.isDone());
            var stages = events.events().stream().filter(e -> e.has("milestone")).toList();
            assertEquals(1, stages.size(), "does not wait for the script's final result");
            assertEquals("craft", stages.getFirst().getAsJsonObject("milestone")
                    .getAsJsonObject("evidence").get("source").getAsString());
            assertFalse(stages.getFirst().get("goal_satisfied").getAsBoolean());
            assertEquals(1, client.engine().responses.size(), "no extra model call");
            reply(new JsonObject());
            until(run::isDone);
            assertTrue(run.join().ok(), run.join().feedback());
            assertEquals(2, run.join().data().get("primitive_calls").getAsInt());
            assertEquals(1, client.sent.stream().filter(e -> e.kind().equals("task_begin")).count());
        }
    }

    @Test void repeatedCraftStageAndExplicitSameItemReportSpeakOnlyOnceButOtherToolsStillSpeak() {
        begin();
        try (var events = new BridgeEventCapture()) {
            for (String item : java.util.List.of("wooden_pickaxe", "wooden_pickaxe", "iron_pickaxe")) {
                var result = runner.execute("craft", "{\"item\":\"" + item + "\"}");
                client.drain(); reply(crafted("minecraft:" + item));
                assertTrue(result.join().ok());
                assertTrue(result.join().data().get("milestone_reported").getAsBoolean());
            }
            long calls = client.toolCount();
            var explicit = runner.execute("report_milestone",
                    "{\"condition\":{\"type\":\"item\",\"item\":\"iron_pickaxe\",\"count\":1,\"mode\":\"total\"}}").join();
            assertTrue(explicit.data().get("already_reported").getAsBoolean());
            assertEquals(calls, client.toolCount());
            assertEquals(2, events.events().stream().filter(e -> e.has("milestone")).count());
        }
    }

    @Test void automaticStagesReachTheRealSseAdapterBeforeTheFinalResult() throws Exception {
        client.liveObservation = true; runner.start(); identity();
        try (var bridge = new RunnerBridgeHarness(runner, () -> runner, Runnable::run)) {
            long task = json(bridge.send("/v1/task", "{\"text\":\"collect then make equipment\",\"wait_s\":0}")
                    .body()).get("task_id").getAsLong();
            client.drain(); reply(body(5));
            var gather = runner.execute("gather", """
                    {"item":"dirt","targets":["dirt"],"count":5,"mode":"total","scope":"subtask"}
                    """);
            client.drain(); reply(body(5));
            assertTrue(gather.join().ok());
            var craft = runner.execute("craft", "{\"item\":\"wooden_pickaxe\"}");
            client.drain(); reply(crafted("minecraft:wooden_pickaxe"));
            assertTrue(craft.join().ok());
            client.engine().reply(0, "actual final result"); client.drain(); reply(body(5));
            var frames = bridge.framesThroughTask(task).stream().map(f -> f.getAsJsonObject("data")).toList();
            var stages = frames.stream().filter(f -> f.has("milestone")).toList();
            assertEquals(2, stages.size());
            assertEquals("gather", stages.get(0).getAsJsonObject("milestone").getAsJsonObject("evidence")
                    .get("source").getAsString());
            assertEquals("craft", stages.get(1).getAsJsonObject("milestone").getAsJsonObject("evidence")
                    .get("source").getAsString());
            assertEquals("done", frames.getLast().get("ev").getAsString());
            var fixture = new com.google.gson.JsonArray();
            stages.forEach(fixture::add);
            java.nio.file.Files.writeString(java.nio.file.Path.of("build/automatic-milestone-wire-20261025.json"),
                    fixture.toString(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test void malformedQueryFailureAndCancelledLateCraftDoNotSpeakOrChangeTheActionResult() {
        begin();
        try (var events = new BridgeEventCapture()) {
            var missing = runner.execute("craft", "{\"item\":\"wooden_pickaxe\"}"); client.drain();
            reply(new JsonObject());
            assertTrue(missing.join().ok(), "missing optional reporting evidence cannot fail a real action");
            var query = runner.execute("craft", "{\"item\":\"wooden_pickaxe\",\"query\":true}"); client.drain();
            var queried = crafted("minecraft:wooden_pickaxe"); queried.addProperty("query", true);
            reply(queried);
            assertTrue(query.join().ok());
            var late = runner.execute("craft", "{\"item\":\"wooden_pickaxe\"}"); client.drain();
            long seq = client.lastTool().num("seq", -1);
            runner.cancelTask(0); client.drain();
            var envelope = json("{\"ok\":true,\"feedback\":\"late successful action\"}");
            envelope.addProperty("seq", seq); envelope.add("data", crafted("minecraft:wooden_pickaxe"));
            runner.handleS2c(new Envelope("tool_result", envelope)); client.drain();
            assertTrue(late.isDone());
            assertEquals(0, events.events().stream().filter(e -> e.has("milestone")).count());
        }
    }
}
