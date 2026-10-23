package com.neko.mcbot.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.cfg.ClientConfig;
import com.neko.mcbot.common.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunnerTaskBubbleTest {
    private final OfflineAgentClient client = new OfflineAgentClient();
    private final AgentRunner runner = client.create();
    @AfterEach void close() { runner.close(); client.drain(); }
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private void body(UUID id) {
        var data = new JsonObject();
        data.addProperty("companion", "same_name");
        if (id != null) data.addProperty("companion_uuid", id.toString());
        runner.handleS2c(new Envelope("companion_state", data));
    }
    private long start() {
        body(UUID.randomUUID());
        runner.start();
        long task = runner.submitTask("采集一些石头");
        client.drain();
        return task;
    }
    private void receipt(JsonObject data) {
        var result = new JsonObject();
        result.addProperty("seq", client.lastTool().num("seq", -1));
        result.addProperty("ok", true);
        result.addProperty("feedback", "actual");
        result.add("data", data);
        runner.handleS2c(new Envelope("tool_result", result));
        client.drain();
    }
    @Test void sentPrimitiveRemainsVisibleAcrossAcceptanceAndIgnoresOtherInspectionsAndLateEvents() {
        long task = start();
        assertEquals("等待下一步", runner.taskBubbleSnapshot().action());
        client.engine().tools(0, new ToolCall("mine", "break_block", "{\"x\":2,\"y\":90,\"z\":0}"));
        client.drain();
        long seq = client.lastTool().num("seq", -1);
        assertEquals("正在挖掘", runner.taskBubbleSnapshot().action());
        var ack = json("{\"job_id\":\"mine\",\"cap_ticks\":1200}");
        ack.addProperty("seq", seq);
        runner.handleS2c(new Envelope("job_ack", ack)); client.drain();
        assertEquals("正在挖掘", runner.taskBubbleSnapshot().action());
        runner.inspect("status"); client.drain();
        receipt(new JsonObject());
        assertEquals(task, runner.taskBubbleSnapshot().taskId());
        assertEquals("正在挖掘", runner.taskBubbleSnapshot().action());
        var terminal = json("{\"job_id\":\"mine\",\"phase\":\"done\",\"text\":\"actual\"}");
        terminal.addProperty("seq", seq + 1);
        runner.handleS2c(new Envelope("job_event", terminal)); client.drain();
        assertEquals("正在挖掘", runner.taskBubbleSnapshot().action());
        terminal.addProperty("seq", seq);
        runner.handleS2c(new Envelope("job_event", terminal)); client.drain();
        assertEquals("等待下一步", runner.taskBubbleSnapshot().action());
        client.engine().reply(1, "done"); client.drain();
        assertEquals(TaskBubbleState.Tone.COMPLETED, runner.taskBubbleSnapshot().tone());
        client.now += 6000;
        assertNull(runner.taskBubbleSnapshot());
    }
    @Test void questionAndDisplayPreferenceChangesPreserveExecutionAndSaveFailurePreservesOldPreference() {
        long task = start();
        client.engine().tools(0, new ToolCall("ask", "ask_owner", "{\"text\":\"往哪走？\"}")); client.drain();
        var question = runner.questionSnapshot();
        assertEquals(TaskBubbleState.Tone.QUESTION, runner.taskBubbleSnapshot().tone());
        var prefs = runner.config().preferences;
        var hidden = runner.config().withPreferences(new ClientConfig.Preferences(prefs.contextWindow(),
                prefs.compactThreshold(), prefs.recentBudget(), prefs.autoCompact(), prefs.foldTools(), prefs.dark(), false));
        client.failSave = true;
        assertThrows(RuntimeException.class, () -> runner.reconfigure(hidden));
        assertTrue(runner.config().preferences.showHud());
        client.failSave = false;
        runner.reconfigure(hidden);
        assertFalse(runner.config().preferences.showHud());
        assertEquals(task, runner.currentTaskId());
        assertEquals(question.id(), runner.questionSnapshot().id());
        assertEquals(1, client.engines.size());
        assertEquals(0, client.cancelCount());
        runner.cancelTask(0); client.drain();
        assertEquals(TaskBubbleState.Tone.CANCELLED, runner.taskBubbleSnapshot().tone());
        assertFalse(runner.answerFromPanel(question.id(), "late"));
        client.now += 4000;
        assertNull(runner.taskBubbleSnapshot());
    }
    @Test void sameNameNewUuidAndMissingIdentityInvalidateOldDisplayAndBodyRead() {
        start();
        UUID original = runner.companionUuid();
        runner.refreshCompanion(); client.drain();
        body(UUID.randomUUID());
        receipt(json("{\"hp\":19,\"dimension\":\"minecraft:overworld\"}"));
        assertNotEquals(original, runner.companionUuid());
        assertNull(runner.taskBubbleSnapshot());
        assertTrue(runner.companionSnapshot().isEmpty());
        body(null);
        assertNull(runner.companionUuid());
        body(original);
        var malformed = json("{\"companion\":\"same_name\",\"companion_uuid\":\"1-2-3-4-5\"}");
        runner.handleS2c(new Envelope("companion_state", malformed));
        assertNull(runner.companionUuid());
        body(original);
        malformed.addProperty("companion_uuid", 2);
        runner.handleS2c(new Envelope("companion_state", malformed));
        assertNull(runner.companionUuid());
    }
    @Test void repeatedIdentitySyncPreservesTaskButReloadAndCloseClearDisplay() {
        start();
        body(runner.companionUuid());
        assertNotNull(runner.taskBubbleSnapshot());
        client.engine().reply(0, "done"); client.drain();
        assertNotNull(runner.taskBubbleSnapshot());
        runner.reconfigure(OfflineAgentClient.NEXT); client.drain();
        assertNull(runner.taskBubbleSnapshot());
        runner.submitTask("next"); client.drain();
        assertNotNull(runner.taskBubbleSnapshot());
        runner.close(); client.drain();
        assertNull(runner.taskBubbleSnapshot());
    }
    @Test void gatherUsesVerifiedSubtaskInventoryAndNeverCountsBlockRemovalAsPickup() {
        client.liveObservation = true;
        start();
        receipt(BoundedGatherTest.state(0));
        client.engine().tools(0, new ToolCall("goal", "set_goal", "{\"item\":\"diamond\",\"count\":1}"));
        client.drain(); receipt(BoundedGatherTest.state(0));
        runner.execute("gather", "{\"item\":\"dirt\",\"targets\":[\"dirt\"],\"count\":4,\"scope\":\"subtask\"}");
        assertNull(runner.taskBubbleSnapshot().progress(), "no fresh subtask baseline yet");
        client.drain(); receipt(BoundedGatherTest.state(2));
        assertEquals("minecraft:dirt", runner.taskBubbleSnapshot().progress().item());
        assertEquals(2, runner.taskBubbleSnapshot().progress().current());
        receipt(json("{\"item_targets\":[]}"));
        var targets = new com.google.gson.JsonArray(); targets.add(BoundedGatherTest.block(true));
        var data = new JsonObject(); data.add("targets", targets);
        receipt(data);
        assertEquals("正在挖掘", runner.taskBubbleSnapshot().action());
        receipt(json("{\"removed\":true,\"drop_count\":64}"));
        assertEquals(2, runner.taskBubbleSnapshot().progress().current());
        receipt(BoundedGatherTest.state(3));
        assertEquals(3, runner.taskBubbleSnapshot().progress().current());
        assertEquals(4, runner.taskBubbleSnapshot().progress().target());
    }
    @Test void lateResultsAndQueuedTaskCancellationDoNotChangeTheNewTask() {
        start();
        var future = client.engine().responses.getFirst();
        runner.cancelTask(0); client.drain();
        long next = runner.submitTask("next"); client.drain();
        long queued = runner.submitTask("queued"); client.drain();
        runner.cancelTask(queued); client.drain();
        future.complete(new com.neko.mcbot.agentcore.llm.AssistantTurn("late", java.util.List.of(), 0, 0, -1, "stop"));
        client.drain();
        assertEquals(next, runner.taskBubbleSnapshot().taskId());
        assertEquals(TaskBubbleState.Tone.WORKING, runner.taskBubbleSnapshot().tone());
    }
    @Test void timedOutPrimitiveStopsClaimingToBeInFlight() {
        start();
        client.engine().tools(0, new ToolCall("mine", "break_block", "{\"x\":2,\"y\":90,\"z\":0}"));
        client.drain();
        assertEquals("正在挖掘", runner.taskBubbleSnapshot().action());
        client.now += 90001; runner.tick(); client.drain();
        assertEquals("等待下一步", runner.taskBubbleSnapshot().action());
    }
    @Test void modelFailureShowsRealFailureForTenSeconds() {
        start();
        client.engine().responses.getFirst().completeExceptionally(new IllegalStateException("offline failure"));
        client.drain();
        assertEquals(TaskBubbleState.Tone.FAILED, runner.taskBubbleSnapshot().tone());
        assertEquals("任务未完成", runner.taskBubbleSnapshot().action());
        client.now += 9999;
        assertNotNull(runner.taskBubbleSnapshot());
        client.now++;
        assertNull(runner.taskBubbleSnapshot());
    }

    @Test void modelWaitIsExplicitInPanelAndBubbleWhilePrimitiveActivityKeepsPriority() {
        start();
        assertTrue(runner.taskState().matches("等待模型响应 · \\d+ 秒"));
        assertTrue(runner.taskBubbleSnapshot().detail().matches("等待模型响应 · \\d+ 秒"));
        client.engine().early(0, new ToolCall("mine", "break_block", "{\"x\":2,\"y\":90,\"z\":0}"));
        client.drain();
        assertEquals("正在挖掘", runner.taskBubbleSnapshot().action());
        assertFalse(runner.taskBubbleSnapshot().detail().contains("等待模型"));
    }
}
