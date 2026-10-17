package com.neko.mcbot.agent;

import com.google.gson.*;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.common.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class AgentRunnerProgramTest {
    private final OfflineAgentClient client = new OfflineAgentClient();
    private final AgentRunner runner = client.create();
    @AfterEach void close() { runner.close(); client.drain(); }

    private void until(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            client.drain();
            java.util.concurrent.locks.LockSupport.parkNanos(100000);
        }
        assertTrue(condition.getAsBoolean(), "runner/program did not settle");
    }
    private void start(String directive, String code) { start(directive, code, 128); }
    private void start(String directive, String code, int budget) {
        runner.start();
        runner.submitTask(directive);
        program(0, code, budget);
    }
    private void program(int turn, String code, int budget) {
        var args = new JsonObject();
        args.addProperty("code", code);
        args.addProperty("max_calls", budget);
        client.engine().tools(turn, new ToolCall("program", "run_program", args.toString()));
        client.drain();
    }
    private void tool(String expected, long count) {
        until(() -> client.toolCount() >= count);
        assertEquals(count, client.toolCount());
        assertEquals(expected, client.lastTool().str("tool"));
    }
    private void receipt(boolean ok, String text, String data) {
        var body = new JsonObject();
        body.addProperty("seq", client.lastTool().num("seq", -1));
        body.addProperty("ok", ok);
        body.addProperty("feedback", text);
        body.add("data", JsonParser.parseString(data));
        runner.handleS2c(new Envelope("tool_result", body));
        client.drain();
    }
    private Msg.Tool outcome() {
        until(() -> client.engine().responses.size() >= 2);
        return client.engine().histories.getLast().stream().filter(Msg.Tool.class::isInstance)
                .map(Msg.Tool.class::cast).filter(t -> t.callId().equals("program"))
                .reduce((earlier, later) -> later).orElseThrow();
    }
    private void ack(String job) {
        var body = new JsonObject();
        body.addProperty("seq", client.lastTool().num("seq", -1));
        body.addProperty("job_id", job);
        body.addProperty("cap_ticks", 1200);
        runner.handleS2c(new Envelope("job_ack", body));
        client.drain();
    }
    private void terminal(long seq, String job, String data) {
        var body = new JsonObject();
        body.addProperty("seq", seq);
        body.addProperty("job_id", job);
        body.addProperty("phase", "done");
        body.addProperty("text", "actual terminal");
        body.add("data", JsonParser.parseString(data));
        runner.handleS2c(new Envelope("job_event", body));
        client.drain();
    }
    @Test void followArrivalAndAcceptedProgressDoNotAdvanceLuaBeforeTheTrueTerminal() {
        start("follow briefly then observe", "local r=mc.call('follow',{seconds=1}); "
                + "if r.data.goal_satisfied then error('not an outer goal') end; return mc.call('inventory',{})", 2);
        tool("follow", 1);
        long seq = client.lastTool().num("seq", -1);
        ack("following");
        assertEquals(1, client.toolCount());
        var progress = new JsonObject();
        progress.addProperty("seq", seq); progress.addProperty("job_id", "following");
        progress.addProperty("phase", "progress"); progress.addProperty("text", "sleeping at target");
        runner.handleS2c(new Envelope("job_event", progress)); client.drain();
        assertEquals(1, client.toolCount());
        terminal(seq, "following", "{\"behavior_ended\":true,\"goal_satisfied\":false,\"follow\":{\"in_band\":true}}");
        tool("inventory", 2);
        receipt(true, "fresh inventory", "{}");
        assertTrue(outcome().ok(), outcome().content());
    }

    @Test void followFailureDoesNotDispatchADependentActionOrRefillProgramBudget() {
        start("follow then equip", "mc.call('follow',{}); return mc.call('equip',{slot=1})", 2);
        tool("follow", 1);
        receipt(false, "NO_PATH:dry gap", "{\"goal_satisfied\":false}");
        assertFalse(outcome().ok());
        assertEquals(1, client.toolCount());
    }

    @Test void cancellingFollowIgnoresLateTerminalAndDoesNotWakeTheOldProgram() {
        start("follow then equip", "mc.call('follow',{}); return mc.call('equip',{slot=1})", 2);
        tool("follow", 1);
        long seq = client.lastTool().num("seq", -1);
        ack("follow-cancel");
        runner.cancelTask(0); client.drain();
        terminal(seq, "follow-cancel", "{\"behavior_ended\":true,\"goal_satisfied\":false}");
        assertEquals(1, client.toolCount());
        assertEquals(0, JsonParser.parseString(runner.statusJson()).getAsJsonObject().get("current_task").getAsLong());
    }

    @Test void readOnlyProgramCannotIssueFollowEvenWhenAlreadyNearItsTarget() {
        start("[只读] follow", "return mc.call('follow',{seconds=1})", 1);
        assertFalse(outcome().ok());
        assertEquals(0, client.toolCount());
    }

    @Test void followLocalRecoverySharesTaskFailureLedgerAcrossFreshQueriesWithoutBuyingMoreAttempts() {
        start("bounded follow", """
                for i=1,4 do
                    mc.try_call("follow",{seconds=1})
                    mc.call("status",{details=true})
                end
                """, 12);
        for (int i = 0; i < 4; i++) {
            tool("follow", 2 * i + 1);
            receipt(false, "NO_PATH:unchanged dry gap", "{\"goal_satisfied\":false}");
            tool("status", 2 * i + 2);
            receipt(true, "same full body facts", BoundedGatherTest.state(0).toString());
        }
        assertTrue(outcome().ok());
        program(1, "return mc.call('follow',{seconds=1})", 1);
        tool("follow", 9);
        receipt(false, "NO_PATH:fifth real terminal", "{\"goal_satisfied\":false}");
        until(() -> client.engine().responses.size() >= 3);
        assertFalse(outcome().ok());
        program(2, "return mc.call('follow',{seconds=1})", 1);
        until(() -> client.engine().responses.size() >= 4);
        assertFalse(outcome().ok());
        assertTrue(outcome().content().contains("REPEATED_FAILURE:"));
        assertEquals(9, client.toolCount());
    }

    @Test void followDurationDoesNotCompleteOrReplaceAnOriginalLongTermItemGoal() {
        client.liveObservation = true;
        runner.start();
        runner.submitTask("取得一块圆石，先跟随主人观察");
        client.drain();
        receipt(true, "baseline", BoundedGatherTest.state(0).toString());
        long taskId = JsonParser.parseString(runner.statusJson()).getAsJsonObject().get("current_task").getAsLong();
        client.engine().tools(0, new ToolCall("goal", "set_goal",
                "{\"item\":\"cobblestone\",\"count\":1,\"lifecycle\":\"long_term\"}"));
        client.drain();
        receipt(true, "fresh before follow", BoundedGatherTest.state(0).toString());
        program(1, "return mc.call('follow',{seconds=1})", 1);
        tool("follow", 3);
        ack("long-follow");
        assertEquals(taskId, JsonParser.parseString(runner.statusJson()).getAsJsonObject().get("current_task").getAsLong());
        terminal(client.lastTool().num("seq", -1), "long-follow",
                "{\"behavior_ended\":true,\"goal_satisfied\":false,\"follow\":{\"in_band\":true}}");
        tool("status", 4);
        receipt(true, "still no cobblestone", BoundedGatherTest.state(0).toString());
        assertEquals(3, client.engine().responses.size());
        client.engine().reply(2, "阶段结束");
        client.drain();
        receipt(true, "final item still missing", BoundedGatherTest.state(0).toString());
        receipt(true, "fresh next stage", BoundedGatherTest.state(0).toString());
        assertEquals(taskId, JsonParser.parseString(runner.statusJson()).getAsJsonObject().get("current_task").getAsLong());
        assertEquals(1, client.sent.stream().filter(e -> e.kind().equals("task_begin")).count());
        assertTrue(client.engine().histories.getLast().stream().anyMatch(m ->
                m instanceof Msg.Nudge n && n.text().contains("minecraft:cobblestone")));
    }
    @Test void blueprintInspectionCanStopAnUnsupportedImportWithinOneBoundedProgram() {
        start("inspect the selected blueprint","local r=mc.call('build_blueprint',{operation='inspect',name='sample'});"
                +"if not r.data.importable then return r.data end; "
                +"return mc.call('build_blueprint',{operation='import',name='sample',source_revision=r.data.source_revision,design_id='wall'})",4);
        tool("build_blueprint",1); receipt(true,"unsupported","{\"importable\":false,\"unsupported_cells\":2}");
        assertTrue(outcome().ok()); assertEquals(1,client.toolCount());
        assertTrue(outcome().content().contains("\"unsupported_cells\":2"));
    }
    @Test void readOnlyLuaRejectsBlueprintImportWithoutDispatch() {
        start("[只读] inspect","return mc.call('build_blueprint',{operation='import',name='sample',source_revision='"
                +"a".repeat(64)+"',design_id='wall'})",4);
        assertFalse(outcome().ok()); assertEquals(0,client.toolCount());
    }
    @Test void cancelledInspectionNeverImportsFromALateResult() {
        start("inspect then import","local r=mc.call('build_blueprint',{operation='inspect',name='sample'});"
                +"return mc.call('build_blueprint',{operation='import',name='sample',source_revision=r.data.source_revision,design_id='wall'})",4);
        tool("build_blueprint",1); runner.cancelTask(0); client.drain();
        receipt(true,"late file data","{\"importable\":true,\"source_revision\":\""+"a".repeat(64)+"\"}");
        runner.submitTask("inspect current world"); client.drain();
        assertFalse(outcome().ok()); assertEquals(1,client.toolCount());
    }
    @Test void durableRecordQueriesAndReferenceConstructionUseTheExistingLuaRunner() {
        start("[只读] inspect designs","return mc.call('build_record',{operation='design_list'}).data",4);
        tool("build_record",1);
        receipt(true,"stored data","{\"entries\":[],\"next_offset\":-1}");
        assertTrue(outcome().ok());
    }
    @Test void readOnlyLuaCannotCreateDurableDesignData() {
        start("[只读] inspect","return mc.call('build_record',{operation='design_create',design_id='wall',"
                +"definition={cells={{x=0,y=0,z=0,block='stone'}}}})",4);
        assertFalse(outcome().ok()); assertEquals(0,client.toolCount());
    }
    @Test void instanceReferencePassesTheOriginalConstructionSchemasWithoutExpandingTheTarget() {
        start("build selected instance","return mc.build({instance_id='12345678-1234-1234-1234-123456789012',instance_revision=2},"
                +"{access_route={{x=0,y=90,z=0},{x=1,y=90,z=0}},max_actions=1,max_rounds=1})",8);
        tool("build_site",1);
        assertEquals(2,client.lastTool().body().getAsJsonObject("args").getAsJsonObject("target").get("instance_revision").getAsInt());
        receipt(true,"frozen instance",siteFacts());
        tool("status",2); receipt(true,"body","{}");
        tool("build_diff",3); receipt(true,"whole target",siteDiff("satisfied",true));
        assertTrue(outcome().ok()); assertEquals(3,client.toolCount());
    }

    @Test void recipeThenCraftThenRealSlotSelectionNeedsOnlyOneModelTurn() {
        start("make a fixture item", """
                local q=mc.call("craft",{item="stick",count=4,query=true})
                if not q.data.can_craft then return {blocked=q.feedback} end
                mc.call("craft",{item="stick",count=4})
                local inv=mc.call("inventory",{})
                for _,slot in ipairs(inv.data.slots) do
                    if slot.item=="minecraft:stick" then return mc.call("equip",{slot=slot.slot}) end
                end
                error("crafted item not observed")
                """);
        tool("craft", 1);
        receipt(true, "recipe available", "{\"can_craft\":true}");
        tool("craft", 2);
        receipt(true, "actual crafted count", "{\"crafted_count\":4}");
        tool("inventory", 3);
        receipt(true, "fresh slot", "{\"slots\":[{\"slot\":17,\"item\":\"minecraft:stick\",\"count\":4}]}");
        tool("equip", 4);
        assertEquals(17, client.lastTool().obj("args").get("slot").getAsInt());
        assertEquals(1, client.engine().responses.size());
        receipt(true, "equipped actual slot", "{\"selected_slot\":3}");
        assertTrue(outcome().ok(), outcome().content());
        assertTrue(outcome().content().contains("actual crafted count"));
        assertTrue(client.sent.stream().noneMatch(e -> e.kind().contains("program")));
    }

    @Test void readOnlyBuildingSurveyUsesTheSameProgramEntryAndReportsIncompleteGoalEvidence() {
        start("[只读] inspect the selected building cells", """
                local q=mc.call("build_diff",{target={anchor={x=0,y=90,z=0},
                    cells={{x=0,y=0,z=0,block="stone"}}}})
                assert(q.data.goal_satisfied==false)
                return {revision=q.data.target_revision,missing=q.data.remaining}
                """, 1);
        tool("build_diff", 1);
        receipt(true, "read-only actual difference", """
                {"target_revision":"fixture","remaining":1,"goal_satisfied":false,
                "observation_complete":false,"material_bill_complete":false,"material_next_offset":-1}
                """);
        var result = outcome();
        assertTrue(result.ok(), result.content());
        assertTrue(result.content().contains("\"missing\":1"));
        assertTrue(result.content().contains("\"goal_satisfied\":false"), "unprinted negative evidence remains model-facing");
        assertTrue(result.content().contains("\"material_bill_complete\":false"));
        assertEquals(1, client.toolCount());
    }

    private static final String BUILD = """
            local t={anchor={x=0,y=90,z=0},cells={{x=0,y=0,z=0,block="stone"}}}
            local q=mc.call("build_diff",{target=t})
            mc.call("build_nearby",{target=t,expected_revision=q.data.target_revision,max_actions=2})
            return mc.call("wait",{seconds=1})
            """;
    private static final String SITE_BUILD = """
            return mc.build({anchor={x=4,y=90,z=0},cells={{x=0,y=0,z=0,block="stone"}}},
                {access_route={{x=0,y=90,z=0},{x=1,y=90,z=0}},max_actions=2,max_rounds=2})
            """;
    private static String siteFacts() {
        return "{\"active\":true,\"target_revision\":\""+"a".repeat(64)+"\","
                +"\"bounds\":{\"x\":0,\"y\":90,\"z\":0,\"radius\":32},\"temporary\":[]}";
    }
    private static String siteDiff(String state,boolean satisfied) {
        return """
                {"target_revision":"%s","observation_key":"actual","goal_satisfied":%s,
                "observation_complete":true,"spatial_stable":true,"material_bill_complete":true,
                "budget_exhausted":false,"offset":0,"next_offset":-1,
                "cells":[{"x":4,"y":90,"z":0,"state":"%s","item":"minecraft:stone","material_available":true}]}
                """.formatted("a".repeat(64),satisfied,state);
    }
    @Test void bundledConstructionUsesRealRunnerSchemasAndWaitsForMovementAndBuildingTerminals() {
        start("build the selected worksite",SITE_BUILD,20);
        tool("build_site",1); receipt(true,"original worksite",siteFacts());
        tool("status",2); receipt(true,"fresh body","{}");
        tool("build_diff",3); receipt(true,"far target",siteDiff("out_of_reach",false));
        tool("build_site",4); receipt(true,"original checkpoint",siteFacts());
        tool("move_to",5);
        long moveSeq=client.lastTool().num("seq",-1); ack("site-move");
        assertEquals(5,client.toolCount());
        terminal(moveSeq,"site-move","{\"can_work_here\":true}");
        tool("status",6); receipt(true,"fresh body","{}");
        tool("build_diff",7); receipt(true,"ready target",siteDiff("ready",false));
        tool("build_site",8); receipt(true,"next checkpoint",siteFacts());
        tool("build_nearby",9);
        long buildSeq=client.lastTool().num("seq",-1); ack("site-build");
        assertEquals(9,client.toolCount());
        terminal(buildSeq,"site-build","{\"placed_blocks\":1,\"consumed_count\":1,\"goal_satisfied\":false}");
        tool("build_diff",10); receipt(true,"independent whole target",siteDiff("satisfied",true));
        var result=outcome();
        assertTrue(result.ok(),result.content());
        assertTrue(result.content().contains("\"goal_satisfied\":true"));
        assertTrue(result.content().contains("\"outer_goal_verified\":false"));
        assertTrue(result.content().contains("实际原子调用=11"));
    }
    @Test void cancellationOfBundledConstructionNeverReopensItsWorksiteOrContinuesALateMovement() {
        start("build selected worksite",SITE_BUILD,20);
        tool("build_site",1); receipt(true,"site",siteFacts());
        tool("status",2); receipt(true,"body","{}");
        tool("build_diff",3); receipt(true,"far",siteDiff("out_of_reach",false));
        tool("build_site",4); receipt(true,"checkpoint",siteFacts());
        tool("move_to",5);
        long seq=client.lastTool().num("seq",-1); ack("cancel-site");
        runner.cancelTask(0); client.drain();
        terminal(seq,"cancel-site","{\"can_work_here\":true}");
        runner.submitTask("inspect current world"); client.drain();
        assertFalse(outcome().ok()); assertEquals(5,client.toolCount());
    }
    @Test void bundledConstructionKeepsDoorSecondaryRequirementsWithoutIssuingASecondPlacementOrBudget() {
        start("build the selected door", """
                local t={anchor={x=4,y=90,z=0},cells={
                  {x=0,y=0,z=0,block="oak_door",properties={{name="half",value="lower"},{name="facing",value="north"},{name="hinge",value="left"}}},
                  {x=0,y=1,z=0,block="oak_door",properties={{name="half",value="upper"},{name="facing",value="north"},{name="hinge",value="left"}}}}}
                return mc.build(t,{access_route={{x=0,y=90,z=0},{x=1,y=90,z=0}},max_actions=1,max_rounds=1})
                """,6);
        tool("build_site",1); receipt(true,"frozen pair",siteFacts());
        tool("status",2); receipt(true,"fresh body","{}");
        var diff=JsonParser.parseString(siteDiff("ready",false)).getAsJsonObject();
        var primary=diff.getAsJsonArray("cells").get(0).getAsJsonObject(); primary.addProperty("item","minecraft:oak_door");
        var secondary=primary.deepCopy(); secondary.addProperty("y",91); secondary.addProperty("state","paired_pending");
        diff.getAsJsonArray("cells").add(secondary);
        tool("build_diff",3); receipt(true,"both cells",diff.toString());
        tool("build_site",4); receipt(true,"same account",siteFacts());
        tool("build_nearby",5);
        assertEquals(2,client.lastTool().body().getAsJsonObject("args").getAsJsonObject("target").getAsJsonArray("cells").size());
        long seq=client.lastTool().num("seq",-1); ack("pair");
        terminal(seq,"pair","{\"placed_blocks\":1,\"consumed_count\":1,\"goal_satisfied\":false}");
        tool("build_diff",6); receipt(true,"independent both-cell verification",siteDiff("satisfied",true));
        assertTrue(outcome().ok()); assertEquals(6,client.toolCount());
        assertTrue(outcome().content().contains("\"outer_goal_verified\":false"));
    }
    @Test void readOnlyTaskCannotRegisterAWorksiteThroughTheLuaHelper() {
        start("[只读] inspect selected worksite",SITE_BUILD,20);
        assertFalse(outcome().ok());
        assertEquals(0,client.toolCount());
    }

    @Test void constructionReservesEveryDeclaredActionAndWaitsForTerminalBeforeContinuing() {
        start("build selected cells", BUILD, 3);
        tool("build_diff", 1);
        receipt(true, "fresh diff", "{\"target_revision\":\"" + "a".repeat(64) + "\"}");
        tool("build_nearby", 2);
        long seq = client.lastTool().num("seq", -1);
        ack("construction");
        assertEquals(2, client.toolCount());
        terminal(seq, "construction", """
                {"placed_blocks":1,"changed":true,"consumed_count":1,"goal_satisfied":false,"final_verified":true}
                """);
        assertFalse(outcome().ok());
        assertTrue(outcome().content().contains("ACTION_LIMIT:"));
        assertEquals(2, client.toolCount(), "1 query + 2 construction actions exhausted original budget");
    }

    @Test void insufficientConstructionBudgetDispatchesNoBatchAndCancellationNeverRunsTheNextChild() {
        start("build selected cells", BUILD, 2);
        tool("build_diff", 1);
        receipt(true, "fresh diff", "{\"target_revision\":\"" + "a".repeat(64) + "\"}");
        assertFalse(outcome().ok());
        assertEquals(1, client.toolCount());
    }

    @Test void cancelledAcceptedConstructionCannotResumeAfterItsLateTerminal() {
        start("build selected cells", BUILD, 12);
        tool("build_diff", 1);
        receipt(true, "fresh diff", "{\"target_revision\":\"" + "a".repeat(64) + "\"}");
        tool("build_nearby", 2);
        long seq = client.lastTool().num("seq", -1);
        ack("construction");
        runner.cancelTask(0);
        client.drain();
        terminal(seq, "construction", "{\"goal_satisfied\":true}");
        runner.submitTask("inspect current facts");
        client.drain();
        assertFalse(outcome().ok());
        assertEquals(2, client.toolCount());
    }

    @Test void cancelledBuildingQueryCannotTriggerPlacementAfterItsLateResult() {
        start("inspect then place if ready", """
                local q=mc.call("build_diff",{target={anchor={x=0,y=90,z=0},
                    cells={{x=0,y=0,z=0,block="stone"}}}})
                if q.data.counts.ready>0 then mc.call("place_block",{x=0,y=90,z=0,item="stone"}) end
                """);
        tool("build_diff", 1);
        assertTrue(runner.cancelTask(0));
        client.drain();
        receipt(true, "late survey", "{\"counts\":{\"ready\":1}}");
        runner.submitTask("inspect current facts");
        client.drain();
        assertEquals(1, client.toolCount());
        assertTrue(outcome().content().contains("CANCELLED:"));
    }

    @Test void helpUsesTheActualRunnerWithoutGameDispatchOrChangingTheNextCatalog() {
        runner.start();
        runner.submitTask("[只读] read the available crafting contract");
        var before = client.engine().definitions.getFirst();
        client.engine().tools(0, new ToolCall("help", "tool_help", "{\"names\":[\"craft\"]}"));
        client.drain();
        assertEquals(0, client.toolCount());
        assertEquals(2, client.engine().responses.size());
        var help = client.engine().histories.getLast().stream().filter(Msg.Tool.class::isInstance)
                .map(Msg.Tool.class::cast).filter(t -> t.callId().equals("help")).findFirst().orElseThrow();
        assertTrue(help.ok());
        assertTrue(help.content().contains("material_index"));
        assertTrue(help.content().contains("query成功不等于can_craft"));
        assertEquals(before, client.engine().definitions.getLast());
        assertEquals(ToolCatalog.MODEL_SPECS, before);
    }

    @Test void programCanLoadHelpAndUseAnUnabridgedQueryLocallyWithoutSendingItsFullTextBack() {
        start("[只读] inspect the recipe", """
                local help=mc.call("tool_help",{names={"craft"}})
                assert(help.data.definitions[1].name=="craft")
                local q=mc.call("craft",{item="stick",query=true})
                if q.data.can_craft then return {ready=true} end
                return {ready=false}
                """);
        tool("craft", 1);
        String full = "large recipe alternatives ".repeat(600);
        receipt(true, full, "{\"can_craft\":true}");
        var result = outcome();
        assertTrue(result.ok(), result.content());
        assertTrue(result.content().contains("\"ready\":true"));
        assertFalse(result.content().contains(full));
        assertTrue(result.content().contains("整批能否完成"), "help is model-facing even inside Lua");
        assertEquals(1, client.toolCount());
    }

    @Test void actualRunnerReportsADataAccessErrorAndKeepsTheFreshQueryForCorrection() {
        start("[只读] inspect a nearby block", """
                local r=mc.call("inspect_block",{x=1,y=90,z=0})
                return r.data.position.x
                """);
        tool("inspect_block", 1);
        receipt(true, "block identity from the actual receipt",
                "{\"block\":\"minecraft:stone\",\"container\":false,\"slots\":[],\"next_offset\":-1}");
        var result = outcome();
        assertFalse(result.ok());
        assertTrue(result.content().contains("FIELD_ACCESS"), result.content());
        assertTrue(result.content().contains("脚本行=2"));
        assertTrue(result.content().contains("block identity from the actual receipt"));
        assertEquals(1, client.toolCount());
    }
    @Test void localRecoveryThroughActualRunnerStillRequiresFreshFullStatusAndOriginalPermission() {
        start("approach the same worksite", """
                local r=mc.try_call("move_to",{x=1,y=90,z=0,walk_only=true})
                if not r.ok then
                    local s=mc.call("status",{details=true}).data
                    return {x=s.x,kept=s.inventory.slots[1].count}
                end
                return {arrived=true}
                """);
        tool("move_to", 1);
        receipt(false, "PARTIAL:actual movement and terrain preserved", "{\"moved\":true}");
        tool("status", 2);
        assertTrue(client.lastTool().obj("args").get("details").getAsBoolean());
        receipt(true, "fresh body and inventory", "{\"x\":2,\"y\":90,\"z\":0,\"dimension\":\"minecraft:overworld\","
                + "\"inventory\":{\"slots\":[{\"slot\":0,\"count\":9}]}}");
        var result = outcome();
        assertTrue(result.ok(), result.content());
        assertTrue(result.content().contains("PARTIAL:actual movement and terrain preserved"));
        assertTrue(result.content().contains("\"kept\":9"));
        assertEquals(2, client.toolCount());
    }

    @Test void tryCallCannotBypassReadonlyOrContinueOnADeniedAction() {
        start("[只读] inspect only", """
                mc.try_call("move_to",{x=1,y=90,z=0})
                mc.call("status",{details=true})
                """);
        var result = outcome();
        assertFalse(result.ok());
        assertTrue(result.content().contains("只读"));
        assertEquals(0, client.toolCount());
    }

    @Test void helpThenInvalidDynamicArgumentsStillReturnTheExactSchemaFieldWithoutDispatch() {
        start("inspect movement contract", """
                mc.call("tool_help",{names={"move_to"}})
                mc.try_call("move_to",{x=1,y=90})
                mc.call("inventory",{})
                """);
        var result = outcome();
        assertFalse(result.ok());
        assertTrue(result.content().contains("args.z"));
        assertTrue(result.content().contains("缺少必要参数"));
        assertEquals(0, client.toolCount());
    }

    @Test void genericEntityDiscoveryControlsExplorationLocallyWithoutACollectionGoal() {
        start("find an observed entity", """
                for i=1,4 do
                    local s=mc.call("scan_area",{r=8})
                    if #s.data.entity_targets>0 then return s.data.entity_targets[1] end
                    if i<4 then mc.call("explore",{}) end
                end
                return {blocked="bounded observation ended"}
                """);
        for (int i = 0; i < 3; i++) {
            long offset = i * 5L;
            tool("scan_area", offset + 1);
            receipt(true, "no observed entity", "{\"entity_targets\":[],\"no_targets\":true}");
            tool("status", offset + 2);
            var here = BoundedGatherTest.state(0);
            here.addProperty("x", i * 8);
            receipt(true, "actual departure", here.toString());
            tool("scan_area", offset + 3);
            assertTrue(client.lastTool().obj("args").get("frontiers").getAsBoolean());
            receipt(true, "standing sample", "{\"exploration_points\":[{\"x\":" + ((i + 1) * 8)
                    + ",\"y\":90,\"z\":0}]}");
            tool("move_to", offset + 4);
            assertTrue(client.lastTool().obj("args").get("walk_only").getAsBoolean());
            long seq = client.lastTool().num("seq", -1);
            ack("explore-" + i);
            assertEquals(offset + 4, client.toolCount(), "acceptance must not trigger another scan");
            terminal(seq, "explore-" + i, "{\"arrived\":true}");
            tool("status", offset + 5);
            here.addProperty("x", (i + 1) * 8);
            receipt(true, "actual new observation region", here.toString());
            assertEquals(1, client.engine().responses.size());
        }
        tool("scan_area", 16);
        receipt(true, "entity at the new region", "{\"entity_targets\":[{\"entity_id\":47}],\"no_targets\":false}");
        assertTrue(outcome().ok(), outcome().content());
        assertEquals(16, client.toolCount());
        assertTrue(outcome().content().contains("entity_id"));
        assertFalse(client.sent.stream().anyMatch(e -> e.kind().equals("tool_call")
                && java.util.Set.of("gather", "break_block", "craft").contains(e.str("tool"))));
    }

    @Test void readonlyAndNestedPrimitiveBudgetStillApplyToGenericExploration() {
        start("[只读] find a place", "return mc.call('explore',{})");
        assertFalse(outcome().ok());
        assertEquals(0, client.toolCount());
    }

    @Test void paginatedEmptyResourceSearchReachesTheFifthPageThroughActualRunnerAndLua() {
        start("check the whole observed neighborhood", """
                local offset=0
                for i=1,5 do
                    local page=mc.call("find_resource",{targets={"oak_log"},r=16,offset=offset,
                        scan_center={x=0,y=90,z=0}})
                    offset=page.data.next_offset
                end
                return offset
                """);
        for (int i = 0; i < 5; i++) {
            int offset = i * 4096;
            tool("find_resource", i + 1);
            assertEquals(offset, client.lastTool().obj("args").get("offset").getAsInt());
            var data = new JsonObject();
            data.addProperty("offset", offset);
            data.addProperty("radius", 16);
            data.addProperty("samples_planned", 17077);
            data.addProperty("samples_examined", Math.min(4096, 17077 - offset));
            data.addProperty("next_offset", i == 4 ? -1 : offset + 4096);
            data.add("scan_center", JsonParser.parseString("{\"x\":0,\"y\":90,\"z\":0}"));
            data.addProperty("no_targets", true);
            receipt(true, "actual empty page " + i, data.toString());
        }
        assertTrue(outcome().ok(), outcome().content());
        assertEquals(5, client.toolCount());
        assertEquals(1, client.engine().histories.getLast().stream().filter(Msg.Tool.class::isInstance)
                .map(Msg.Tool.class::cast).filter(t -> t.callId().equals("program")).count());
    }

    @Test void nestedExplorationCannotBypassTheProgramAtomicCallLimit() {
        start("inspect elsewhere", "return mc.call('explore',{})", 2);
        tool("status", 1);
        receipt(true, "departure", BoundedGatherTest.state(0).toString());
        tool("scan_area", 2);
        receipt(true, "sample", "{\"exploration_points\":[{\"x\":8,\"y\":90,\"z\":0}]}");
        assertFalse(outcome().ok());
        assertTrue(outcome().content().contains("ACTION_LIMIT"));
        assertEquals(2, client.toolCount());
    }

    @Test void undergroundSurveyChargesEachSearchPageToTheSameProgramBudget() {
        start("bounded underground survey",
                "return mc.call('explore',{mode='underground',direction='east',target_y=40,targets={'copper_ore'},r=16})", 3);
        tool("status", 1);
        receipt(true, "actual supplies", new BoundedSurveyTest.World().state().toString());
        for (int page = 0; page < 2; page++) {
            tool("find_resource", page + 2);
            receipt(true, "actual page", BoundedSearchTest.page(page * 4096, false).toString());
        }
        assertFalse(outcome().ok());
        assertTrue(outcome().content().contains("ACTION_LIMIT"));
        assertEquals(3, client.toolCount());
        assertTrue(client.sent.stream().noneMatch(e -> "move_to".equals(e.str("tool"))));
    }

    @Test void completeSearchWithinProgramCountsEveryPageAndStopsLaterActionsAtTheLimit() {
        start("search then inspect", """
                mc.call("search_resource",{targets={"oak_log"}})
                return mc.call("inventory",{})
                """, 5);
        for (int page = 0; page < 5; page++) {
            tool("find_resource", page + 1);
            receipt(true, "actual page", BoundedSearchTest.page(page * 4096, page == 4).toString());
        }
        assertFalse(outcome().ok());
        assertTrue(outcome().content().contains("ACTION_LIMIT"));
        assertTrue(outcome().content().contains("实际原子调用=5"));
        assertEquals(5, client.toolCount());
    }

    @Test void completeSearchCannotSendItsThirdPageWhenOnlyTwoPrimitivesRemain() {
        start("bounded search", "return mc.call('search_resource',{targets={'oak_log'}})", 2);
        for (int page = 0; page < 2; page++) {
            tool("find_resource", page + 1);
            receipt(true, "actual empty page", BoundedSearchTest.page(page * 4096, false).toString());
        }
        assertFalse(outcome().ok());
        assertTrue(outcome().content().contains("ACTION_LIMIT"));
        assertEquals(2, client.toolCount());
    }

    @Test void readonlyProgramCanCompleteAFullSearchLocally() {
        start("[只读] query nearby resources", "return mc.call('search_resource',{targets={'oak_log'}})");
        for (int page = 0; page < 5; page++) {
            tool("find_resource", page + 1);
            receipt(true, "actual page", BoundedSearchTest.page(page * 4096, page == 4).toString());
        }
        assertTrue(outcome().ok(), outcome().content());
        assertTrue(outcome().content().contains("minecraft:oak_log"));
        assertEquals(5, client.toolCount());
    }

    @Test void freshScanCoordinatesAndMovementTerminalDriveTheNextAction() {
        start("work at the actual target", """
                local scan=mc.call("find_resource",{targets={"dirt"},work=true})
                local target=scan.data.targets[1]
                if not target then return {blocked="no observed target"} end
                if not target.can_work_here then
                    local p=target.stand_positions[1]
                    mc.call("move_to",{x=p.x,y=p.y,z=p.z,exact=true})
                end
                return mc.call("break_block",{x=target.x,y=target.y,z=target.z})
                """);
        tool("find_resource", 1);
        receipt(true, "real target", "{\"targets\":[{\"x\":7,\"y\":65,\"z\":-3,\"can_work_here\":false,"
                + "\"stand_positions\":[{\"x\":6,\"y\":65,\"z\":-3}]}]}");
        tool("move_to", 2);
        assertEquals(6, client.lastTool().obj("args").get("x").getAsInt());
        long seq = client.lastTool().num("seq", -1);
        ack("moving");
        assertEquals(2, client.toolCount());
        assertEquals(1, client.engine().responses.size());
        terminal(seq + 1, "moving", "{}");
        assertEquals(2, client.toolCount());
        terminal(seq, "moving", "{\"arrived\":true}");
        tool("break_block", 3);
        assertEquals(7, client.lastTool().obj("args").get("x").getAsInt());
        receipt(true, "removed; drops remain", "{\"removed\":true,\"collected_count\":0}");
        assertTrue(outcome().ok());
    }

    @Test void readonlyProgramCanQueryButCannotDispatchAModification() {
        start("[只读] inspect", "mc.call('inventory',{}) mc.call('equip',{slot=3})");
        tool("inventory", 1);
        receipt(true, "read actual inventory", "{}");
        assertFalse(outcome().ok());
        assertEquals(1, client.toolCount());
        assertTrue(outcome().content().contains("只读"));
    }

    @Test void luaReadsOriginAccountingThenChecksActualInventoryBeforeDecidingTheNextAction() {
        start("inspect actual yield", """
                local mined=mc.call("break_block",{x=2,y=90,z=0})
                local origin=mined.data.drop_accounting
                local fresh=mc.call("status",{details=true})
                local actual=0
                for _,s in ipairs(fresh.data.inventory.slots) do
                  if s.item=="minecraft:dirt" then actual=actual+s.count end
                end
                if actual==0 then
                  return {blocked="inventory empty",observed_pickup=origin.picked_up_self_count}
                end
                return mc.call("equip",{slot=0})
                """);
        tool("break_block", 1);
        ack("native-mining");
        assertEquals(1, client.toolCount());
        terminal(client.lastTool().num("seq", -1), "native-mining", "{\"removed\":true,\"drop_accounting_known\":true,"
                + "\"drop_accounting\":{\"spawned_count\":4,\"picked_up_self_count\":4}}");
        tool("status", 2);
        receipt(true, "actual inventory", BoundedGatherTest.state(0).toString());
        assertTrue(outcome().ok());
        assertTrue(outcome().content().contains("inventory empty"));
        assertEquals(2, client.toolCount());
        assertTrue(client.sent.stream().noneMatch(e -> "equip".equals(e.str("tool"))));
    }

    @Test void authorizationFailureReturnsFullActualReceiptAndStopsDependentWork() {
        start("approach then work", "mc.call('move_to',{x=3,y=65,z=2}) mc.call('break_block',{x=4,y=65,z=2})");
        tool("move_to", 1);
        receipt(false, "NEED_CONFIRM:actual complete route", "{\"authorization_id\":\"fixture\",\"changes\":[{\"x\":3,\"y\":65,\"z\":2}]}");
        assertFalse(outcome().ok());
        assertEquals(1, client.toolCount());
        assertTrue(outcome().content().contains("NEED_CONFIRM:actual complete route"));
    }

    @Test void receiptReferencesReachModelHistoryWithoutChangingTheScriptsObservedData() {
        start("read a value", """
                local r=mc.call("inventory",{})
                print("inventory",r.ok,r.feedback)
                assert(r.data.amount==7)
                return {amount=r.data.amount,feedback=r.feedback}
                """);
        tool("inventory", 1);
        receipt(true, "unique inventory receipt", "{\"amount\":7}");
        var result = outcome();
        assertTrue(result.ok(), result.content());
        assertEquals(1, result.content().split("unique inventory receipt", -1).length - 1);
        assertTrue(result.content().contains("见回执#1"));
        assertTrue(result.content().contains("\"amount\":7"));
        assertEquals(1, client.toolCount());
    }

    @Test void nestedWorkflowUsesTheSameTerminalAndPrimitiveBudget() {
        start("query then wait", """
                return mc.call("workflow",{steps={
                    {tool="inventory",args={}},
                    {tool="wait",args={seconds=1}},
                    {tool="inventory",args={}}
                }})
                """, 2);
        tool("inventory", 1);
        receipt(true, "first observation", "{}");
        tool("wait", 2);
        receipt(true, "actual wait complete", "{}");
        assertFalse(outcome().ok());
        assertEquals(2, client.toolCount());
        assertTrue(outcome().content().contains("ACTION_LIMIT:"));
    }

    @Test void nestedMaterialGatherReusesTheExistingLocalLoop() {
        start("prepare material", """
                return mc.call("gather",{item="dirt",targets={"dirt"},count=1,scope="subtask",mode="total"})
                """);
        tool("status", 1);
        receipt(true, "fresh existing material", BoundedGatherTest.state(1).toString());
        assertTrue(outcome().ok(), outcome().content());
        assertEquals(1, client.toolCount());
        assertTrue(outcome().content().contains("采集子目标"));
    }

    @Test void nestedGatherConsentReissueCannotEscapeTheProgramsPrimitiveBudget() {
        start("prepare material", """
                return mc.call("gather",{item="dirt",targets={"dirt"},count=1,scope="subtask"})
                """, 4);
        tool("status", 1);
        receipt(true, "actual inventory", BoundedGatherTest.state(0).toString());
        tool("scan_area", 2);
        receipt(true, "no drops", "{\"item_targets\":[]}");
        tool("find_resource", 3);
        receipt(true, "workable target", "{\"targets\":[" + BoundedGatherTest.block(true) + "]}");
        tool("break_block", 4);
        receipt(false, "NEED_CONFIRM:exact cells",
                "{\"authorization_id\":\"program-plan\",\"authorization_summary\":\"exact server cells\"}");
        assertNotNull(runner.questionSnapshot());
        assertTrue(runner.answerFromPanel(runner.questionSnapshot().id(), "确认"));
        client.drain();
        var grant = client.sent.getLast();
        assertEquals("authorize", grant.kind());
        var body = new JsonObject();
        body.addProperty("seq", grant.num("seq", -1));
        body.addProperty("ok", true);
        body.addProperty("feedback", "approved");
        runner.handleS2c(new Envelope("tool_result", body));
        assertFalse(outcome().ok());
        assertEquals(4, client.toolCount());
        assertTrue(outcome().content().contains("ACTION_LIMIT:"));
        assertNull(runner.questionSnapshot());
    }

    @Test void nestedGatherCannotSilentlyRegisterOrReplaceAFinalGoal() {
        start("prepare material", "return mc.call('gather',{item='dirt',targets={'dirt'},count=1})");
        assertFalse(outcome().ok());
        assertEquals(0, client.toolCount());
        assertTrue(outcome().content().contains("scope=subtask"));
    }

    @Test void cancellingAnAcceptedChildPreventsLateContinuationAndKeepsHistoryPaired() {
        start("wait then craft", "mc.call('wait',{seconds=1}) mc.call('craft',{item='stick',count=4})");
        tool("wait", 1);
        long seq = client.lastTool().num("seq", -1);
        ack("waiting");
        assertTrue(runner.cancelTask(0));
        client.drain();
        terminal(seq, "waiting", "{}");
        runner.submitTask("inspect current facts");
        client.drain();
        assertEquals(1, client.toolCount());
        assertTrue(outcome().content().contains("CANCELLED:"));
        assertEquals(0, JsonParser.parseString(runner.statusJson()).getAsJsonObject().get("pending_jobs").getAsInt());
    }

    @Test void aComputationalLoopYieldsAnErrorWithoutAnyGameDispatch() {
        start("bounded computation", "while true do end");
        assertFalse(outcome().ok());
        assertTrue(outcome().content().startsWith("INSTRUCTION_LIMIT:"));
        assertEquals(0, client.toolCount());
    }

    @Test void invalidDynamicParametersAndForbiddenToolsCannotReachTheServer() {
        start("inspect", "mc.call('inventory',{}) mc.call('equip',{slot='3'}) mc.call('attack',{})");
        tool("inventory", 1);
        receipt(true, "fresh inventory", "{}");
        assertFalse(outcome().ok());
        assertEquals(1, client.toolCount());
        assertTrue(outcome().content().contains("Schema"));
        assertTrue(outcome().content().contains("args.slot"));
        assertTrue(outcome().content().contains("integer"));
    }

    @Test void nestedMoveArgumentErrorsExplainTheMissingCoordinateWithoutDispatch() {
        start("approach work", "mc.call('move_to',{x=2,y=90,z=0,exact=true,work_target={x=3,y=90}})");
        var result = outcome();
        assertFalse(result.ok());
        assertTrue(result.content().contains("move_to"));
        assertTrue(result.content().contains("args.work_target.z"));
        assertTrue(result.content().contains("缺少必要参数"));
        assertEquals(0, client.toolCount());
    }

    @Test void declaredEnumsAreExplainedWithoutEchoingInvalidArgumentValues() {
        start("place", "mc.call('place_block',{x=2,y=90,z=0,item='dirt',face='private-value'})");
        var result = outcome();
        assertFalse(result.ok());
        assertTrue(result.content().contains("args.face"));
        assertTrue(result.content().contains("允许值="));
        assertTrue(result.content().contains("up"));
        assertFalse(result.content().contains("private-value"));
        assertEquals(0, client.toolCount());
    }

    @Test void negativePlacementQueryCanBeUsedToSelectAnExplicitAlternativeInLua() {
        start("choose workstation site", """
                local q=mc.call("place_block",{x=2,y=90,z=0,item="crafting_table",query=true})
                if not q.data.preflight_passed then
                  local p=q.data.alternatives[1]
                  if p then return mc.call("place_block",{x=p.x,y=p.y,z=p.z,item="crafting_table"}) end
                  return {blocked=true}
                end
                """);
        tool("place_block", 1);
        receipt(true, "query only", "{\"query\":true,\"preflight_passed\":false,"
                + "\"alternatives\":[{\"x\":3,\"y\":90,\"z\":0}]}");
        tool("place_block", 2);
        assertEquals(3, client.lastTool().obj("args").get("x").getAsInt());
        receipt(true, "native placement confirmed", "{\"changed\":true,\"consumed_count\":1}");
        assertTrue(outcome().ok());
        assertEquals(2, client.toolCount());
    }

    private static final String PLACE_AFTER_WORK = """
            local q=mc.call("place_block",{x=2,y=90,z=0,item="cobblestone",query=true})
            if not q.data.preflight_passed then
              local m=mc.call("move_to",{x=2,y=90,z=0,exact=true,arrival="work",
                work_target={x=2,y=90,z=0},place_item="cobblestone",face="up",walk_only=true})
              if not m.data.can_work_here then return {blocked=true} end
            end
            return mc.call("place_block",{x=2,y=90,z=0,item="cobblestone",face="up"})
            """;

    @Test void exactPlacementUsesTheTrueWorkTerminalAndKeepsTheOriginalTargetInOneModelTurn() {
        start("place at the specified cell", PLACE_AFTER_WORK);
        tool("place_block", 1);
        receipt(true, "target blocked by own body", "{\"preflight_passed\":false,\"stand_positions\":["
                + "{\"x\":1,\"y\":90,\"z\":0,\"feet_y\":90.5}],\"alternatives\":[{\"x\":3,\"y\":90,\"z\":0}]}");
        tool("move_to", 2);
        assertEquals("cobblestone", client.lastTool().obj("args").get("place_item").getAsString());
        assertEquals(2, client.lastTool().obj("args").get("x").getAsInt());
        long seq = client.lastTool().num("seq", -1);
        ack("placement-work");
        assertEquals(2, client.toolCount(), "ack cannot authorize dependent placement");
        terminal(seq, "placement-work", "{\"can_work_here\":true,\"work_kind\":\"place\",\"x\":1,\"feet_y\":90.5}");
        tool("place_block", 3);
        assertEquals(2, client.lastTool().obj("args").get("x").getAsInt());
        assertEquals(1, client.engine().responses.size());
        receipt(true, "actual single placement", "{\"changed\":true,\"consumed_count\":1}");
        assertTrue(outcome().ok(), outcome().content());
        assertEquals(3, client.toolCount());
    }

    @Test void cancelledPlacementApproachNeverPlacesAfterALateSuccessfulTerminal() {
        start("place at the specified cell", PLACE_AFTER_WORK);
        tool("place_block", 1);
        receipt(true, "not currently workable", "{\"preflight_passed\":false}");
        tool("move_to", 2);
        long seq = client.lastTool().num("seq", -1);
        ack("placement-work");
        assertTrue(runner.cancelTask(0));
        client.drain();
        terminal(seq, "placement-work", "{\"can_work_here\":true}");
        runner.submitTask("inspect current facts");
        client.drain();
        assertEquals(2, client.toolCount());
        assertTrue(outcome().content().contains("CANCELLED:"));
    }

    @Test void aNegativeFreshWorkTerminalCannotTriggerPlacement() {
        start("place at the specified cell", PLACE_AFTER_WORK);
        tool("place_block", 1);
        receipt(true, "not currently workable", "{\"preflight_passed\":false}");
        tool("move_to", 2);
        ack("placement-work");
        terminal(client.lastTool().num("seq", -1), "placement-work", "{\"can_work_here\":false}");
        assertTrue(outcome().ok(), outcome().content());
        assertEquals(2, client.toolCount());
        assertTrue(outcome().content().contains("\"blocked\":true"));
    }

    @Test void cannotNestProgramsOrUseOwnerQuestionsOrGoalMutation() {
        start("inspect", "return mc.call('set_goal',{item='dirt',count=1})");
        assertFalse(outcome().ok());
        assertEquals(0, client.toolCount());
        assertTrue(outcome().content().contains("最终目标"));
    }

    @Test void sharedAttackAcceptsTheDeclaredIntegerAndSelectorVariants() {
        start("local attack", """
                mc.call("attack",{entity_id=37,target_uuid="00000000-0000-0000-0000-000000000037",max_hits=1})
                return mc.call("attack",{entity_id="hostile_nearby",max_hits=1})
                """);
        tool("attack", 1);
        receipt(true, "actual first attack", "{\"strikes\":1}");
        tool("attack", 2);
        receipt(true, "actual nearby attack", "{\"strikes\":1}");
        assertTrue(outcome().ok(), outcome().content());
    }

    @Test void timeoutCancelsAcceptedChildAndNeverSendsADependentAction() {
        start("wait then craft", "mc.call('wait',{seconds=1}) mc.call('craft',{item='stick',count=4})");
        tool("wait", 1);
        long seq = client.lastTool().num("seq", -1);
        ack("waiting");
        client.now += 300000;
        runner.tick();
        client.drain();
        assertFalse(outcome().ok());
        assertTrue(outcome().content().startsWith("TIMEOUT:"));
        terminal(seq, "waiting", "{}");
        assertEquals(1, client.toolCount());
    }

    @Test void programPhaseSuccessCannotReplaceTheFinalPhysicalItemCheck() {
        client.liveObservation = true;
        runner.start();
        runner.submitTask("制作一把木镐");
        client.drain();
        receipt(true, "baseline", BoundedGatherTest.state(0).toString());
        client.engine().tools(0, new ToolCall("goal", "set_goal", "{\"item\":\"wooden_pickaxe\",\"count\":1}"));
        client.drain();
        receipt(true, "fresh before program", BoundedGatherTest.state(0).toString());
        program(1, "return mc.call('craft',{item='stick',count=4})", 128);
        until(() -> client.toolCount() == 3);
        receipt(true, "materials only", "{\"crafted_count\":4}");
        until(() -> client.toolCount() == 4);
        receipt(true, "materials observed", BoundedGatherTest.state(4).toString());
        assertEquals(3, client.engine().responses.size());
        client.engine().reply(2, "finished");
        client.drain();
        receipt(true, "final item missing", BoundedGatherTest.state(4).toString());
        receipt(true, "fresh continuation", BoundedGatherTest.state(4).toString());
        assertEquals(4, client.engine().responses.size());
        assertNotEquals(0, JsonParser.parseString(runner.statusJson()).getAsJsonObject().get("current_task").getAsLong());
        assertTrue(client.engine().histories.getLast().stream().anyMatch(m ->
                m instanceof Msg.Nudge n && n.text().contains("minecraft:wooden_pickaxe")));
        client.engine().reply(3, "finished");
        client.drain();
        var finalState = BoundedGatherTest.state(4);
        var item = finalState.getAsJsonObject("inventory").getAsJsonArray("slots").get(1).getAsJsonObject();
        item.addProperty("item", "minecraft:wooden_pickaxe");
        item.addProperty("count", 1);
        receipt(true, "actual final item", finalState.toString());
        assertEquals(0, JsonParser.parseString(runner.statusJson()).getAsJsonObject().get("current_task").getAsLong());
    }

    @Test void recipeBackedAlternateWoodSubtaskCannotCompleteTheFinalIronPickaxeGoal() {
        client.liveObservation = true;
        runner.start();
        runner.submitTask("制作一把铁镐");
        client.drain();
        receipt(true, "baseline", BoundedGatherTest.state(0).toString());
        client.engine().tools(0, new ToolCall("goal", "set_goal",
                "{\"item\":\"iron_pickaxe\",\"count\":1,\"mode\":\"total\"}"));
        client.drain();
        receipt(true, "fresh before program", BoundedGatherTest.state(0).toString());
        program(1, """
                return mc.call("gather",{count=2,scope="subtask",mode="total",
                    material_for={item="crafting_table",ingredient=0,sources=true}})
                """, 128);
        tool("craft", 3);
        assertTrue(client.lastTool().obj("args").get("query").getAsBoolean());
        receipt(true, "actual recipe alternatives", """
                {"material":{"truncated":false,"sources":[
                    {"item":"minecraft:oak_log","block":"minecraft:oak_log"},
                    {"item":"minecraft:birch_log","block":"minecraft:birch_log"}]}}
                """);
        tool("status", 4);
        var wood = BoundedGatherTest.state(0);
        var slot = wood.getAsJsonObject("inventory").getAsJsonArray("slots").get(1).getAsJsonObject();
        slot.addProperty("item", "minecraft:birch_log");
        slot.addProperty("count", 2);
        receipt(true, "existing alternate material", wood.toString());
        until(() -> client.toolCount() == 5);
        receipt(true, "materials observed", wood.toString());
        assertTrue(outcome().ok(), outcome().content());
        assertEquals(3, client.engine().responses.size());
        client.engine().reply(2, "finished");
        client.drain();
        receipt(true, "final item still missing", wood.toString());
        receipt(true, "fresh continuation", wood.toString());
        assertEquals(4, client.engine().responses.size());
        assertTrue(client.engine().histories.getLast().stream().anyMatch(m ->
                m instanceof Msg.Nudge n && n.text().contains("minecraft:iron_pickaxe")));
        client.engine().reply(3, "finished");
        client.drain();
        var actualFinal = wood.deepCopy();
        var finalSlot = actualFinal.getAsJsonObject("inventory").getAsJsonArray("slots").get(2).getAsJsonObject();
        finalSlot.addProperty("item", "minecraft:iron_pickaxe");
        finalSlot.addProperty("count", 1);
        receipt(true, "actual final item", actualFinal.toString());
        assertEquals(0, JsonParser.parseString(runner.statusJson()).getAsJsonObject().get("current_task").getAsLong());
    }
}
