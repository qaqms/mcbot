package com.neko.mcbot.server;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ActionPermissionsTest {
    private final AtomicLong clock = new AtomicLong(100);
    private final ActionPermissions permissions = new ActionPermissions(clock::get);
    private final ActionPermissions.Context task = new ActionPermissions.Context(
            UUID.randomUUID(), UUID.randomUUID(), "overworld", 7, false);
    private final ActionPermissions.Change dig = new ActionPermissions.Change("dig", 123, "stone");

    private String propose() {
        permissions.begin(task);
        return permissions.propose(new ActionPermissions.Scope(task, 999, Map.of(ActionPermissions.key(dig), dig)));
    }

    @Test void approvalRequiresCurrentOwnerTaskCompanionDimensionAndToken() {
        String token = propose();
        assertNotNull(token);
        assertNull(permissions.take(task, token, 999));
        for (var other : new ActionPermissions.Context[]{
                new ActionPermissions.Context(UUID.randomUUID(), task.companion(), task.dimension(), task.taskId(), false),
                new ActionPermissions.Context(task.owner(), UUID.randomUUID(), task.dimension(), task.taskId(), false),
                new ActionPermissions.Context(task.owner(), task.companion(), "nether", task.taskId(), false),
                new ActionPermissions.Context(task.owner(), task.companion(), task.dimension(), 8, false)}) {
            assertFalse(permissions.approve(other, token));
        }
        assertFalse(permissions.approve(task, "invented"));
        assertTrue(permissions.approve(task, token));
        assertNull(permissions.take(task, token, 998));
        var scope = permissions.take(task, token, 999);
        assertNotNull(scope);
        assertNull(permissions.take(task, token, 999));
    }

    @Test void cachedAndReplannedNewTargetsOrStateOrOperationAreOutsideScope() {
        String token = propose();
        assertTrue(permissions.approve(task, token));
        var scope = permissions.take(task, token, 999);
        assertTrue(scope.contains(dig));
        assertFalse(scope.contains(new ActionPermissions.Change("dig", 124, "stone")));
        assertFalse(scope.contains(new ActionPermissions.Change("dig", 123, "chest")));
        assertFalse(scope.contains(new ActionPermissions.Change("place", 123, "stone")));
    }

    @Test void expirationClockRollbackAndReplacementInvalidateApprovals() {
        String token = propose();
        clock.set(100 + ActionPermissions.TTL_MS + 1);
        assertFalse(permissions.approve(task, token));
        token = propose();
        clock.decrementAndGet();
        assertFalse(permissions.approve(task, token));
        token = propose();
        permissions.begin(task);
        assertFalse(permissions.approve(task, token));
    }

    @Test void endingAnotherTaskDoesNotRevokeCurrentButCancelDoes() {
        String token = propose();
        permissions.end(task.owner(), 8);
        assertTrue(permissions.approve(task, token));
        permissions.end(task.owner(), 0);
        assertNull(permissions.take(task, token, 999));
        assertNull(permissions.current(task.owner(), task.companion(), task.dimension(), 7));
    }

    @Test void readOnlyCannotEscalateEvenWithModelBoolean() {
        var read = new ActionPermissions.Context(task.owner(), task.companion(), task.dimension(), 8, true);
        permissions.begin(read);
        var args = JsonParser.parseString("{\"may_alter_terrain\":true}").getAsJsonObject();
        for (String tool : new String[]{"move_to", "break_block", "place_block", "collect", "transfer", "equip", "craft", "attack"}) {
            assertFalse(ActionPermissions.allowed(read, tool, args), tool);
        }
        assertTrue(ActionPermissions.allowed(read, "scan_area", args));
        assertNull(permissions.propose(new ActionPermissions.Scope(read, 9, Map.of(ActionPermissions.key(dig), dig))));
        assertFalse(ActionPermissions.allowed(null, "break_block", args));
        assertFalse(ActionPermissions.allowed(task, "unknown", args));
    }

    @Test void scopeCopiesTheProposalAndHasBoundedSize() {
        permissions.begin(task);
        var changes = new java.util.HashMap<String, ActionPermissions.Change>();
        changes.put(ActionPermissions.key(dig), dig);
        var scope = new ActionPermissions.Scope(task, 999, changes);
        changes.clear();
        assertTrue(scope.contains(dig));
        for (int i = 0; i < 257; i++) {
            var change = new ActionPermissions.Change("dig", i, "stone");
            changes.put(ActionPermissions.key(change), change);
        }
        assertNull(permissions.propose(new ActionPermissions.Scope(task, 999, changes)));
    }

    @Test void executionCannotReauthorizeARefilledCellOrNewReplanModification() {
        String token = propose();
        permissions.approve(task, token);
        var execution = new ActionPermissions.Execution(permissions.take(task, token, 999));
        var original = Map.of(ActionPermissions.key(dig), dig);
        assertTrue(execution.covers(original));
        assertTrue(execution.allows(dig));
        execution.observed("dig", dig.position());
        assertFalse(execution.allows(dig), "相同方块被补回也不能借旧许可再挖");
        assertFalse(execution.covers(original));
        var newCell = new ActionPermissions.Change("place", 321, "air");
        assertFalse(execution.covers(Map.of(ActionPermissions.key(newCell), newCell)));
        assertTrue(execution.covers(Map.of()), "无新增世界修改的重规划可以继续");
        assertFalse(new ActionPermissions.Execution(null).allows(dig));
    }

    @Test void sameUuidResummonedBodyCannotInheritOldTask() {
        Object original = new Object();
        Object resummoned = new Object();
        permissions.begin(task, original);
        assertEquals(task, permissions.current(task.owner(), task.companion(), task.dimension(), 7, original));
        assertNull(permissions.current(task.owner(), task.companion(), task.dimension(), 7, resummoned));
        assertNull(permissions.current(task.owner(), task.companion(), "nether", 7, original));
        permissions.end(task.owner(), 7);
        assertNull(permissions.body(task.owner()));
    }
}
