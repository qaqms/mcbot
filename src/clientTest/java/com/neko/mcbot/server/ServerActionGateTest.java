package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.task.CompanionScheduler;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ServerActionGateTest {
    private final CompanionScheduler scheduler = new CompanionScheduler();
    private final UUID owner = UUID.randomUUID(), body = UUID.randomUUID();
    private final JsonObject pos = JsonParser.parseString("{\"x\":2,\"y\":90,\"z\":0}").getAsJsonObject();
    private final ActionPermissions.Context readonly = new ActionPermissions.Context(owner, body, "world", 1, true);
    private final ActionPermissions.Context normal = new ActionPermissions.Context(owner, body, "world", 2, false);
    private final AtomicInteger actions = new AtomicInteger();

    private CompletableFuture<ServerTool.Result> action() {
        actions.incrementAndGet();
        return CompletableFuture.completedFuture(new ServerTool.Result(true, "observed", null));
    }

    @Test void readonlyGateDeniesAllMutatorsRegardlessOfModelFields() {
        pos.addProperty("may_alter_terrain", true);
        pos.addProperty("read_only", false);
        pos.addProperty("authorization_id", "fabricated");
        for (String name : new String[]{"move_to", "break_block", "place_block", "collect", "transfer",
                "equip", "craft", "smelt", "wait", "attack"}) {
            if (name.equals("smelt")) pos.addProperty("action", "load");
            var receipt = ServerActionGate.execute(readonly, body, "world", name, pos, scheduler, this::action).join();
            assertTrue(receipt.feedback().startsWith("DENIED:"), name);
        }
        assertEquals(0, actions.get());
    }

    @Test void readQueriesRunDuringResourceUseButMutationsCannot() {
        var longTask = new CompletableFuture<ServerTool.Result>();
        ServerActionGate.execute(normal, body, "world", "break_block", pos, scheduler, () -> longTask);
        for (String name : new String[]{"status", "inventory", "scan_area"}) {
            assertTrue(ServerActionGate.execute(readonly, body, "world", name, pos, scheduler, this::action).join().ok());
        }
        assertTrue(ServerActionGate.execute(normal, body, "world", "equip", pos, scheduler, this::action)
                .join().feedback().startsWith("BUSY:"));
        longTask.complete(new ServerTool.Result(true, "done", null));
        assertTrue(ServerActionGate.execute(normal, body, "world", "equip", pos, scheduler, this::action).join().ok());
    }

    @Test void otherCompanionCannotModifyLockedContainerOrPathNeighborhood() {
        var future = new CompletableFuture<ServerTool.Result>();
        ServerActionGate.execute(normal, body, "world", "break_block", pos, scheduler, () -> future);
        UUID other = UUID.randomUUID();
        var otherTask = new ActionPermissions.Context(UUID.randomUUID(), other, "world", 3, false);
        assertTrue(ServerActionGate.execute(otherTask, other, "world", "transfer", pos, scheduler, this::action)
                .join().feedback().startsWith("BUSY:"));
        future.complete(new ServerTool.Result(false, "failed but released", null));
        assertTrue(ServerActionGate.execute(otherTask, other, "world", "transfer", pos, scheduler, this::action).join().ok());
    }

    @Test void missingTaskOrMismatchedBodyDimensionCannotMutate() {
        for (ActionPermissions.Context context : new ActionPermissions.Context[]{null, normal}) {
            assertFalse(ServerActionGate.execute(context, UUID.randomUUID(), "world", "break_block",
                    pos, scheduler, this::action).join().ok());
        }
        assertFalse(ServerActionGate.execute(normal, body, "nether", "break_block", pos, scheduler, this::action).join().ok());
        assertEquals(0, actions.get());
    }
}
