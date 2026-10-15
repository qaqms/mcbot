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
                .map(Msg.Tool.class::cast).filter(t -> t.callId().equals("program")).findFirst().orElseThrow();
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

    @Test void authorizationFailureReturnsFullActualReceiptAndStopsDependentWork() {
        start("approach then work", "mc.call('move_to',{x=3,y=65,z=2}) mc.call('break_block',{x=4,y=65,z=2})");
        tool("move_to", 1);
        receipt(false, "NEED_CONFIRM:actual complete route", "{\"authorization_id\":\"fixture\",\"changes\":[{\"x\":3,\"y\":65,\"z\":2}]}");
        assertFalse(outcome().ok());
        assertEquals(1, client.toolCount());
        assertTrue(outcome().content().contains("NEED_CONFIRM:actual complete route"));
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
