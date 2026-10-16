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
    @org.junit.jupiter.api.BeforeAll static void registries() {
        com.neko.mcbot.server.BlockActionFixtures.bootstrap();
    }

    @Test void placementIntentReusesExactTargetAndSinglePlacementItemAndFaceParsing() {
        var args = json("{\"x\":2,\"y\":0,\"z\":0,\"exact\":true,\"arrival\":\"work\","
                + "\"work_target\":{\"x\":2,\"y\":0,\"z\":0},\"place_item\":\"cobblestone\",\"face\":\"east\"}");
        var placement = MoveToTool.placement(args, MoveToTool.workTarget(args));
        assertEquals(net.minecraft.world.item.Items.COBBLESTONE, placement.item());
        assertEquals(net.minecraft.core.Direction.EAST, placement.face());
        args.remove("face");
        assertEquals(net.minecraft.core.Direction.UP, MoveToTool.placement(args, MoveToTool.workTarget(args)).face());
        assertNull(MoveToTool.placement(new JsonObject(), null));
    }

    @Test void invalidPlacementIntentIsRejectedBeforeAccessingTheBodyOrWorld() {
        var valid = json("{\"x\":2,\"y\":0,\"z\":0,\"exact\":true,\"arrival\":\"work\","
                + "\"work_target\":{\"x\":2,\"y\":0,\"z\":0},\"place_item\":\"cobblestone\"}");
        var variants = new java.util.ArrayList<JsonObject>();
        for (String field : java.util.List.of("work_target", "arrival", "exact")) {
            var args = valid.deepCopy(); args.remove(field); variants.add(args);
        }
        for (String item : java.util.List.of("stick", "missing_item", "")) {
            var args = valid.deepCopy(); args.addProperty("place_item", item); variants.add(args);
        }
        var mismatch = valid.deepCopy(); mismatch.addProperty("x", 3); variants.add(mismatch);
        var clear = valid.deepCopy(); clear.addProperty("clear_obstructions", true); variants.add(clear);
        var badFace = valid.deepCopy(); badFace.addProperty("face", "side"); variants.add(badFace);
        var numeric = valid.deepCopy(); numeric.addProperty("place_item", 2); variants.add(numeric);
        var orphanFace = valid.deepCopy(); orphanFace.remove("place_item");
        orphanFace.addProperty("face", "up"); variants.add(orphanFace);
        for (var args : variants)
            assertTrue(new MoveToTool().runAsync(null, args, null).join().feedback().startsWith("DENIED:"), args.toString());
    }

    @Test void walkOnlyUsesStrictBooleansAndRejectsMalformedModeBeforeBodyAccess() {
        assertFalse(MoveToTool.walkOnly(new JsonObject()));
        assertTrue(MoveToTool.walkOnly(JsonParser.parseString("{\"walk_only\":true}").getAsJsonObject()));
        for (String value : java.util.List.of("\"true\"", "1", "null")) {
            var args = JsonParser.parseString("{\"x\":1,\"y\":64,\"z\":2,\"walk_only\":" + value + "}").getAsJsonObject();
            assertTrue(new MoveToTool().runAsync(null, args, null).join().feedback().startsWith("DENIED:"));
        }
    }
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
