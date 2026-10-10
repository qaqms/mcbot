package com.neko.mcbot.server.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.server.BlockActionFixtures.Mining;
import com.neko.mcbot.server.BlockActionFixtures.Placement;
import com.neko.mcbot.server.BlockMining;
import com.neko.mcbot.task.TickTask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.neko.mcbot.server.BlockActionFixtures.bootstrap;
import static org.junit.jupiter.api.Assertions.*;

class BlockToolOperationsTest {
    @BeforeAll static void registriesOnly() { bootstrap(); }
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static JsonObject placeArgs() {
        return json("{\"x\":2,\"y\":90,\"z\":-4,\"item\":\"cobblestone\"}");
    }

    @Test void coordinatesRequireExactNumericIntegersIncludingMoveSharedParser() {
        assertEquals(new BlockPos(-2, 90, 4), BreakBlockTool.readPos(
                json("{\"x\":-2.0,\"y\":9e1,\"z\":4}")));
        for (String value : new String[]{"\"2\"", "true", "null", "1.5", "2147483648",
                "-2147483649", "1e100", "{}", "[]"}) {
            assertNull(BreakBlockTool.readPos(json("{\"x\":" + value + ",\"y\":90,\"z\":4}")), value);
        }
        assertNull(BreakBlockTool.readPos(new JsonObject()));
    }

    @Test void itemRegistryAndOptionalFaceAreStrictWithoutChangingDefault() {
        var input = PlaceBlockTool.arguments(placeArgs());
        assertNotNull(input);
        assertSame(Items.COBBLESTONE, input.item());
        assertEquals(Direction.UP, input.face());
        var wall = placeArgs();
        wall.addProperty("face", "west");
        assertEquals(Direction.WEST, PlaceBlockTool.arguments(wall).face());
        for (String value : new String[]{"\"UP\"", "null", "false", "3", "\"diagonal\""}) {
            var args = placeArgs();
            args.add("face", JsonParser.parseString(value));
            assertNull(PlaceBlockTool.arguments(args));
        }
        for (String value : new String[]{"\"stone_without_such_id\"", "\"stick\"", "\"minecraft:wall_torch\"",
                "\"BAD ID\"", "true", "null", "\"" + "a".repeat(129) + "\""}) {
            var args = placeArgs();
            args.add("item", JsonParser.parseString(value));
            assertNull(PlaceBlockTool.arguments(args), value);
        }
    }

    @Test void invalidAndBusyPlacementDoNotReadWorldOrSpendItems() {
        Placement access = new Placement();
        access.inventory.setItem(13, new ItemStack(Items.COBBLESTONE, 3));
        assertTrue(PlaceBlockTool.place(access, placeArgs(), true).feedback().startsWith("BUSY:"));
        assertTrue(PlaceBlockTool.place(access, new JsonObject(), false).feedback().startsWith("DENIED:"));
        assertEquals(0, access.reads);
        assertEquals(0, access.calls);
        assertEquals(3, access.inventory.getItem(13).getCount());
    }

    @Test void placementFindsRequestedStorageItemAndNeverUsesOffhand() {
        Placement access = new Placement();
        access.inventory.setItem(0, new ItemStack(Items.DIRT, 7));
        access.inventory.setItem(40, new ItemStack(Items.COBBLESTONE, 4));
        assertFalse(PlaceBlockTool.place(access, placeArgs(), false).ok());
        assertEquals(0, access.calls);
        access.inventory.setItem(35, new ItemStack(Items.COBBLESTONE, 3));
        var args = placeArgs();
        args.addProperty("face", "south");
        assertTrue(PlaceBlockTool.place(access, args, false).ok());
        assertEquals(Direction.SOUTH, access.face);
        assertEquals(2, access.inventory.getItem(35).getCount());
        assertEquals(4, access.inventory.getItem(40).getCount());
        assertEquals(7, access.inventory.getItem(0).getCount());
    }

    @Test void miningTaskWaitsForFinalReceiptWithoutRepeatingDestruction() {
        Mining access = new Mining();
        access.gain = 1;
        var task = new BreakBlockTool.Task(new BlockMining(access));
        assertInstanceOf(TickTask.Progress.Running.class, task.tick(null));
        assertEquals(1, access.destroys);
        assertInstanceOf(TickTask.Progress.Running.class, task.tick(null));
        assertInstanceOf(TickTask.Progress.Running.class, task.tick(null));
        var done = assertInstanceOf(TickTask.Progress.Done.class, task.tick(null));
        assertTrue(done.result().ok());
        assertTrue(done.result().data().get("removed").getAsBoolean());
        assertEquals(1, access.destroys);
        assertEquals(1200, new BreakBlockTool().capTicks(new JsonObject()));
    }

    @Test void failedOrAbortedMiningTaskTerminatesWithoutWorldEffects() {
        Mining wrong = new Mining();
        wrong.correct = false;
        var task = new BreakBlockTool.Task(new BlockMining(wrong));
        var failed = assertInstanceOf(TickTask.Progress.Done.class, task.tick(null));
        assertTrue(failed.result().feedback().startsWith("WRONG_TOOL:"));
        assertEquals(0, wrong.destroys);
        Mining waiting = new Mining();
        task = new BreakBlockTool.Task(new BlockMining(waiting));
        assertInstanceOf(TickTask.Progress.Running.class, task.tick(null));
        task.onAbort();
        var cancelled = assertInstanceOf(TickTask.Progress.Done.class, task.tick(null));
        assertTrue(cancelled.result().feedback().startsWith("CANCELLED:"));
        assertEquals(0, waiting.destroys);
        assertEquals(-1, waiting.cracks.getLast());
    }
}
