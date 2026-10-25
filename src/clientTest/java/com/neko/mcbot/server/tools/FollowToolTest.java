package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.path.PathSpec;
import com.neko.mcbot.server.ActionPermissions;
import com.neko.mcbot.server.ServerActionGate;
import com.neko.mcbot.task.CompanionScheduler;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FollowToolTest {
    private static JsonObject args(String text) { return JsonParser.parseString(text).getAsJsonObject(); }

    @Test void strictParametersDefaultToOwnerAndUseOneServerDeadline() {
        var input = FollowTool.arguments(args("{}"));
        assertNotNull(input);
        assertNull(input.target());
        assertEquals(3, input.distance());
        assertEquals(30, input.seconds());
        assertFalse(input.protectOwner());
        assertTrue(FollowTool.arguments(args("{\"protect_owner\":true}")).protectOwner());
        var tool = new FollowTool();
        assertEquals(600, tool.capTicks(args("{}")));
        assertEquals(1200, tool.capTicks(args("{\"seconds\":60}")));
        assertEquals(com.neko.mcbot.server.ServerTool.Acceptance.ACCEPT, tool.acceptanceMode());
        var uuid = UUID.randomUUID();
        assertEquals(uuid, FollowTool.arguments(args("{\"target_uuid\":\"" + uuid + "\",\"distance\":2,\"seconds\":1}")).target());
        assertNotNull(FollowTool.arguments(args("{\"distance\":6}")));
    }

    @Test void rejectsForgedPermissionsExtraFieldsAndInvalidNumericOrUuidTypes() {
        for (String json : java.util.List.of("{\"seconds\":0}", "{\"seconds\":61}", "{\"seconds\":\"30\"}",
                "{\"seconds\":1.2}", "{\"distance\":1}", "{\"distance\":7}", "{\"target_uuid\":3}",
                "{\"target_uuid\":null}", "{\"target_uuid\":\"1-1-1-1-1\"}", "{\"distance\":true}",
                "{\"seconds\":2147483649}", "{\"bounds\":{}}", "{\"query\":true}",
                "{\"protect_owner\":\"true\"}", "{\"protect_owner\":1}", "{\"protect_owner\":null}",
                "{\"target_uuid\":\"00000000-0000-0000-0000-00000000000A\"}"))
            assertNull(FollowTool.arguments(args(json)), json);
    }

    @Test void proactiveGuardDoesNotConfuseNeutralOrPassiveCreaturesWithEnemies() {
        assertTrue(com.neko.mcbot.server.ThreatFacts.guardCandidate(true, false, false, false));
        assertFalse(com.neko.mcbot.server.ThreatFacts.guardCandidate(true, true, false, false));
        assertTrue(com.neko.mcbot.server.ThreatFacts.guardCandidate(true, true, true, false));
        assertTrue(com.neko.mcbot.server.ThreatFacts.guardCandidate(true, true, false, true));
        assertFalse(com.neko.mcbot.server.ThreatFacts.guardCandidate(false, false, false, false));
        assertFalse(com.neko.mcbot.server.ThreatFacts.guardCandidate(false, true, true, true));
    }

    @Test void frozenLocalSphereIsConservativelyContainedByTheOriginalWorkScope() {
        var scope = FollowTool.scope(new BlockPos(4, 0, 0),
                new PathSpec(false, false, true, 0, BlockPos.ZERO, 10));
        assertEquals(new BlockPos(4, 0, 0), scope.anchor());
        assertEquals(6, scope.radius());
        assertTrue(scope.underground());
        assertEquals(0, scope.maxDigs());
        assertFalse(scope.clear());
        assertNull(FollowTool.scope(new BlockPos(10, 0, 0),
                new PathSpec(false, false, false, 0, BlockPos.ZERO, 10)));
        assertEquals(32, FollowTool.scope(BlockPos.ZERO, PathSpec.DEFAULT).radius());
    }

    @Test void originalServerGateRequiresTaskIdentityAndRejectsReadOnlyEvenWithForgedQuery() {
        var companion = UUID.randomUUID();
        var owner = UUID.randomUUID();
        var scheduler = new CompanionScheduler();
        for (var context : new ActionPermissions.Context[]{
                null, new ActionPermissions.Context(owner, companion, "world", 1, true),
                new ActionPermissions.Context(owner, UUID.randomUUID(), "world", 1, false),
                new ActionPermissions.Context(owner, companion, "other", 1, false)}) {
            var result = ServerActionGate.execute(context, companion, "world", "follow", args("{\"query\":true}"),
                    scheduler, () -> { fail("must not dispatch"); return null; }).join();
            assertTrue(result.feedback().startsWith("DENIED:"));
        }
        assertTrue(ActionPermissions.allowed(new ActionPermissions.Context(owner, companion, "world", 2, false),
                "follow", args("{}")));
    }
}
