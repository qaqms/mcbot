package com.neko.mcbot.path;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DynamicNavigationTest {
    private static Object field(PathTask task, String name) throws Exception {
        var field = PathTask.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(task);
    }

    private static void field(PathTask task, String name, Object value) throws Exception {
        var field = PathTask.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(task, value);
    }

    @Test void aMovingEntityCannotChangeTheCoordinateKeyUsedToRedeemTheOriginalApproval() throws Exception {
        var permissions = new com.neko.mcbot.server.ActionPermissions();
        var context = new com.neko.mcbot.server.ActionPermissions.Context(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), "minecraft:overworld", 1, false);
        permissions.begin(context);
        var requested = new BlockPos(2, 5, 0);
        var task = PathTask.approach(null, requested, permissions, context, null, PathSpec.DEFAULT,
                NavigationGoal.position(2, 5, 0, 0), new NavigationBudget());
        field(task, "path", List.of(new DigAStar.Step(0, 0, 0, List.of(), List.of(), DigAStar.ACT_START)));
        task.retarget(new BlockPos(2, 0, 0), NavigationGoal.position(2, 0, 0, 0));
        var confirmation = PathTask.class.getDeclaredMethod("needConfirmation", java.util.Map.class, int.class, int.class);
        confirmation.setAccessible(true);
        var done = (com.neko.mcbot.task.TickTask.Progress.Done) confirmation.invoke(task, java.util.Map.of(), 0, 0);
        var id = done.result().data().get("authorization_id").getAsString();
        assertTrue(permissions.approve(context, id));
        assertNotNull(permissions.take(context, id, requested.asLong()),
                "the replay contains the original request coordinates even after the item falls");
    }

    @Test void newMovingGoalKeepsTheExecutingPrefixAndInFlightSearchButMarksTheOldEndIncomplete() throws Exception {
        var account = new NavigationBudget();
        var task = PathTask.approach(null, new BlockPos(2, 0, 0), null, null, null,
                PathSpec.DEFAULT, NavigationGoal.position(2, 0, 0, 0), account);
        var path = new ArrayList<>(List.of(
                new DigAStar.Step(0, 0, 0, List.of(), List.of(), DigAStar.ACT_START),
                new DigAStar.Step(1, 0, 0, List.of(), List.of(), DigAStar.ACT_WALK),
                new DigAStar.Step(2, 0, 0, List.of(), List.of(), DigAStar.ACT_WALK)));
        field(task, "path", path);
        field(task, "cursor", 1);
        DigSampler floor = new DigSampler() {
            @Override public boolean inBounds(int x, int y, int z) { return x >= 0 && x <= 10 && y >= -1 && y <= 2 && z == 0; }
            @Override public boolean passable(int x, int y, int z) { return y >= 0; }
            @Override public boolean support(int x, int y, int z) { return y == -1; }
            @Override public double digSeconds(int x, int y, int z) { return INFEASIBLE; }
            @Override public boolean placeable(int x, int y, int z) { return false; }
            @Override public double placeCost(int x, int y, int z) { return INFEASIBLE; }
            @Override public int maxPlaces() { return 0; }
        };
        var search = new DigAStar(floor, 0, 0, 0, 2, 0, 0, 4000, 0);
        field(task, "search", search);
        var continuation = (NavigationContinuation) field(task, "continuation");
        continuation.advance(() -> new DigAStar(floor, 2, 0, 0, 8, 0, 0, 4000, 0),
                new NavigationAllowance(8), 1, Long.MAX_VALUE);
        var pending = continuation.search();
        assertTrue(continuation.pending());
        var latest = NavigationGoal.position(8, 0, 0, 0);
        task.retarget(new BlockPos(8, 0, 0), latest);
        assertSame(path, field(task, "path"));
        assertEquals(1, field(task, "cursor"));
        assertSame(search, field(task, "search"));
        assertSame(pending, continuation.search());
        assertSame(account, field(task, "sharedBudget"));
        assertSame(latest, field(task, "activeGoal"));
        assertEquals(true, field(task, "segmentTail"));
        assertNotEquals(field(task, "goalRevision"), field(task, "searchGoalRevision"));
        assertEquals(0, account.replans());
        task.retarget(new BlockPos(2, 0, 0), NavigationGoal.position(2, 0, 0, 0));
        assertEquals(false, field(task, "segmentTail"), "an endpoint still valid for the latest goal needs no extension");
        assertThrows(IllegalStateException.class, () ->
                new PathTask(null, BlockPos.ZERO, null, null, null, null).retarget(BlockPos.ZERO, latest));
    }
}
