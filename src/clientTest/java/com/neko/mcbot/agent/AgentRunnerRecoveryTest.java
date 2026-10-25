package com.neko.mcbot.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.loop.GoalCheckpoint;
import com.neko.mcbot.agentcore.loop.GoalJournal;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.common.Envelope;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunnerRecoveryTest {
    @TempDir Path root;
    private JsonObject body(int count) {
        var data = BoundedGatherTest.state(count);
        int id = 1;
        for (String field : java.util.List.of("world_id", "owner_uuid", "companion_uuid", "body_continuity_id"))
            data.addProperty(field, "10000000-0000-0000-0000-%012d".formatted(id++));
        data.addProperty("body_id", "body-one"); data.addProperty("alive", true);
        data.addProperty("permission_mode", "confirm"); data.addProperty("recovery_spatial_known", true);
        data.add("recovery_spatial", JsonParser.parseString("""
                {"work":[],"trail":{"points":[],"scopes":[],"edits":[],"sequence":0,"truncated":false,"gap":false},
                "site":{"active":false}}
                """));
        return data;
    }
    private OfflineAgentClient client() {
        var client = new OfflineAgentClient();
        client.journal = new GoalJournal(root); client.liveObservation = true;
        return client;
    }
    private void reply(OfflineAgentClient client, AgentRunner runner, JsonObject data) {
        replyTool(client, runner, "status", data);
    }
    private void replyTool(OfflineAgentClient client, AgentRunner runner, String tool, JsonObject data) {
        assertEquals(tool, client.lastTool().str("tool"));
        var receipt = new JsonObject();
        receipt.addProperty("seq", client.lastTool().num("seq", -1));
        receipt.addProperty("ok", true); receipt.addProperty("feedback", "actual"); receipt.add("data", data);
        runner.handleS2c(new Envelope("tool_result", receipt)); client.drain();
    }
    private AgentRunner seed(OfflineAgentClient client) {
        var runner = client.create(); runner.start(); runner.submitTask("采集三份额外泥土"); client.drain();
        reply(client, runner, body(5));
        client.engine().tools(0, new ToolCall("goal", "set_goal",
                "{\"item\":\"dirt\",\"count\":3,\"lifecycle\":\"long_term\"}")); client.drain();
        reply(client, runner, body(6));
        assertEquals(2, client.engine().responses.size());
        assertNotNull(client.journal.load(GoalCheckpoint.scope(body(5))));
        return runner;
    }
    @Test void explicitRecoveryWithoutAStoreNeverBecomesANewModelDirective() {
        var client = new OfflineAgentClient();
        var runner = client.create();
        try {
            runner.start();
            runner.submitTask("恢复目标");
            client.drain();
            assertEquals(0, client.engine().responses.size(),
                    "recovery must validate a durable goal before asking the model");
            assertEquals(0, runner.currentTaskId());
        } finally {
            runner.close();
            client.drain();
        }
    }

    @Test void milestoneDeduplicationSurvivesExplicitRecoveryWithoutReannouncingOldAchievements() {
        var oldClient = client(); var old = seed(oldClient);
        String args = "{\"condition\":{\"type\":\"item\",\"item\":\"dirt\",\"count\":2,\"mode\":\"additional\"}}";
        old.handleS2c(new Envelope("companion_state", JsonParser.parseString(
                "{\"companion\":\"testbot\",\"body_id\":\"body-one\",\"body_state\":\"alive\"}").getAsJsonObject()));
        oldClient.drain();
        oldClient.engine().tools(1, new ToolCall("milestone", "report_milestone", args)); oldClient.drain();
        reply(oldClient, old, body(7));
        reply(oldClient, old, body(7));
        var saved = oldClient.journal.load(GoalCheckpoint.scope(body(5)));
        assertEquals(1, saved.getAsJsonObject("host").getAsJsonArray("milestones").size());
        old.disconnect(); oldClient.drain();
        var next = client(); var runner = next.create();
        try {
            runner.start(); runner.submitTask("恢复目标"); next.drain();
            var fresh = body(7); fresh.addProperty("body_id", "new-body");
            reply(next, runner, fresh); reply(next, runner, fresh); reply(next, runner, fresh);
            long calls = next.toolCount();
            var duplicate = runner.execute("report_milestone", args).join();
            assertTrue(duplicate.ok(), duplicate.feedback());
            assertTrue(duplicate.data().get("already_reported").getAsBoolean());
            assertEquals(calls, next.toolCount());
            assertNotEquals(0, runner.currentTaskId());
        } finally { runner.close(); next.drain(); }
    }

    @Test void automaticallyReportedEquipmentKeepsItsDeduplicationAcrossExplicitRecovery() {
        var first = client(); var old = seed(first);
        old.handleS2c(new Envelope("companion_state", JsonParser.parseString(
                "{\"companion\":\"testbot\",\"body_id\":\"body-one\",\"body_state\":\"alive\"}").getAsJsonObject()));
        first.drain();
        var crafted = JsonParser.parseString("""
                {"query":false,"can_craft":true,"crafted_count":1,"output_inventory_count":1,
                 "output_per_batch":{"item":"minecraft:wooden_pickaxe","count":1,"mining_tool":true,"max_damage":59},
                 "body_delta":{"body_id":"body-one","same_world":true,"observation_known":true,
                 "dimension_after":"minecraft:overworld"}}
                """).getAsJsonObject();
        first.engine().tools(1, new ToolCall("craft", "craft", "{\"item\":\"wooden_pickaxe\"}"));
        first.drain(); replyTool(first, old, "craft", crafted); reply(first, old, body(7));
        var saved = first.journal.load(GoalCheckpoint.scope(body(5)));
        assertEquals(2, saved.getAsJsonObject("host").getAsJsonArray("milestones").size());
        old.disconnect(); first.drain();
        var next = client(); var runner = next.create();
        try {
            runner.start(); runner.submitTask("恢复目标"); next.drain();
            var fresh = body(7); fresh.addProperty("body_id", "new-body");
            reply(next, runner, fresh); reply(next, runner, fresh); reply(next, runner, fresh);
            try (var events = new com.neko.mcbot.bridge.BridgeEventCapture()) {
                var repeated = runner.execute("craft", "{\"item\":\"wooden_pickaxe\"}"); next.drain();
                crafted.getAsJsonObject("body_delta").addProperty("body_id", "new-body");
                replyTool(next, runner, "craft", crafted);
                assertTrue(repeated.join().data().get("milestone_reported").getAsBoolean());
                assertEquals(0, events.events().stream().filter(e -> e.has("milestone")).count());
            }
        } finally { runner.close(); next.drain(); }
    }

    @Test void realJournalAndRunnerRejoinRequireOwnerIntentThenFreshPermissionAndOriginalBaseline() {
        var oldClient = client(); var old = seed(oldClient);
        String scope = GoalCheckpoint.scope(body(5));
        var saved = oldClient.journal.load(scope);
        old.disconnect(); oldClient.drain();
        var client = client(); var runner = client.create();
        try {
            runner.start();
            assertTrue(client.engine().responses.isEmpty(), "joining must not wake a saved goal");
            runner.submitTask("恢复目标"); client.drain();
            var fresh = body(6); fresh.addProperty("body_id", "body-after-rejoin");
            reply(client, runner, fresh);
            var begin = client.sent.stream().filter(e -> e.kind().equals("task_begin")).findFirst().orElseThrow();
            assertTrue(begin.body().has("recovery_spatial"));
            assertTrue(begin.num("task_id", 0) > saved.getAsJsonObject("core").get("task_id").getAsLong());
            assertEquals("body-after-rejoin", begin.str("body_id"));
            assertFalse(begin.bool("autonomous"));
            assertTrue(client.engine().responses.isEmpty());
            reply(client, runner, fresh);
            assertTrue(client.engine().responses.isEmpty(), "the normal next-step observation remains mandatory");
            reply(client, runner, fresh);
            assertEquals(1, client.engine().responses.size());
            assertTrue(client.engine().histories.getFirst().toString().contains("任务开始基线=5"));
            assertEquals(saved.getAsJsonObject("core").get("goal_id"),
                    client.journal.load(scope).getAsJsonObject("core").get("goal_id"));
            client.engine().reply(0, "完成"); client.drain();
            var complete = body(8); complete.addProperty("body_id", "body-after-rejoin");
            reply(client, runner, complete);
            assertEquals(0, runner.currentTaskId());
            assertNull(client.journal.load(scope));
        } finally { runner.close(); client.drain(); }
    }
    @Test void anUnsettledDirectOrLuaDispatchBlocksRecoveryAcrossDisconnect() {
        for (String tool : java.util.List.of("break_block", "run_program")) {
            var client = client(); var runner = seed(client);
            runner.execute(tool, tool.equals("break_block") ? "{\"x\":1,\"y\":90,\"z\":0}"
                    : "{\"code\":\"return mc.call('wait',{seconds=1})\"}");
            client.drain(); runner.disconnect(); client.drain();
            assertThrows(RuntimeException.class, () -> new GoalJournal(root).load(GoalCheckpoint.scope(body(5))));
            client.journal.clear(GoalCheckpoint.scope(body(5)));
        }
    }
    @Test void stoppedGoalNeverReappearsAfterRestart() {
        var client = client(); var runner = seed(client);
        runner.cancelTask(0); client.drain(); runner.disconnect(); client.drain();
        assertNull(new GoalJournal(root).load(GoalCheckpoint.scope(body(5))));
    }
    @Test void changedContinuityOrPermissionPreventsAnyNewModelRequest() {
        for (boolean continuity : new boolean[]{true, false}) {
            var oldClient = client(); var old = seed(oldClient); old.disconnect(); oldClient.drain();
            var client = client(); var runner = client.create();
            try {
                runner.start(); runner.submitTask("恢复目标"); client.drain();
                var state = body(6);
                if (continuity) state.addProperty("body_continuity_id", "20000000-0000-0000-0000-000000000001");
                reply(client, runner, state);
                if (!continuity) { state.addProperty("permission_mode", "none"); reply(client, runner, state); }
                assertTrue(client.engine().responses.isEmpty());
                assertEquals(0, runner.currentTaskId());
            } finally { runner.close(); client.drain(); }
        }
    }
    @Test void cancellingTheRecoveryPreflightRejectsLateFactsAndClearsTheRecord() {
        var oldClient = client(); var old = seed(oldClient); old.disconnect(); oldClient.drain();
        var client = client(); var runner = client.create();
        try {
            runner.start(); runner.submitTask("恢复目标"); client.drain(); reply(client, runner, body(6));
            var preflight = client.lastTool();
            runner.cancelTask(0); client.drain();
            var data = new JsonObject(); data.addProperty("seq", preflight.num("seq", -1));
            data.addProperty("ok", true); data.addProperty("feedback", "late"); data.add("data", body(8));
            runner.handleS2c(new Envelope("tool_result", data)); client.drain();
            assertTrue(client.engine().responses.isEmpty());
            assertNull(client.journal.load(GoalCheckpoint.scope(body(5))));
        } finally { runner.close(); client.drain(); }
    }
    @Test void statusAndDiscardCommandsAreReadOnlyAndDoNotStartTheModel() {
        var oldClient = client(); var old = seed(oldClient); old.disconnect(); oldClient.drain();
        var client = client(); var runner = client.create();
        try {
            runner.start();
            runner.submitTask("目标状态"); client.drain(); reply(client, runner, body(6));
            assertNotNull(client.journal.load(GoalCheckpoint.scope(body(5))));
            runner.submitTask("放弃目标"); client.drain(); reply(client, runner, body(6));
            assertNull(client.journal.load(GoalCheckpoint.scope(body(5))));
            assertTrue(client.engine().responses.isEmpty());
            assertEquals(0, client.sent.stream().filter(e -> e.kind().equals("task_begin")).count());
        } finally { runner.close(); client.drain(); }
    }
    private JsonObject buildingEvidence(String revision, boolean complete) {
        var data = new JsonObject();
        data.addProperty("target_revision", revision);
        data.addProperty("total", 20); data.addProperty("remaining", complete ? 0 : 20);
        var counts = new JsonObject();
        counts.addProperty("satisfied", complete ? 20 : 0); counts.addProperty("not_assessed", complete ? 0 : 20);
        counts.addProperty("unknown", 0); counts.addProperty("unsupported", 0);
        data.add("counts", counts);
        data.addProperty("observation_complete", true); data.addProperty("spatial_stable", true);
        data.addProperty("budget_exhausted", false); data.addProperty("goal_satisfied", complete);
        data.addProperty("goal_observation_key", (complete ? "b" : "a").repeat(64));
        return data;
    }
    private AgentRunner stoppedBuilding(OfflineAgentClient client, String revision) {
        var runner = client.create();
        runner.start(); runner.submitTask("完成这栋住宅"); client.drain();
        reply(client,runner,body(0));
        client.engine().tools(0,new ToolCall("goal","set_goal","{\"type\":\"building\",\"target\":{\"draft_revision\":\""
                + revision + "\"},\"expected_revision\":\"" + revision + "\",\"lifecycle\":\"long_term\"}"));
        client.drain(); reply(client,runner,body(0));
        replyTool(client,runner,"build_diff",buildingEvidence(revision,false));
        reply(client,runner,body(0));
        runner.cancelTask(0); client.drain();
        return runner;
    }
    @Test void changedIncarnationOrRejectedServerHandoffCannotWakeTheModel() {
        for (boolean changedBody : new boolean[]{true,false}) {
            var client = client(); var runner = stoppedBuilding(client,"e".repeat(64));
            try {
                int requests = client.engine().responses.size();
                long begins = client.sent.stream().filter(e->e.kind().equals("task_begin")).count();
                runner.submitTask("继续"); client.drain();
                var fresh = body(0);
                if (changedBody) fresh.addProperty("body_id","replacement-body");
                reply(client,runner,fresh);
                if (!changedBody) {
                    var rejected = body(0); rejected.addProperty("permission_mode","none");
                    reply(client,runner,rejected);
                } else assertEquals(begins,client.sent.stream().filter(e->e.kind().equals("task_begin")).count());
                assertEquals(requests,client.engine().responses.size());
                assertEquals(0,runner.currentTaskId());
            } finally { runner.close(); client.drain(); }
        }
    }
    @Test void stoppedBuildingStatusIsDataOnlyAndCancellationInvalidatesALateContinuePreflight() {
        var client = client(); var runner = stoppedBuilding(client,"e".repeat(64));
        try {
            int requests = client.engine().responses.size();
            long calls = client.toolCount();
            runner.submitTask("目标状态"); client.drain();
            assertEquals(calls,client.toolCount()); assertEquals(requests,client.engine().responses.size());
            runner.submitTask("继续"); client.drain();
            runner.cancelTask(0); client.drain();
            reply(client,runner,body(0));
            assertEquals(0,runner.currentTaskId()); assertEquals(requests,client.engine().responses.size());
            runner.submitTask("放弃目标"); client.drain();
            reply(client,runner,body(0));
            assertNull(client.journal.load(GoalCheckpoint.scope(body(0))));
            runner.submitTask("查看附近情况"); client.drain(); reply(client,runner,body(0));
            var begin = client.sent.stream().filter(e->e.kind().equals("task_begin")).toList().getLast();
            assertFalse(begin.body().has("continue_construction"),"discarded work never leaks into an unrelated task");
        } finally { runner.close(); client.drain(); }
    }
    @Test void ownerContinueAfterCancellingBuildingRecoversTheExactGoalWithoutReplayingThePendingMove() {
        var client = client(); var runner = client.create();
        String revision = "e".repeat(64);
        String target = "{\"draft_revision\":\"" + revision + "\"}";
        try {
            runner.start(); runner.submitTask("完成这栋住宅"); client.drain();
            reply(client,runner,body(0));
            client.engine().tools(0,new ToolCall("goal","set_goal","{\"type\":\"building\",\"target\":" + target
                    + ",\"expected_revision\":\"" + revision + "\",\"lifecycle\":\"long_term\"}"));
            client.drain(); reply(client,runner,body(0));
            replyTool(client,runner,"build_diff",buildingEvidence(revision,false));
            reply(client,runner,body(0));
            long previousTask = runner.currentTaskId();
            var saved = client.journal.load(GoalCheckpoint.scope(body(0))).getAsJsonObject("core");
            runner.execute("move_to","{\"x\":448,\"y\":69,\"z\":-293}"); client.drain();
            long moves = client.sent.stream().filter(e -> e.kind().equals("tool_call") && e.str("tool").equals("move_to")).count();
            runner.cancelTask(0); client.drain(); runner.cancelTask(0); client.drain();
            assertEquals(0,runner.currentTaskId());
            assertNull(client.journal.load(GoalCheckpoint.scope(body(0))),"cancel still clears durable execution");
            int requests = client.engine().responses.size();
            runner.submitTask("继续"); client.drain();
            assertEquals(requests,client.engine().responses.size(),"continue first checks current identity");
            reply(client,runner,body(0));
            var begin = client.sent.stream().filter(e -> e.kind().equals("task_begin")).toList().getLast();
            var continuation = begin.body().getAsJsonObject("continue_construction");
            assertNotNull(continuation); assertFalse(begin.body().has("recovery_spatial"));
            assertEquals(previousTask,continuation.get("previous_task_id").getAsLong());
            assertEquals(revision,continuation.get("target_revision").getAsString());
            assertTrue(runner.currentTaskId() > previousTask);
            reply(client,runner,body(0));
            assertEquals(JsonParser.parseString(target),client.lastTool().obj("args").get("target"));
            replyTool(client,runner,"build_diff",buildingEvidence(revision,false));
            reply(client,runner,body(0));
            assertEquals(requests+1,client.engine().responses.size());
            var resumed = client.journal.load(GoalCheckpoint.scope(body(0))).getAsJsonObject("core");
            assertEquals(saved.get("goal_id"),resumed.get("goal_id"));
            assertTrue(resumed.get("steps").getAsInt()>saved.get("steps").getAsInt());
            assertTrue(client.engine().histories.getLast().toString().contains(revision));
            assertEquals(moves,client.sent.stream().filter(e -> e.kind().equals("tool_call")
                    && e.str("tool").equals("move_to")).count(),"never replay the old pending movement");
            client.engine().tools(requests,new ToolCall("query","set_goal","{\"operation\":\"query\"}"));
            client.drain(); reply(client,runner,body(0));
            assertTrue(client.engine().histories.getLast().toString().contains("\"registered\":true"));
        } finally { runner.close(); client.drain(); }
    }

    @Test void recoveredBuildingQueriesOriginalRevisionAndRejectsChangedEvidenceBeforeTheModel() {
        String revision = "a".repeat(64);
        String target = "{\"instance_id\":\"11000000-0000-0000-0000-000000000001\",\"instance_revision\":2}";
        for (boolean changed : new boolean[]{false, true}) {
            var oldClient = client(); var old = oldClient.create();
            old.start(); old.submitTask("完成原建筑目标"); oldClient.drain();
            reply(oldClient, old, body(0));
            oldClient.engine().tools(0, new ToolCall("goal", "set_goal",
                    "{\"type\":\"building\",\"target\":" + target + ",\"expected_revision\":\"" + revision
                            + "\",\"lifecycle\":\"long_term\"}"));
            oldClient.drain(); reply(oldClient, old, body(0));
            replyTool(oldClient, old, "build_diff", buildingEvidence(revision, false));
            reply(oldClient, old, body(0));
            old.disconnect(); oldClient.drain();
            var client = client(); var runner = client.create();
            try {
                runner.start(); runner.submitTask("恢复目标"); client.drain();
                var fresh = body(0); fresh.addProperty("body_id", "body-after-rejoin");
                reply(client, runner, fresh); reply(client, runner, fresh);
                var args = client.lastTool().obj("args");
                assertEquals(JsonParser.parseString(target), args.get("target"));
                assertEquals(revision, args.get("expected_revision").getAsString());
                assertTrue(args.get("verify_only").getAsBoolean());
                replyTool(client, runner, "build_diff", buildingEvidence(changed ? "c".repeat(64) : revision, true));
                assertEquals(0, runner.currentTaskId());
                assertTrue(client.engine().responses.isEmpty());
                assertEquals(changed ? "failed" : "completed", runner.panelHistory().tasks().stream()
                        .filter(task -> task.title().equals("完成原建筑目标")).findFirst().orElseThrow().status());
            } finally { runner.close(); client.drain(); }
        }
    }
}
