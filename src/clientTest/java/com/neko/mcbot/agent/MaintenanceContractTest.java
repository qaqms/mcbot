package com.neko.mcbot.agent;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MaintenanceContractTest {
    @Test void shortTasksAndProgramsExposeOneBoundedNativeConsumptionContract() {
        var spec = ClientToolDefs.SPECS.stream().filter(s -> s.name().equals("consume")).findFirst().orElseThrow();
        var schema = JsonParser.parseString(spec.paramsJsonSchema()).getAsJsonObject();
        BoundedWorkflow.validate(JsonParser.parseString("{\"slot\":13}").getAsJsonObject(), schema);
        assertThrows(IllegalArgumentException.class, () -> BoundedWorkflow.validate(
                JsonParser.parseString("{\"slot\":36}").getAsJsonObject(), schema));
        var plan = BoundedWorkflow.parse("{\"steps\":[{\"tool\":\"consume\",\"args\":{\"slot\":13}}]}");
        assertFalse(plan.readOnly());
        assertEquals(1, plan.itemRequests());
        assertTrue(ClientToolDefs.resultGuide("consume").contains("consumed_count"));
    }
}
