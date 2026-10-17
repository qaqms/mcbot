package com.neko.mcbot.agent;

import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.provider.OpenAiCompatProvider;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolCatalogTest {
    @Test void blueprintInspectionAndExplicitImportExposeTheSharedDesignEntry() {
        var help=ToolCatalog.read("{\"names\":[\"build_blueprint\"]}");
        assertTrue(help.ok(),help.feedback());
        assertTrue(help.feedback().contains("source_revision"));
        assertTrue(help.feedback().contains(".nbt"));
    }
    @Test void durableBuildingRecordsAreDiscoverableAndUseTheSharedTargetReference() {
        var help = ToolCatalog.read("{\"names\":[\"build_record\"]}");
        assertTrue(help.ok(), help.feedback());
        for (String name : List.of("build_diff", "build_nearby", "build_site")) {
            var spec = ClientToolDefs.SPECS.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
            assertTrue(spec.paramsJsonSchema().contains("instance_id"));
        }
        assertTrue(help.feedback().contains("instance_revision"));
    }
    @Test void constructionHelperAndTemporaryEngineeringAreDiscoverableInTheActualCatalog() {
        var help=ToolCatalog.read("{\"names\":[\"build_site\",\"run_program\",\"place_block\"]}");
        assertTrue(help.ok(),help.feedback());
        assertTrue(help.feedback().contains("mc.build"));
        assertTrue(help.feedback().contains("temporary_purpose"));
        for(String name:List.of("break_block","place_block","move_to")) {
            var spec=ClientToolDefs.SPECS.stream().filter(s->s.name().equals(name)).findFirst().orElseThrow();
            var properties=JsonParser.parseString(spec.paramsJsonSchema()).getAsJsonObject().getAsJsonObject("properties");
            assertTrue(properties.has(name.equals("break_block")?"site_revision":"temporary_purpose"));
        }
        var site=ToolCatalog.MODEL_SPECS.stream().filter(s->s.name().equals("build_site")).findFirst().orElseThrow();
        assertTrue(site.description().contains("mc.build"));
    }
    @Test void nearbyConstructionIsABoundedActionUsingTheSameTargetSchema() {
        var help = ToolCatalog.read("{\"names\":[\"build_nearby\"]}");
        assertTrue(help.ok(), help.feedback());
        var action = ClientToolDefs.SPECS.stream().filter(s -> s.name().equals("build_nearby"))
                .findFirst().orElseThrow();
        var query = ClientToolDefs.SPECS.stream().filter(s -> s.name().equals("build_diff"))
                .findFirst().orElseThrow();
        var actionProperties = JsonParser.parseString(action.paramsJsonSchema()).getAsJsonObject()
                .getAsJsonObject("properties");
        assertEquals(JsonParser.parseString(query.paramsJsonSchema()).getAsJsonObject()
                .getAsJsonObject("properties").get("target"), actionProperties.get("target"));
        assertEquals(12, actionProperties.getAsJsonObject("max_actions").get("maximum").getAsInt());
        assertFalse(com.neko.mcbot.agentcore.loop.TaskPolicy.observation("build_nearby", new com.google.gson.JsonObject()));
    }

    @Test void buildingQueryHasOneReadOnlyContractWithTargetAndMaterialPagination() {
        var help = ToolCatalog.read("{\"names\":[\"build_diff\"]}");
        assertTrue(help.ok(), help.feedback());
        var spec = ClientToolDefs.SPECS.stream().filter(s -> s.name().equals("build_diff"))
                .findFirst().orElseThrow();
        var properties = JsonParser.parseString(spec.paramsJsonSchema()).getAsJsonObject()
                .getAsJsonObject("properties");
        assertTrue(properties.has("target"));
        assertTrue(properties.has("offset"));
        assertTrue(properties.has("material_offset"));
        assertTrue(com.neko.mcbot.agentcore.loop.TaskPolicy.observation("build_diff", new com.google.gson.JsonObject()));
    }

    @Test void compactCatalogKeepsEverySchemaAndStableOrderWhileReducingActualWireBytes() {
        assertEquals(ClientToolDefs.SPECS.stream().map(s -> s.name()).toList(),
                ToolCatalog.MODEL_SPECS.stream().map(s -> s.name()).toList());
        for (int i = 0; i < ClientToolDefs.SPECS.size(); i++)
            assertEquals(ClientToolDefs.SPECS.get(i).paramsJsonSchema(),
                    ToolCatalog.MODEL_SPECS.get(i).paramsJsonSchema());
        var provider = new OpenAiCompatProvider("offline", "http://127.0.0.1", "synthetic", "fixture");
        int full = provider.buildBody("system", List.of(), ClientToolDefs.SPECS).toString()
                .getBytes(StandardCharsets.UTF_8).length;
        int compact = provider.buildBody("system", List.of(), ToolCatalog.MODEL_SPECS).toString()
                .getBytes(StandardCharsets.UTF_8).length;
        assertTrue(compact < full * 0.75, compact + " vs " + full);
        System.out.println("tool_catalog_bytes full=" + full + " compact=" + compact);
    }

    @Test void helpReturnsExactDefinitionsFromTheValidationRegistryIncludingNestedRules() {
        var result = ToolCatalog.read("{\"names\":[\"move_to\",\"craft\",\"run_program\"]}");
        assertTrue(result.ok(), result.feedback());
        var definitions = result.data().getAsJsonArray("definitions");
        assertEquals(3, definitions.size());
        for (var value : definitions) {
            var definition = value.getAsJsonObject();
            var spec = ClientToolDefs.SPECS.stream().filter(s ->
                    s.name().equals(definition.get("name").getAsString())).findFirst().orElseThrow();
            assertEquals(spec.description(), definition.get("description").getAsString());
            assertEquals(JsonParser.parseString(spec.paramsJsonSchema()), definition.get("parameters"));
            assertEquals(ClientToolDefs.resultGuide(spec.name()), definition.get("returns").getAsString());
        }
        assertTrue(result.feedback().contains("mc.try_call"));
    }

    @Test void resultHelpExplainsActualFieldShapesAndTheProgramEnvironment() {
        var result = ToolCatalog.read("{\"names\":[\"status\",\"inspect_block\",\"craft\"]}");
        assertTrue(result.ok());
        assertTrue(result.feedback().contains("data.x/y/z"));
        assertTrue(result.feedback().contains("data.block是方块ID字符串"));
        assertTrue(result.feedback().contains("required_count"));
        assertTrue(result.feedback().contains("output_per_batch是{item,count}"));
        var language = ToolCatalog.MODEL_SPECS.stream().filter(s -> s.name().equals("run_program")).findFirst().orElseThrow();
        assertTrue(language.description().contains("不提供string/table/math库"));
        assertTrue(language.description().contains("set_goal/report_blocked/ask_owner只能外层"));
    }
    @Test void helpIsBoundedAndNeverEchoesUnknownInputOrModifiesTheRegistry() {
        var before = List.copyOf(ToolCatalog.MODEL_SPECS);
        for (String json : List.of("{\"names\":[\"private-unknown\"]}",
                "{\"names\":[]}", "{\"names\":[\"craft\",\"craft\",\"craft\",\"craft\"]}",
                "{\"names\":[17]}", "{\"unknown-private\":true}", "[]", "null")) {
            var result = ToolCatalog.read(json);
            assertFalse(result.ok());
            assertFalse(result.feedback().contains("private"));
        }
        var index = ToolCatalog.read("{}");
        assertTrue(index.ok());
        assertEquals(ClientToolDefs.SPECS.size(), index.data().getAsJsonArray("tools").size());
        assertEquals(before, ToolCatalog.MODEL_SPECS);
    }
}
