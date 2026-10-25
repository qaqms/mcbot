package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MilestoneReportTest {
    private JsonObject item(String mode, int count) {
        return WorldGoalTest.json("{\"type\":\"item\",\"item\":\"dirt\",\"mode\":\"" + mode + "\",\"count\":" + count + "}");
    }

    private JsonObject body(int count) {
        var state = AgentLoopGoalTest.state(count);
        state.getAsJsonObject("inventory").getAsJsonArray("slots").get(0).getAsJsonObject()
                .addProperty("item", "minecraft:dirt");
        state.addProperty("dimension", "minecraft:overworld");
        state.addProperty("body_id", "body-one");
        state.addProperty("alive", true);
        state.addProperty("on_ground", true);
        state.addProperty("x", 0); state.addProperty("y", 70); state.addProperty("z", 0);
        return state;
    }

    @Test void existingItemsDoNotBecomeTaskGainsAndMissingInventoryCannotProveSuccess() {
        var initial = body(5);
        var available = MilestoneReport.verify(item("total", 5), initial, body(5), "body-one");
        assertTrue(available.get("verified").getAsBoolean());
        assertEquals(5, available.getAsJsonObject("evidence").get("current").getAsInt());
        assertThrows(RuntimeException.class, () -> MilestoneReport.verify(item("additional", 1), initial, body(5), "body-one"));
        var gain = MilestoneReport.verify(item("additional", 2), initial, body(7), "body-one");
        assertEquals(2, gain.getAsJsonObject("evidence").get("progress").getAsInt());
        var incomplete = body(7);
        incomplete.getAsJsonObject("inventory").getAsJsonArray("slots").remove(35);
        assertThrows(RuntimeException.class, () -> MilestoneReport.verify(item("total", 1), initial, incomplete, "body-one"));
    }

    @Test void bodyDeathReplacementAndDimensionChangesCannotSupplyMilestones() {
        for (String field : java.util.List.of("body_id", "dimension", "alive")) {
            var fresh = body(10);
            if (field.equals("alive")) fresh.addProperty(field, false);
            else fresh.addProperty(field, "different");
            assertThrows(RuntimeException.class, () -> MilestoneReport.verify(item("total", 1), body(0), fresh, "body-one"));
        }
    }

    @Test void canonicalIdentityDoesNotDependOnJsonOrderAliasesOrDefaultFields() {
        var first = WorldGoalTest.json("{\"type\":\"item\",\"item\":\"dirt\",\"count\":1}");
        var second = WorldGoalTest.json("{\"mode\":\"additional\",\"count\":1.0,\"item\":\"minecraft:dirt\",\"type\":\"item\"}");
        assertEquals(MilestoneReport.id(first), MilestoneReport.id(second));
        assertNotEquals(MilestoneReport.id(first), MilestoneReport.id(item("total", 1)));
        assertNotEquals(MilestoneReport.id(first), MilestoneReport.id(item("additional", 2)));
    }

    @Test void buildingRequiresEveryCellKnownStableAndSatisfied() {
        var spec = WorldGoalTest.building();
        assertThrows(RuntimeException.class, () -> MilestoneReport.verify(spec, body(0), WorldGoalTest.buildState(19), "body-one"));
        assertTrue(MilestoneReport.verify(spec, body(0), WorldGoalTest.buildState(20), "body-one").get("verified").getAsBoolean());
        for (String flag : java.util.List.of("observation_complete", "spatial_stable", "budget_exhausted")) {
            var fresh = WorldGoalTest.buildState(20);
            fresh.getAsJsonObject("goal_evidence").addProperty(flag, flag.equals("budget_exhausted"));
            assertThrows(RuntimeException.class, () -> MilestoneReport.verify(spec, body(0), fresh, "body-one"));
        }
        var changed = WorldGoalTest.buildState(20);
        changed.getAsJsonObject("goal_evidence").addProperty("target_revision", "b".repeat(64));
        assertThrows(RuntimeException.class, () -> MilestoneReport.verify(spec, body(0), changed, "body-one"));
    }

    @Test void positionRequiresExactDimensionGroundingAndSphericalRadius() {
        var spec = WorldGoalTest.json("{\"type\":\"position\",\"dimension\":\"minecraft:overworld\",\"x\":1,\"y\":70,\"z\":0,\"radius\":1}");
        assertEquals("position", MilestoneReport.verify(spec, body(0), body(0), "body-one").get("kind").getAsString());
        var diagonal = body(0); diagonal.addProperty("z", 1);
        assertThrows(RuntimeException.class, () -> MilestoneReport.verify(spec, body(0), diagonal, "body-one"));
        var falling = body(0); falling.addProperty("on_ground", false);
        assertThrows(RuntimeException.class, () -> MilestoneReport.verify(spec, body(0), falling, "body-one"));
        var wrongDimension = spec.deepCopy(); wrongDimension.addProperty("dimension", "minecraft:the_nether");
        assertThrows(RuntimeException.class, () -> MilestoneReport.verify(wrongDimension, body(0), body(0), "body-one"));
    }

    @Test void labelsAndUnknownPredicatesCannotMasqueradeAsVerifiedAchievements() {
        assertThrows(RuntimeException.class, () -> MilestoneReport.condition(WorldGoalTest.json("{\"type\":\"text\",\"text\":\"done\"}")));
        var spec = item("total", 1); spec.addProperty("text", "I crafted it");
        assertThrows(RuntimeException.class, () -> MilestoneReport.condition(spec));
    }
}
