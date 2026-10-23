package com.neko.mcbot.agent;

import com.google.gson.*;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class GatherMaterialTest {
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static JsonObject state(int oak,int birch,int unrelated) {
        var state=BoundedGatherTest.state(0);
        var slots=state.getAsJsonObject("inventory").getAsJsonArray("slots");
        String[] names={"oak_log","birch_log","stone"};
        int[] counts={oak,birch,unrelated};
        for(int i=0;i<3;i++) {
            slots.get(i).getAsJsonObject().addProperty("item","minecraft:"+names[i]);
            slots.get(i).getAsJsonObject().addProperty("count",counts[i]);
        }
        return state;
    }
    private static final String ARGS="{\"scope\":\"subtask\",\"count\":2,\"material_for\":"
            + "{\"item\":\"crafting_table\",\"ingredient\":0,\"sources\":true}}";
    @Test void recipeVerifiedBirchAndOakCountTogetherButUnrelatedInventoryNeverSatisfiesMaterialGoal() {
        var queue=new ArrayDeque<Runnable>();
        int[] mined={0};
        var seen=new ArrayList<String>();
        var gather=new BoundedGather(BoundedGather.parse(ARGS),state(0,0,0),()->0,(name,text)-> {
            seen.add(name);
            var data=new JsonObject();
            switch(name) {
                case "craft" -> {
                    var args=json(text);
                    assertTrue(args.get("query").getAsBoolean());
                    assertEquals(0,args.get("material_index").getAsInt());
                    data=json("{\"material\":{\"truncated\":false,\"sources\":["
                            + "{\"item\":\"minecraft:oak_log\",\"block\":\"minecraft:oak_log\"},"
                            + "{\"item\":\"minecraft:birch_log\",\"block\":\"minecraft:birch_log\"}]}}");
                }
                case "status" -> data=state(0,mined[0],64);
                case "scan_area" -> {
                    assertFalse(json(text).has("item"),"do not filter out alternative drops");
                    data.add("item_targets",new JsonArray());
                }
                case "find_resource" -> {
                    assertEquals(2,json(text).getAsJsonArray("targets").size());
                    var target=BoundedGatherTest.block(true);
                    target.addProperty("block","minecraft:birch_log");
                    var targets=new JsonArray();targets.add(target);data.add("targets",targets);
                }
                case "break_block" -> {
                    assertEquals("minecraft:birch_log",json(text).get("expected_block").getAsString());
                    mined[0]++;data.addProperty("removed",true);
                }
                default -> fail(name);
            }
            return CompletableFuture.completedFuture(new ToolOutcome(true,"actual receipt",data));
        },queue::add,()->fail("unexpected cancel"),text->{});
        var result=gather.start();
        while(!queue.isEmpty()) queue.removeFirst().run();
        assertTrue(result.join().ok(),result.join().feedback());
        assertEquals(2,mined[0]);
        assertEquals(2,result.join().data().get("actual_progress").getAsInt());
        assertEquals("craft",seen.getFirst());
        assertTrue(result.join().feedback().contains("未自动转换"));
    }
    @Test void taskGoalsAndManualSourceOverridesCannotEnableRecipeAlternatives() {
        for(String text:List.of(ARGS.replace("\"subtask\"","\"task\""),
                ARGS.replace("\"count\":2","\"count\":2,\"item\":\"oak_log\""),
                ARGS.replace("\"count\":2","\"count\":2,\"targets\":[\"oak_log\"]")))
            assertThrows(RuntimeException.class,()->BoundedGather.parse(text));
    }

    @Test void existingRecipeAlternativeSatisfiesDefaultMaterialSubtaskBeforeAnyWorldAction() {
        var calls = new ArrayList<String>();
        var gather = new BoundedGather(BoundedGather.parse(ARGS), state(0,0,0), () -> 0, (name,text) -> {
            calls.add(name);
            var data = switch (name) {
                case "craft" -> json("{\"material\":{\"truncated\":false,\"sources\":["
                        + "{\"item\":\"minecraft:oak_log\",\"block\":\"minecraft:oak_log\"},"
                        + "{\"item\":\"minecraft:birch_log\",\"block\":\"minecraft:birch_log\"}]}}");
                case "status" -> state(0,2,64);
                default -> throw new AssertionError("existing materials must avoid world actions: " + name);
            };
            return CompletableFuture.completedFuture(new ToolOutcome(true, "fresh", data));
        }, Runnable::run, () -> fail("no child"), text -> {});
        var result = gather.start().join();
        assertTrue(result.ok(), result.feedback());
        assertEquals(2, result.data().get("actual_progress").getAsInt());
        assertEquals(List.of("craft", "status"), calls);
    }
    @Test void incompleteRecipeOptionsStopBeforeAnyWorldAction() {
        var queue=new ArrayDeque<Runnable>();
        var gather=new BoundedGather(BoundedGather.parse(ARGS),state(0,0,0),()->0,(name,text)->{
            assertEquals("craft",name);
            return CompletableFuture.completedFuture(new ToolOutcome(true,"partial",
                    json("{\"material\":{\"truncated\":true}}")));
        },queue::add,()->{},text->{});
        var result=gather.start();
        while(!queue.isEmpty())queue.removeFirst().run();
        assertFalse(result.join().ok());
        assertTrue(result.join().feedback().startsWith("MATERIAL_UNAVAILABLE:"));
    }

    @Test void moreThanEightSourcesCompleteEveryBatchBeforeChoosingToExplore() {
        var queue = new ArrayDeque<Runnable>();
        var searches = new ArrayList<JsonObject>();
        int[] mined = {0};
        String args = ARGS.replace("\"count\":2", "\"count\":1,\"explore_steps\":0");
        var gather = new BoundedGather(BoundedGather.parse(args), state(0,0,0), () -> 0, (name,text) -> {
            JsonObject data = new JsonObject();
            switch (name) {
                case "craft" -> {
                    var options = new JsonArray();
                    for (int i = 0; i < 10; i++) options.add(json("{\"item\":\"test:source_" + i
                            + "\",\"block\":\"test:source_" + i + "\"}"));
                    var material = new JsonObject();
                    material.addProperty("truncated",false);
                    material.add("sources", options); data.add("material",material);
                }
                case "status" -> {
                    data = state(0,0,64);
                    var slot = data.getAsJsonObject("inventory").getAsJsonArray("slots").get(0).getAsJsonObject();
                    slot.addProperty("item","test:source_9");
                    slot.addProperty("count",mined[0]);
                }
                case "scan_area" -> data.add("item_targets", new JsonArray());
                case "find_resource" -> {
                    var request = json(text);
                    searches.add(request);
                    int offset = request.get("offset").getAsInt();
                    data = BoundedSearchTest.page(offset,false);
                    data.getAsJsonObject("scan_center").addProperty("y",90);
                    if (request.getAsJsonArray("targets").size() == 2 && offset == 16384) {
                        var block = BoundedGatherTest.block(true);
                        block.addProperty("block","test:source_9");
                        data.getAsJsonArray("targets").add(block);
                    }
                }
                case "break_block" -> { mined[0]++; data.addProperty("removed",true); }
                default -> fail(name);
            }
            return CompletableFuture.completedFuture(new ToolOutcome(true,"actual receipt",data));
        },queue::add,()->fail("unexpected cancellation"),text->{});
        var result = gather.start();
        while (!queue.isEmpty()) queue.removeFirst().run();
        assertTrue(result.join().ok(), result.join().feedback());
        assertEquals(10, searches.size());
        for (int i = 0; i < searches.size(); i++) {
            assertEquals(i < 5 ? 8 : 2, searches.get(i).getAsJsonArray("targets").size());
            assertEquals((i % 5) * 4096, searches.get(i).get("offset").getAsInt());
        }
        assertEquals(1,mined[0]);
    }
}
