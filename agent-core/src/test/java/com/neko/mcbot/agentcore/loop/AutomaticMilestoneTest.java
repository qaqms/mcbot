package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AutomaticMilestoneTest {
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static ToolExecutor.ToolOutcome receipt(JsonObject data) {
        return new ToolExecutor.ToolOutcome(true, "actual result", data);
    }
    private JsonObject body(int count) {
        var state = AgentLoopGoalTest.state(count);
        state.getAsJsonObject("inventory").getAsJsonArray("slots").get(0).getAsJsonObject()
                .addProperty("item", "minecraft:oak_log");
        state.addProperty("dimension", "minecraft:overworld");
        state.addProperty("body_id", "body-one");
        state.addProperty("alive", true);
        return state;
    }
    private JsonObject collection() {
        var goal = new ItemGoal(new ItemGoal.Target("minecraft:oak_log", 10, false), body(0));
        goal.observe(body(10));
        var data = new JsonObject();
        data.addProperty("goal_satisfied", true);
        data.add("collection_goal", goal.evidence());
        data.add("last_observation", body(10));
        return data;
    }
    private JsonObject craft(String item) {
        return json("""
                {"query":false,"can_craft":true,"crafted_count":1,"output_inventory_count":1,
                 "output_per_batch":{"item":"%s","count":1,"mining_tool":true,"max_damage":59},
                 "body_delta":{"body_id":"body-one","same_world":true,"observation_known":true,
                 "dimension_after":"minecraft:overworld"}}
                """.formatted(item));
    }
    @Test void collectionVerifiesAllSlotsAndKeepsOneIdentityForRepeatedResourceStages() {
        var data = collection();
        var report = AutomaticMilestone.collection(receipt(data), "body-one", "minecraft:overworld");
        assertEquals("item", report.get("kind").getAsString());
        assertTrue(report.get("facts").getAsString().contains("10"));
        data.getAsJsonObject("collection_goal").addProperty("count", 5);
        assertEquals(report.get("id"), AutomaticMilestone.collection(receipt(data), "body-one",
                "minecraft:overworld").get("id"));
        data.getAsJsonObject("last_observation").getAsJsonObject("inventory").getAsJsonArray("slots").remove(35);
        assertThrows(RuntimeException.class, () -> AutomaticMilestone.collection(receipt(data),
                "body-one", "minecraft:overworld"));
    }
    @Test void staleDeathOrUnsatisfiedCollectionNeverBecomesAnAchievement() {
        assertNull(AutomaticMilestone.collection(receipt(collection()), "other", "minecraft:overworld"));
        assertNull(AutomaticMilestone.collection(receipt(collection()), "body-one", "minecraft:the_nether"));
        var data = collection();
        data.getAsJsonObject("last_observation").addProperty("alive", false);
        assertNull(AutomaticMilestone.collection(receipt(data), "body-one", "minecraft:overworld"));
        data = collection(); data.addProperty("goal_satisfied", false);
        assertNull(AutomaticMilestone.collection(receipt(data), "body-one", "minecraft:overworld"));
        data = collection(); data.getAsJsonObject("collection_goal").addProperty("current", 20);
        assertNull(AutomaticMilestone.collection(receipt(data), "body-one", "minecraft:overworld"));
    }
    @Test void materialAlternativesCountActualItemsWithoutClaimingConvertedOutput() {
        var data = collection();
        var proof = data.getAsJsonObject("collection_goal");
        proof.getAsJsonArray("accepted_items").add("minecraft:birch_log");
        var report = AutomaticMilestone.collection(receipt(data), "body-one", "minecraft:overworld");
        assertNotNull(report);
        assertTrue(report.get("facts").getAsString().contains("minecraft:birch_log"));
        proof.getAsJsonArray("accepted_items").add("oak_log");
        assertNull(AutomaticMilestone.collection(receipt(data), "body-one", "minecraft:overworld"));
    }
    @Test void genericCraftCapabilitiesDoNotDependOnVanillaItemNames() {
        for (String item : java.util.List.of("minecraft:wooden_pickaxe", "minecraft:iron_pickaxe",
                "example:precision_drill")) {
            var report = AutomaticMilestone.crafting(receipt(craft(item)), "body-one", "minecraft:overworld");
            assertNotNull(report);
            assertEquals(item, report.getAsJsonObject("evidence").get("item").getAsString());
        }
        var armor = craft("example:helmet");
        armor.getAsJsonObject("output_per_batch").addProperty("mining_tool", false);
        assertNotNull(AutomaticMilestone.crafting(receipt(armor), "body-one", "minecraft:overworld"));
    }
    @Test void intermediateMaterialsQueriesFailedAndAcceptedCraftsStaySilent() {
        var material = craft("minecraft:stick");
        material.getAsJsonObject("output_per_batch").addProperty("mining_tool", false);
        material.getAsJsonObject("output_per_batch").remove("max_damage");
        assertNull(AutomaticMilestone.crafting(receipt(material), "body-one", "minecraft:overworld"));
        var data = craft("minecraft:wooden_pickaxe"); data.addProperty("query", true);
        assertNull(AutomaticMilestone.crafting(receipt(data), "body-one", "minecraft:overworld"));
        assertNull(AutomaticMilestone.crafting(new ToolExecutor.ToolOutcome(false, "failed", craft("minecraft:wooden_pickaxe")),
                "body-one", "minecraft:overworld"));
        assertNull(AutomaticMilestone.crafting(ToolExecutor.ToolOutcome.accepted("j1", "accepted"),
                "body-one", "minecraft:overworld"));
    }
    @Test void unknownOrWrongBodyCraftAndMissingStoredOutputNeverSpeak() {
        var data = craft("minecraft:wooden_pickaxe");
        data.getAsJsonObject("body_delta").addProperty("observation_known", false);
        assertNull(AutomaticMilestone.crafting(receipt(data), "body-one", "minecraft:overworld"));
        assertNull(AutomaticMilestone.crafting(receipt(craft("minecraft:wooden_pickaxe")), "replacement",
                "minecraft:overworld"));
        data = craft("minecraft:wooden_pickaxe"); data.addProperty("output_inventory_count", 0);
        assertNull(AutomaticMilestone.crafting(receipt(data), "body-one", "minecraft:overworld"));
    }
}
