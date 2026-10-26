package com.neko.mcbot.agent;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.common.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class AgentRunnerMultiGoalTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void actualBedSurveyCannotFreezeAnInvalidDrawingAndTheCorrectedPairCanRegisterInTheSameTask(boolean serverRefused) {
        com.neko.mcbot.server.BlockActionFixtures.bootstrap();
        var placement=new com.neko.mcbot.server.BlockActionFixtures.Placement();
        var blocks=new java.util.HashMap<net.minecraft.core.BlockPos,net.minecraft.world.level.block.state.BlockState>();
        var access=new com.neko.mcbot.building.BuildSurvey.Access() {
            @Override public net.minecraft.world.level.block.state.BlockState state(net.minecraft.core.BlockPos p) {
                return blocks.getOrDefault(p,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            }
            @Override public boolean inBounds(net.minecraft.core.BlockPos p) { return true; }
            @Override public boolean protectedCell(net.minecraft.core.BlockPos p) { return false; }
            @Override public boolean near(net.minecraft.core.BlockPos p) { return false; }
            @Override public com.neko.mcbot.server.BlockPlacement.Access placement() { return placement; }
            @Override public JsonObject terrain() { return json("{\"dimension\":\"minecraft:overworld\",\"chunks\":{\"0,0\":\"fixed\"}}"); }
        };
        var bad=json("""
                {"anchor":{"x":-236,"y":63,"z":-440},"cells":[
                {"x":1,"y":1,"z":8,"block":"red_bed","properties":[
                {"name":"part","value":"head"},{"name":"facing","value":"south"}]},
                {"x":1,"y":1,"z":9,"block":"red_bed","properties":[
                {"name":"part","value":"foot"},{"name":"facing","value":"south"}]}]}
                """);
        var target=com.neko.mcbot.building.BuildTarget.parse(bad);
        var goal=json("{\"type\":\"building\",\"lifecycle\":\"long_term\"}");
        goal.add("target",bad); goal.addProperty("expected_revision",target.revision());
        begin();
        long task=runner.currentTaskId();
        client.engine().tools(0,new ToolCall("invalid-bed","set_goal",goal.toString())); client.drain(); status();
        assertEquals("build_diff",client.lastTool().str("tool"));
        var actual=com.neko.mcbot.building.BuildSurvey.verify(target,access,0,()->0);
        assertEquals(2,actual.data().getAsJsonObject("counts").get("unsupported").getAsInt());
        if (serverRefused) reply(false,com.neko.mcbot.building.BuildObjects.invalidFeedback(
                actual.data().getAsJsonObject("object_validation")),actual.data());
        else reply(actual.data());
        status();
        var receipt=client.engine().histories.getLast().stream()
                .filter(com.neko.mcbot.agentcore.llm.Msg.Tool.class::isInstance)
                .map(com.neko.mcbot.agentcore.llm.Msg.Tool.class::cast)
                .filter(t->t.callId().equals("invalid-bed")).findFirst().orElseThrow();
        assertFalse(receipt.ok(),"the old chain accepted the invalid pair and froze it");
        assertTrue(receipt.content().contains("INVALID_BUILD_TARGET:"),receipt.content());
        assertTrue(receipt.content().contains("bed_pair_missing_or_inconsistent"),receipt.content());
        assertEquals(task,runner.currentTaskId());
        for (var cell:bad.getAsJsonArray("cells"))
            cell.getAsJsonObject().getAsJsonArray("properties").get(1).getAsJsonObject().addProperty("value","north");
        var corrected=com.neko.mcbot.building.BuildTarget.parse(bad);
        goal.add("target",bad); goal.addProperty("expected_revision",corrected.revision());
        client.engine().tools(1,new ToolCall("corrected-bed","set_goal",goal.toString())); client.drain(); status();
        reply(com.neko.mcbot.building.BuildSurvey.verify(corrected,access,0,()->0).data()); status();
        assertEquals(task,runner.currentTaskId());
        corrected.cells().forEach(c->blocks.put(c.pos(),c.desired()));
        finishCheck(com.neko.mcbot.building.BuildSurvey.verify(corrected,access,0,()->0).data(),"build_diff");
        assertEquals(0,runner.currentTaskId());
        assertEquals("completed",runner.panelHistory().tasks().getFirst().status());
        assertEquals(0,placement.calls);
        assertEquals(1,client.sent.stream().filter(e->e.kind().equals("task_begin")).count());
    }

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
        evidence.add("object_validation",json("{\"valid\":false,\"invalid_cells\":2,\"issues\":[{\"reason\":\"bed_pair_missing_or_inconsistent\"}]}"));
        evidence.add("foundation_work",json("""
                {"active":true,"y":90,"unfinished":17,"supply_required":true,
                "materials":[{"item":"minecraft:oak_planks","required":17,"available":0,"shortfall":17}]}
                """));
        evidence.add("target",json("{\"draft_revision\":\""+REVISION+"\"}"));
        evidence.addProperty("draft_retained",true);
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
        assertTrue(receipt.content().contains("\"draft_revision\":\""+REVISION+"\""),receipt.content());
        assertTrue(receipt.content().contains("bed_pair_missing_or_inconsistent"),receipt.content());
        assertTrue(receipt.content().contains("\"foundation_work\":"),receipt.content());
        assertTrue(receipt.content().contains("\"supply_required\":true"),receipt.content());
        assertTrue(receipt.content().contains("\"shortfall\":17"),receipt.content());
        assertTrue(receipt.content().contains("minecraft:oak_planks"),receipt.content());
        assertFalse(receipt.content().contains("private_fixture_field"));
        assertNotEquals(0,runner.currentTaskId());
    }
    @Test void directDraftListAndQueriesExposeActualEntriesAndPagesInsteadOfOnlyGenericSuccessText() {
        begin();
        var target=json("{\"draft_revision\":\""+REVISION+"\"}");
        for (String operation : java.util.List.of("draft_list","draft_query")) {
            int turn=client.engine().responses.size()-1;
            var args=json("{\"operation\":\""+operation+"\"}");
            if (operation.equals("draft_query")) args.add("target",target);
            client.engine().tools(turn,new ToolCall(operation,"build_record",args.toString())); client.drain();
            assertEquals("build_record",client.lastTool().str("tool"));
            var data=json("{\"next_offset\":-1,\"task_scoped\":true,\"persistent\":false}");
            if (operation.equals("draft_list")) {
                var entries=new com.google.gson.JsonArray();
                var row=json("{\"target_revision\":\""+REVISION+"\",\"cell_count\":598}"); row.add("target",target);
                entries.add(row); data.add("entries",entries);
            } else {
                data.add("target",target);
                data.add("cells",com.google.gson.JsonParser.parseString("[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"oak_stairs\"}]"));
            }
            reply(data); status();
            var receipt=client.engine().histories.getLast().stream()
                    .filter(com.neko.mcbot.agentcore.llm.Msg.Tool.class::isInstance)
                    .map(com.neko.mcbot.agentcore.llm.Msg.Tool.class::cast)
                    .filter(t->t.callId().equals(operation)).findFirst().orElseThrow();
            assertTrue(receipt.content().contains(REVISION),receipt.content());
            assertTrue(receipt.content().contains(operation.equals("draft_list") ? "\"entries\"" : "oak_stairs"),receipt.content());
        }
    }
    @Test void finalGoalRegistrationRejectsAStaleWorksiteAndAllowsCorrectionBeforeFreezing() {
        begin();
        var target=json("{\"draft_revision\":\""+REVISION+"\"}");
        var goal=json(buildingGoal()); goal.add("target",target);
        client.engine().tools(0,new ToolCall("select","set_goal",goal.toString())); client.drain(); status();
        var evidence=buildEvidence(0); evidence.addProperty("worksite_revision","b".repeat(64));
        reply(evidence); status();
        var rejected=client.engine().histories.getLast().stream()
                .filter(com.neko.mcbot.agentcore.llm.Msg.Tool.class::isInstance)
                .map(com.neko.mcbot.agentcore.llm.Msg.Tool.class::cast)
                .filter(t->t.callId().equals("select")).findFirst().orElseThrow();
        assertFalse(rejected.ok()); assertTrue(rejected.content().contains("TARGET_REVISION_CHANGED"));
        var correction=json("{\"operation\":\"revise\",\"expected_revision\":\""+"b".repeat(64)+"\"}");
        correction.add("target",target);
        client.engine().tools(1,new ToolCall("correct","build_site",correction.toString())); client.drain();
        assertEquals("build_site",client.lastTool().str("tool"));
        reply(json("{\"active\":true,\"target_revision\":\""+REVISION+"\"}")); status();
        client.engine().tools(2,new ToolCall("select-corrected","set_goal",goal.toString())); client.drain(); status();
        evidence.addProperty("worksite_revision",REVISION); reply(evidence); status();
        long before=client.toolCount();
        client.engine().tools(3,new ToolCall("late-correction","build_site",correction.toString())); client.drain();
        assertEquals(before+1,client.toolCount(),"only fresh status may be sent after rejecting the correction locally");
        assertEquals("status",client.lastTool().str("tool")); status();
        var receipt=client.engine().histories.getLast().stream()
                .filter(com.neko.mcbot.agentcore.llm.Msg.Tool.class::isInstance)
                .map(com.neko.mcbot.agentcore.llm.Msg.Tool.class::cast)
                .filter(t->t.callId().equals("late-correction")).findFirst().orElseThrow();
        assertFalse(receipt.ok()); assertTrue(receipt.content().contains("最终目标已登记"));
        var code=new JsonObject();
        code.addProperty("code","return mc.call('build_site',{operation='revise',target={draft_revision='"+REVISION
                +"'},expected_revision='"+"b".repeat(64)+"'})");
        long beforeProgram=client.toolCount();
        client.engine().tools(4,new ToolCall("late-lua","run_program",code.toString())); client.drain();
        until(()->client.toolCount()>beforeProgram); status();
        assertEquals(beforeProgram+1,client.toolCount(),"Lua shares the same final-goal guard");
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
    @Test void aStalledRouteUnparksTheModelWithoutCancellingTheRegisteredBuilding() {
        register(buildingGoal(),buildEvidence(0),"build_diff");
        long task=runner.currentTaskId();
        client.engine().tools(1,new ToolCall("route","move_to","{\"x\":2,\"y\":90,\"z\":0}"));
        client.drain();
        long seq=client.lastTool().num("seq",-1);
        var ack=json("{\"job_id\":\"stalled-route\",\"cap_ticks\":-1}");
        ack.addProperty("seq",seq);
        runner.handleS2c(new Envelope("job_ack",ack)); client.drain();
        assertEquals(2,client.engine().responses.size(),"the model waits for the real route terminal");
        var terminal=json("""
                {"job_id":"stalled-route","phase":"failed","text":"NAVIGATION_STALLED:核对现场再换工作位。",
                "data":{"navigation_stalled":true,"model_reassessment_required":true,"search_idle_ms":30000,
                "goal_satisfied":false,"removed_blocks":1,"placed_blocks":2}}
                """);
        terminal.addProperty("seq",seq);
        runner.handleS2c(new Envelope("job_event",terminal)); client.drain();
        status();
        assertEquals(3,client.engine().responses.size(),"the existing loop requests a new model decision");
        var receipt=client.engine().histories.getLast().stream()
                .filter(com.neko.mcbot.agentcore.llm.Msg.Tool.class::isInstance)
                .map(com.neko.mcbot.agentcore.llm.Msg.Tool.class::cast)
                .filter(t->t.callId().equals("route")).findFirst().orElseThrow();
        assertFalse(receipt.ok());
        assertTrue(receipt.content().contains("NAVIGATION_STALLED:"));
        assertTrue(receipt.content().contains("核对现场再换工作位"),
                "the model receives the action feedback; structured flags remain available to programs and the UI");
        assertEquals(task,runner.currentTaskId());
        assertEquals("running",runner.panelHistory().tasks().getFirst().status());
        assertEquals(1,client.sent.stream().filter(e->e.kind().equals("task_begin")).count());
        assertEquals(0,client.sent.stream().filter(e->e.kind().equals("task_end")).count());
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
