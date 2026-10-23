package com.neko.mcbot.agent;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowArgumentTest {
    private String error(String tool, String args) {
        var spec = ClientToolDefs.SPECS.stream().filter(s -> s.name().equals(tool)).findFirst().orElseThrow();
        return assertThrows(BoundedWorkflow.ArgumentFailure.class, () ->
                BoundedWorkflow.validate(JsonParser.parseString(args),
                        JsonParser.parseString(spec.paramsJsonSchema()).getAsJsonObject())).getMessage();
    }

    @Test void undeclaredFieldDiagnosticsOnlyListTrustedSchemaKeys() {
        String text = error("move_to", "{\"x\":2,\"y\":90,\"z\":0,\"private-field\":\"private-value\"}");
        assertTrue(text.contains("可用字段="));
        assertTrue(text.contains("work_target"));
        assertFalse(text.contains("private-field"));
        assertFalse(text.contains("private-value"));
    }

    @Test void wrongNestedTypesAndNullReportTheActualField() {
        assertTrue(error("move_to", "{\"x\":2,\"y\":90,\"z\":0,\"work_target\":true}")
                .startsWith("args.work_target："));
        assertTrue(error("move_to", "{\"x\":2,\"y\":90,\"z\":null}").startsWith("args.z："));
        assertTrue(error("move_to", "[]").contains("object"));
    }

    @Test void integersBooleansAndArrayElementsRetainTheirStrictTypesAndBounds() {
        assertTrue(error("move_to", "{\"x\":2,\"y\":90,\"z\":0,\"max_digs\":-1}").contains("[0,2147483647]"));
        assertTrue(error("move_to", "{\"x\":2,\"y\":90,\"z\":0,\"exact\":\"true\"}").contains("args.exact"));
        assertTrue(error("find_resource", "{\"targets\":[\"stone\",17]}").startsWith("args.targets[1]："));
        assertTrue(error("equip", "{\"slot\":2.5}").contains("integer"));
    }

    @Test void selectorUnionRejectsOtherTypesAndStillAcceptsBothDeclaredVariants() {
        assertTrue(error("attack", "{\"entity_id\":true}").contains("[integer, string]"));
        var spec = ClientToolDefs.SPECS.stream().filter(s -> s.name().equals("attack")).findFirst().orElseThrow();
        var schema = JsonParser.parseString(spec.paramsJsonSchema()).getAsJsonObject();
        assertDoesNotThrow(() -> BoundedWorkflow.validate(JsonParser.parseString("{\"entity_id\":37}"), schema));
        assertDoesNotThrow(() -> BoundedWorkflow.validate(
                JsonParser.parseString("{\"entity_id\":\"hostile_nearby\"}"), schema));
    }

    @Test void workflowSupportsShortTicksWhileRejectingAmbiguousOrNonIntegerWaits() {
        for (String args : new String[]{"{\"ticks\":5}", "{\"seconds\":60}"})
            assertDoesNotThrow(() -> BoundedWorkflow.parse(
                    "{\"steps\":[{\"tool\":\"wait\",\"args\":" + args + "}]}"));
        for (String args : new String[]{"{}", "{\"ticks\":3,\"seconds\":1}", "{\"ticks\":1.2}",
                "{\"ticks\":\"3\"}", "{\"ticks\":1201}", "{\"seconds\":0}"})
            assertThrows(IllegalArgumentException.class, () -> BoundedWorkflow.parse(
                    "{\"steps\":[{\"tool\":\"wait\",\"args\":" + args + "}]}"));
    }

    @Test void directAndProgramWaitValidationKeepsExactlyOneBoundedDuration() {
        var spec = ClientToolDefs.SPECS.stream().filter(s -> s.name().equals("wait")).findFirst().orElseThrow();
        var schema = JsonParser.parseString(spec.paramsJsonSchema()).getAsJsonObject();
        for (String args : new String[]{"{\"ticks\":1}", "{\"ticks\":1200}", "{\"seconds\":1}", "{\"seconds\":60}"})
            assertDoesNotThrow(() -> BoundedWorkflow.validate(JsonParser.parseString(args), schema));
        for (String args : new String[]{"{}", "{\"ticks\":3,\"seconds\":1}", "{\"ticks\":0}",
                "{\"ticks\":1201}", "{\"seconds\":61}", "{\"ticks\":null}", "{\"seconds\":\"1\"}",
                "{\"ticks\":2.5}", "{\"ticks\":1,\"private-field\":true}"})
            assertFalse(error("wait", args).isBlank(), args);
    }
}
