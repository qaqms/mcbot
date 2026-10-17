package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReturnWaypointTest {
    private JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }

    @Test void returnReferenceRequiresAnExplicitExactWalkWithoutAWorkTarget() {
        var valid = json("{\"x\":0,\"y\":70,\"z\":0,\"return_waypoint\":42,\"exact\":true,\"walk_only\":true}");
        assertEquals(42L, MoveToTool.returnWaypoint(valid));
        for (String value : new String[]{"0", "-1", "1.5", "\"42\"", "true", "null"}) {
            var bad = valid.deepCopy();
            bad.add("return_waypoint", JsonParser.parseString(value));
            assertThrows(RuntimeException.class, () -> MoveToTool.returnWaypoint(bad), value);
        }
        var noWalk = valid.deepCopy(); noWalk.remove("walk_only");
        assertThrows(RuntimeException.class, () -> MoveToTool.returnWaypoint(noWalk));
        var noExact = valid.deepCopy(); noExact.addProperty("exact", false);
        assertThrows(RuntimeException.class, () -> MoveToTool.returnWaypoint(noExact));
        var work = valid.deepCopy(); work.add("work_target", json("{\"x\":0,\"y\":70,\"z\":0}"));
        assertThrows(RuntimeException.class, () -> MoveToTool.returnWaypoint(work));
        assertNull(MoveToTool.returnWaypoint(json("{}")));
    }

    @Test void returnRevalidatesTheRecordedFootAndCannotLoosenScopeOrUseAStaleId() {
        var trail = new com.neko.mcbot.path.NavigationTrail();
        trail.bind("task", new Object());
        var to = new net.minecraft.core.BlockPos(0, 70, 0);
        var scope = new com.neko.mcbot.path.PathSpec(false, false, true, 12, to, 32);
        trail.observe(0, 70, 0, 70.5, true, scope);
        long id = trail.report().getAsJsonArray("waypoints").get(0).getAsJsonObject().get("id").getAsLong();
        assertNull(MoveToTool.returnGuard(trail, id, to, scope, () -> 70.5));
        assertNotNull(MoveToTool.returnGuard(trail, id, to, scope, () -> Double.NaN));
        assertNotNull(MoveToTool.returnGuard(trail, id, to, scope, () -> 70));
        assertTrue(MoveToTool.returnGuard(trail, id, to, com.neko.mcbot.path.PathSpec.DEFAULT,
                () -> { fail("must refuse before querying world"); return 0; })
                .feedback().startsWith("SCOPE_CHANGED:"));
        trail.bind("new task", new Object());
        assertTrue(MoveToTool.returnGuard(trail, id, to, scope,
                () -> { fail("stale IDs must never query or move"); return 0; })
                .feedback().startsWith("NO_ROUTE:"));
    }
}
