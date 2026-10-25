package com.neko.mcbot.agent;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.common.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class AgentRunnerMultiGoalTest {
    private static final String REVISION="a".repeat(64);
    private static final String TARGET="{\"anchor\":{\"x\":2,\"y\":90,\"z\":0},\"fill\":{"
            + "\"from\":{\"x\":0,\"y\":0,\"z\":0},\"to\":{\"x\":19,\"y\":0,\"z\":0},\"block\":\"stone\"}}";
    private final OfflineAgentClient client=new OfflineAgentClient();
    private final AgentRunner runner=client.create();
    @AfterEach void close() { runner.close(); client.drain(); }
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private JsonObject body() {
        var data=BoundedGatherTest.state(0);
        data.addProperty("body_id","body-one"); data.addProperty("alive",true);
        return data;
    }
    private void reply(JsonObject data) { reply(true,"actual",data); }
    private void reply(boolean ok,String feedback,JsonObject data) {
        var receipt=new JsonObject();
        receipt.addProperty("seq",client.lastTool().num("seq",-1));
        receipt.addProperty("ok",ok); receipt.addProperty("feedback",feedback); receipt.add("data",data);
        runner.handleS2c(new Envelope("tool_result",receipt)); client.drain();
    }
    private void status() {
        assertEquals("status",client.lastTool().str("tool"));
        reply(body());
    }
    private void until(java.util.function.BooleanSupplier condition) {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean() && System.nanoTime()<deadline) {
            client.drain();
            java.util.concurrent.locks.LockSupport.parkNanos(100000);
        }
        assertTrue(condition.getAsBoolean(),"runner did not settle");
    }
    private void begin() {
        client.liveObservation=true;
        runner.start(); runner.submitTask("按现场条件完成有限目标"); client.drain(); status();
    }
    private void register(String goal,JsonObject evidence,String query) {
        begin();
        client.engine().tools(0,new ToolCall("goal","set_goal",goal)); client.drain();
        status();
        assertEquals(query,client.lastTool().str("tool"));
        reply(evidence);
        status();
        assertEquals(2,client.engine().responses.size());
    }
    private JsonObject buildEvidence(int satisfied) {
        return json("{\"target_revision\":\"" + REVISION + "\",\"total\":20,\"remaining\":" + (20-satisfied)
                + ",\"counts\":{\"satisfied\":" + satisfied + ",\"not_assessed\":" + (20-satisfied)
                + ",\"unknown\":0,\"unsupported\":0},\"observation_complete\":true,\"spatial_stable\":true,"
                + "\"budget_exhausted\":false,\"goal_satisfied\":" + (satisfied==20)
                + ",\"goal_observation_key\":\"" + String.format("%064x",satisfied) + "\"}");
    }
    private String buildingGoal() {
        return "{\"type\":\"building\",\"target\":" + TARGET + ",\"expected_revision\":\"" + REVISION
                + "\",\"lifecycle\":\"long_term\"}";
    }
    private JsonObject container(int count) {
        return json("{\"container_id\":\"container-one\",\"container\":true,\"container_identity_known\":true,"
                + "\"contents_complete\":true,\"dimension\":\"minecraft:overworld\",\"item\":\"minecraft:diamond\","
                + "\"x\":2,\"y\":90,\"z\":0,\"slot_count\":27,\"item_total\":" + count + "}");
    }
    private void registerDelivery(int count) {
        register("{\"type\":\"delivery\",\"container\":{\"x\":2,\"y\":90,\"z\":0},"
                + "\"item\":\"diamond\",\"count\":3,\"lifecycle\":\"long_term\"}",container(count),"inspect_block");
    }
    private JsonObject moved(String dir,int before,int after) {
        var data=container(after);
        data.addProperty("transfer_evidence_known",true); data.addProperty("dir",dir);
        data.addProperty("container_count_before",before); data.addProperty("container_count_after",after);
        data.addProperty("moved_items",Math.abs(after-before));
        return data;
    }
    private void finishCheck(JsonObject evidence,String query) {
        client.engine().reply(client.engine().responses.size()-1,"目标已完成"); client.drain();
        status();
        assertEquals(query,client.lastTool().str("tool"));
        reply(evidence);
    }
    @Test void typedGoalsAreDiscoverableAndReuseTheBuildingTargetContract() {
        var goal = ClientToolDefs.SPECS.stream().filter(s -> s.name().equals("set_goal")).findFirst().orElseThrow();
        var properties = JsonParser.parseString(goal.paramsJsonSchema()).getAsJsonObject().getAsJsonObject("properties");
        assertTrue(properties.has("type"), "set_goal must distinguish item, building and delivery evidence");
        var query = ClientToolDefs.SPECS.stream().filter(s -> s.name().equals("build_diff")).findFirst().orElseThrow();
        assertEquals(JsonParser.parseString(query.paramsJsonSchema()).getAsJsonObject()
                .getAsJsonObject("properties").get("target"), properties.get("target"));
        assertTrue(properties.has("container"));
        var help = ToolCatalog.read("{\"names\":[\"set_goal\"]}");
        assertTrue(help.ok());
        assertTrue(help.feedback().contains("building"));
        assertTrue(help.feedback().contains("delivery"));
    }

    @Test void registeredBuildingUsesWholeTargetChecksAndKeepsTheOriginalTaskAcrossPartialStages() {
        register(buildingGoal(),buildEvidence(0),"build_diff");
        long task=runner.currentTaskId();
        finishCheck(buildEvidence(16),"build_diff");
        assertEquals(task,runner.currentTaskId());
        assertTrue(client.lastTool().obj("args").get("details").getAsBoolean());
        status();
        assertEquals(1,client.sent.stream().filter(e->e.kind().equals("task_begin")).count());
        assertEquals(0,client.sent.stream().filter(e->e.kind().equals("task_end")).count());
        finishCheck(buildEvidence(20),"build_diff");
        assertEquals(0,runner.currentTaskId());
        assertEquals("completed",runner.panelHistory().tasks().getFirst().status());
        assertEquals(1,client.sent.stream().filter(e->e.kind().equals("task_end")).count());
    }
    @Test void directSurveyShowsTheActualRevisionAndDesignFactsInTheNextModelRequest() {
        begin();
        client.engine().tools(0,new ToolCall("survey","build_diff","{\"target\":"+TARGET+"}"));
        client.drain();
        assertEquals("build_diff",client.lastTool().str("tool"));
        var evidence=buildEvidence(0);
        evidence.addProperty("next_offset",16);
        evidence.addProperty("material_next_offset",-1);
        evidence.add("design_components",json("{\"doors\":0,\"beds\":0}"));
        evidence.add("design_review",json("{\"stairs_missing_orientation\":2,\"stairs_rising_behind_back\":4}"));
        evidence.add("cells",json("{\"private_fixture_field\":\"not-for-direct-feedback\"}"));
        reply(true,"只读核对，尚未施工。",evidence);
        status();
        var receipt=client.engine().histories.getLast().stream()
                .filter(com.neko.mcbot.agentcore.llm.Msg.Tool.class::isInstance)
                .map(com.neko.mcbot.agentcore.llm.Msg.Tool.class::cast)
                .filter(t->t.callId().equals("survey")).findFirst().orElseThrow();
        assertTrue(receipt.ok());
        assertTrue(receipt.content().contains("\"target_revision\":\""+REVISION+"\""),receipt.content());
        assertTrue(receipt.content().contains("\"next_offset\":16"),receipt.content());
        assertTrue(receipt.content().contains("\"stairs_rising_behind_back\":4"),receipt.content());
        assertTrue(receipt.content().contains("\"doors\":0"),receipt.content());
        assertFalse(receipt.content().contains("private_fixture_field"));
        assertNotEquals(0,runner.currentTaskId());
    }
    @Test void registrationRevisionConflictKeepsItsCauseAndCanBeCorrectedBeforeFreezingTheGoal() {
        begin();
        var wrong=json(buildingGoal()); wrong.addProperty("expected_revision","b".repeat(64));
        client.engine().tools(0,new ToolCall("wrong-goal","set_goal",wrong.toString()));
        client.drain(); status();
        assertEquals("build_diff",client.lastTool().str("tool"));
        reply(false,"TARGET_REVISION_CHANGED:输入目标已变化。",new JsonObject());
        assertNotEquals(0,runner.currentTaskId(),"a rejected drawing has not fixed a final goal yet");
        status();
        var receipt=client.engine().histories.getLast().stream()
                .filter(com.neko.mcbot.agentcore.llm.Msg.Tool.class::isInstance)
                .map(com.neko.mcbot.agentcore.llm.Msg.Tool.class::cast)
                .filter(t->t.callId().equals("wrong-goal")).findFirst().orElseThrow();
        assertFalse(receipt.ok());
        assertTrue(receipt.content().startsWith("TARGET_REVISION_CHANGED:"),receipt.content());
        assertTrue(receipt.content().contains("build_diff"),receipt.content());
        client.engine().tools(1,new ToolCall("corrected-goal","set_goal",buildingGoal()));
        client.drain(); status(); reply(buildEvidence(0)); status();
        assertEquals(3,client.engine().responses.size());
        assertEquals(1,client.sent.stream().filter(e->e.kind().equals("task_begin")).count());
        assertEquals(0,client.sent.stream().filter(e->e.kind().equals("task_end")).count());
        finishCheck(buildEvidence(20),"build_diff");
        assertEquals(0,runner.currentTaskId());
        assertEquals("completed",runner.panelHistory().tasks().getFirst().status());
    }
    @Test void failedTargetReadPreservesTheServerCauseInTheFinalDiagnostic() {
        begin();
        client.engine().tools(0,new ToolCall("goal","set_goal",buildingGoal()));
        client.drain(); status();
        reply(false,"TARGET_LOST:unloaded",new JsonObject());
        assertEquals(0,runner.currentTaskId());
        assertTrue(client.chat.stream().anyMatch(t->t.contains("TARGET_LOST:unloaded")),client.chat.toString());
        assertEquals("failed",runner.panelHistory().tasks().getFirst().status());
    }
    @Test void programGetsRegisteredTargetFromTheLoopAndNeedsNoModelReconstructionOrRecordLookup() {
        register(buildingGoal(),buildEvidence(0),"build_diff");
        var args=new JsonObject();
        args.addProperty("code","return mc.build()");
        long before=client.toolCount();
        client.engine().tools(1,new ToolCall("resume-build","run_program",args.toString())); client.drain();
        until(()->client.toolCount()>before);
        assertEquals("build_site",client.lastTool().str("tool"));
        assertEquals(json(TARGET),client.lastTool().obj("args").getAsJsonObject("target"));
        assertEquals(REVISION,client.lastTool().obj("args").get("expected_revision").getAsString());
        reply(json("{\"active\":true,\"target_revision\":\""+REVISION+"\",\"temporary\":[]}"));
        until(()->client.toolCount()>before+1);
        status();
        until(()->client.toolCount()>before+2);
        assertEquals("build_diff",client.lastTool().str("tool"));
        assertEquals(json(TARGET),client.lastTool().obj("args").getAsJsonObject("target"));
        var evidence=buildEvidence(20);
        evidence.add("cells",new com.google.gson.JsonArray());
        evidence.addProperty("next_offset",-1);
        reply(evidence);
        until(()->client.toolCount()>before+3);
        status();
        assertEquals(3,client.engine().responses.size());
        assertTrue(client.engine().histories.getLast().stream()
                .filter(com.neko.mcbot.agentcore.llm.Msg.Tool.class::isInstance)
                .map(com.neko.mcbot.agentcore.llm.Msg.Tool.class::cast)
                .anyMatch(t->t.callId().equals("resume-build") && t.ok() && t.content().contains("\"goal_satisfied\":true")));
        assertNotEquals(0,runner.currentTaskId(),"local completion does not bypass the independent final goal check");
        assertTrue(client.sent.stream().filter(e->e.kind().equals("tool_call"))
                .noneMatch(e->e.str("tool").equals("build_record")));
    }
    @Test void luaTransfersUseTheSameActualLedgerAndExistingContainerContentsDoNotCountAsNewDelivery() {
        registerDelivery(5);
        var code="local r=mc.call('transfer',{x=2,y=90,z=0,dir='in',item='diamond'}); return r.data";
        var args=new JsonObject(); args.addProperty("code",code);
        long sent=client.toolCount();
        client.engine().tools(1,new ToolCall("deposit","run_program",args.toString())); client.drain();
        until(()->client.toolCount()>sent);
        assertEquals("transfer",client.lastTool().str("tool"));
        long transferCount=client.toolCount();
        reply(moved("in",5,8));
        until(()->client.toolCount()>transferCount);
        status();
        finishCheck(container(8),"inspect_block");
        assertEquals("completed",runner.panelHistory().tasks().getFirst().status());
        assertEquals(0,runner.currentTaskId());
    }
    @Test void externalDepositsAndBackpackChangesCannotReplaceTheTaskTransferEvidence() {
        registerDelivery(5);
        finishCheck(container(8),"inspect_block");
        assertNotEquals(0,runner.currentTaskId());
        status();
        var transfer=runner.execute("transfer","{\"x\":2,\"y\":90,\"z\":0,\"dir\":\"in\",\"item\":\"diamond\"}");
        client.drain(); reply(false,"partial",moved("in",8,11));
        assertFalse(transfer.join().ok());
        finishCheck(container(11),"inspect_block");
        assertEquals("completed",runner.panelHistory().tasks().getFirst().status());
    }
    @Test void withdrawnItemsAreDeductedEvenWhenTheTransferRunsInADifferentComposition() {
        registerDelivery(0);
        var deposited=runner.execute("transfer","{\"x\":2,\"y\":90,\"z\":0,\"dir\":\"in\",\"item\":\"diamond\"}");
        client.drain(); reply(moved("in",0,3)); assertTrue(deposited.join().ok());
        var withdrawn=runner.execute("transfer","{\"x\":2,\"y\":90,\"z\":0,\"dir\":\"out\",\"item\":\"diamond\"}");
        client.drain(); reply(moved("out",3,0)); assertTrue(withdrawn.join().ok());
        finishCheck(container(3),"inspect_block");
        assertNotEquals(0,runner.currentTaskId(),"another depositor cannot restore withdrawn task credit");
    }
    @Test void cancellationDuringTargetReadIgnoresLateEvidenceAndNeverStartsAnotherTaskOrModelTurn() {
        register(buildingGoal(),buildEvidence(0),"build_diff");
        client.engine().reply(1,"done"); client.drain(); status();
        var pending=client.lastTool();
        int requests=client.engine().responses.size();
        runner.cancelTask(0); client.drain();
        long calls=client.toolCount();
        var receipt=json("{\"ok\":true,\"feedback\":\"late\"}");
        receipt.addProperty("seq",pending.num("seq",-1)); receipt.add("data",buildEvidence(20));
        runner.handleS2c(new Envelope("tool_result",receipt)); client.drain();
        assertEquals(calls,client.toolCount());
        assertEquals(requests,client.engine().responses.size());
        assertEquals("cancelled",runner.panelHistory().tasks().getFirst().status());
    }
    @Test void changedContainerIdentityOrUnknownTransferEndsWithoutClaimingCompletion() {
        registerDelivery(0);
        var transfer=runner.execute("transfer","{\"x\":2,\"y\":90,\"z\":0,\"dir\":\"in\",\"item\":\"diamond\"}");
        client.drain(); reply(false,"unknown mutation",new JsonObject());
        assertFalse(transfer.join().ok());
        finishCheck(container(3),"inspect_block");
        assertEquals("failed",runner.panelHistory().tasks().getFirst().status());
        assertEquals(0,runner.currentTaskId());
    }
    @Test void originalInstanceReferenceAndRevisionReachTheExistingReadOnlyToolUnchanged() {
        String target="{\"instance_id\":\"11000000-0000-0000-0000-000000000001\",\"instance_revision\":2}";
        register("{\"type\":\"building\",\"target\":" + target + ",\"expected_revision\":\"" + REVISION + "\"}",
                buildEvidence(0),"build_diff");
        client.engine().reply(1,"done"); client.drain(); status();
        assertEquals(json(target),client.lastTool().obj("args").getAsJsonObject("target"));
        assertEquals(REVISION,client.lastTool().obj("args").get("expected_revision").getAsString());
        assertTrue(client.lastTool().obj("args").get("verify_only").getAsBoolean());
        reply(false,"TARGET_REVISION_CHANGED:changed",new JsonObject());
        assertEquals("failed",runner.panelHistory().tasks().getFirst().status());
    }
    @Test void finiteFollowTerminalCannotSatisfyAnUnfinishedRegisteredBuilding() {
        register(buildingGoal(),buildEvidence(0),"build_diff");
        client.engine().tools(1,new ToolCall("follow","follow","{\"seconds\":1}")); client.drain();
        long seq=client.lastTool().num("seq",-1);
        var ack=json("{\"job_id\":\"follow-job\",\"cap_ticks\":20}");
        ack.addProperty("seq",seq); runner.handleS2c(new Envelope("job_ack",ack)); client.drain();
        assertEquals(2,client.engine().responses.size());
        assertNotEquals(0,runner.currentTaskId());
        var terminal=json("{\"job_id\":\"follow-job\",\"phase\":\"done\",\"text\":\"behavior ended\","
                + "\"data\":{\"behavior_ended\":true,\"goal_satisfied\":false}}");
        terminal.addProperty("seq",seq); runner.handleS2c(new Envelope("job_event",terminal)); client.drain();
        status();
        finishCheck(buildEvidence(0),"build_diff");
        assertNotEquals(0,runner.currentTaskId());
        assertEquals("running",runner.panelHistory().tasks().getFirst().status());
    }
    @Test void unavailableRegistrationEndsAsFailureInsteadOfFallingBackToUnverifiedTextCompletion() {
        begin();
        client.engine().tools(0,new ToolCall("goal","set_goal",buildingGoal())); client.drain(); status();
        reply(false,"TARGET_LOST:unloaded",new JsonObject());
        assertEquals(0,runner.currentTaskId());
        assertEquals(1,client.engine().responses.size());
        assertEquals("failed",runner.panelHistory().tasks().getFirst().status());
    }
}
