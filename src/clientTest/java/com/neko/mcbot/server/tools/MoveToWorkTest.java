package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.server.BlockAim;
import com.neko.mcbot.server.ServerTool;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class MoveToWorkTest {
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }

    @Test void workTargetRequiresExactMovementAndStrictCoordinatesBeforeWorldAccess() {
        assertNull(MoveToTool.workTarget(new JsonObject()));
        assertEquals(new BlockPos(3, 64, -2), MoveToTool.workTarget(json(
                "{\"exact\":true,\"work_target\":{\"x\":3,\"y\":64,\"z\":-2}}")));
        for (String text : new String[]{
                "{\"work_target\":{\"x\":3,\"y\":64,\"z\":-2}}",
                "{\"exact\":false,\"work_target\":{\"x\":3,\"y\":64,\"z\":-2}}",
                "{\"exact\":true,\"work_target\":null}",
                "{\"exact\":true,\"work_target\":{\"x\":3.2,\"y\":64,\"z\":-2}}",
                "{\"exact\":true,\"work_target\":{\"x\":3,\"y\":64,\"z\":-2,\"extra\":1}}"}) {
            assertThrows(RuntimeException.class, () -> MoveToTool.workTarget(json(text)));
            var args = json(text);
            args.addProperty("x", 0); args.addProperty("y", 64); args.addProperty("z", 0);
            assertTrue(new MoveToTool().runAsync(null, args, null).join().feedback().startsWith("DENIED:"));
        }
    }

    @Test void arrivalUsesFreshWorkAssessmentAndPreservesActualMovementData() {
        var data = json("{\"arrived\":true,\"nodes\":3}");
        var moved = new ServerTool.Result(true, "arrived", data);
        var nowBlocked = new AtomicBoolean(false);
        java.util.function.Supplier<BlockAim.Aim> assess = () -> nowBlocked.get()
                ? new BlockAim.Aim(null, "TARGET_BLOCKED:scene changed")
                : new BlockAim.Aim(new Vec3(3.5, 64.5, 0), null);
        assertTrue(MoveToTool.finishWork(moved, assess).ok());
        nowBlocked.set(true);
        var result = MoveToTool.finishWork(moved, assess);
        assertFalse(result.ok());
        assertTrue(result.feedback().startsWith("TARGET_BLOCKED:"));
        assertTrue(result.data().get("arrived").getAsBoolean());
        assertFalse(result.data().get("can_work_here").getAsBoolean());
        assertFalse(data.has("can_work_here"), "the navigation receipt must not be mutated");
    }

    @Test void routeFailuresAndOrdinaryMovementNeverInvokeTheWorkAssessment() {
        var denied = new ServerTool.Result(false, "NEED_CONFIRM:concrete route", new JsonObject());
        assertSame(denied, MoveToTool.finishWork(denied, () -> { fail("must preserve authorization failure"); return null; }));
        var moved = new ServerTool.Result(true, "arrived", new JsonObject());
        assertSame(moved, MoveToTool.finishWork(moved, null));
    }
}
