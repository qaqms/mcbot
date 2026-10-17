package com.neko.mcbot.building;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BuildTargetTest {
    @BeforeAll static void bootstrap() { com.neko.mcbot.server.BlockActionFixtures.bootstrap(); }

    static BuildTarget target(String cells) {
        return BuildTarget.parse(JsonParser.parseString("""
                {"anchor":{"x":10,"y":90,"z":20},"cells":%s}
                """.formatted(cells)).getAsJsonObject());
    }

    @Test void coordinateAndAuthoredOrientationRotateTogetherAboutTheStableAnchor() {
        var target = BuildTarget.parse(JsonParser.parseString("""
                {"anchor":{"x":10,"y":90,"z":20},"rotation":90,
                "cells":[{"x":2,"y":1,"z":0,"block":"oak_stairs",
                  "properties":[{"name":"facing","value":"north"},{"name":"half","value":"top"}]}]}
                """).getAsJsonObject());
        var cell = target.cells().getFirst();
        assertEquals(new BlockPos(10, 91, 22), cell.pos());
        var actual = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                .setValue(BlockStateProperties.HALF, net.minecraft.world.level.block.state.properties.Half.TOP);
        assertTrue(cell.matches(actual));
        assertFalse(cell.matches(actual.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)));
        assertFalse(cell.matches(actual.setValue(BlockStateProperties.HALF, net.minecraft.world.level.block.state.properties.Half.BOTTOM)));
        assertTrue(cell.matches(actual.setValue(BlockStateProperties.WATERLOGGED, true)),
                "unspecified properties impose no requirement");
    }

    @Test void explicitDynamicPropertiesAndBlockIdentityAreNeverSilentlyRelaxed() {
        var cell = target("""
                [{"x":0,"y":0,"z":0,"block":"oak_stairs","properties":[{"name":"waterlogged","value":"false"}]}]
                """).cells().getFirst();
        assertFalse(cell.matches(Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)));
        assertFalse(cell.matches(Blocks.SPRUCE_STAIRS.defaultBlockState()));
        assertTrue(cell.matches(Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)));
    }

    @Test void canonicalRevisionIgnoresInputOrderButChangesWithRequirementsAnchorAndRotation() {
        var a = target("""
                [{"x":1,"y":0,"z":0,"block":"stone"},{"x":0,"y":0,"z":0,"block":"dirt"}]
                """);
        var b = target("""
                [{"z":0,"block":"minecraft:dirt","y":0,"x":0},{"block":"minecraft:stone","x":1,"y":0,"z":0}]
                """);
        assertEquals(a.revision(), b.revision());
        assertNotEquals(a.revision(), target("""
                [{"x":1,"y":0,"z":0,"block":"cobblestone"},{"x":0,"y":0,"z":0,"block":"dirt"}]
                """).revision());
        var original = JsonParser.parseString("""
                {"anchor":{"x":10,"y":90,"z":20},"cells":[{"x":0,"y":0,"z":0,"block":"stone"}]}
                """).getAsJsonObject();
        String revision = BuildTarget.parse(original).revision();
        original.getAsJsonObject("anchor").addProperty("x", 11);
        assertNotEquals(revision, BuildTarget.parse(original).revision());
        original.getAsJsonObject("anchor").addProperty("x", 10);
        original.addProperty("rotation", 90);
        assertNotEquals(revision, BuildTarget.parse(original).revision());
    }

    @Test void fillIsTheSameTargetRepresentationForLinesPlanesAndVolumesAndIsBoundedBeforeExpansion() {
        for (String end : List.of("{\"x\":7,\"y\":0,\"z\":0}", "{\"x\":7,\"y\":0,\"z\":7}",
                "{\"x\":7,\"y\":1,\"z\":7}")) {
            var input = JsonParser.parseString("""
                    {"anchor":{"x":0,"y":90,"z":0},"fill":{"from":{"x":0,"y":0,"z":0},"to":%s,"block":"stone"}}
                    """.formatted(end)).getAsJsonObject();
            var value = BuildTarget.parse(input);
            assertTrue(value.cells().size() <= 128);
            assertEquals(value.cells().size(), value.cells().stream().map(BuildTarget.Cell::pos).distinct().count());
        }
        assertThrows(RuntimeException.class, () -> BuildTarget.parse(JsonParser.parseString("""
                {"anchor":{"x":0,"y":90,"z":0},"fill":{"from":{"x":-32,"y":-32,"z":-32},
                "to":{"x":32,"y":32,"z":32},"block":"stone"}}
                """).getAsJsonObject()));
    }

    @Test void duplicateCoordinatesInvalidPropertiesAndConflictingRepresentationsAreRejected() {
        for (String cells : List.of("[]",
                "[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"stone\"},{\"x\":0,\"y\":0,\"z\":0,\"block\":\"stone\"}]",
                "[{\"x\":33,\"y\":0,\"z\":0,\"block\":\"stone\"}]",
                "[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"not_a_real_block\"}]",
                "[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"stone\",\"properties\":[{\"name\":\"facing\",\"value\":\"north\"}]}]",
                "[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"oak_log\",\"properties\":[{\"name\":\"axis\",\"value\":\"wrong\"}]}]",
                "[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"oak_log\",\"properties\":[{\"name\":\"axis\",\"value\":\"x\"},{\"name\":\"axis\",\"value\":\"z\"}]}]",
                "[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"stone\",\"nbt\":{}}]"))
            assertThrows(RuntimeException.class, () -> target(cells), cells);
        for (String extra : List.of(",\"rotation\":45", ",\"execute\":true", ",\"fill\":{}"))
            assertThrows(RuntimeException.class, () -> BuildTarget.parse(JsonParser.parseString("""
                    {"anchor":{"x":0,"y":90,"z":0},"cells":[{"x":0,"y":0,"z":0,"block":"stone"}]%s}
                    """.formatted(extra)).getAsJsonObject()));
    }

    @Test void materialRulesDoNotChargeMulticellObjectsAsIndependentSinglePlacements() {
        var plain = target("[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"stone\"}]").cells().getFirst().material();
        assertTrue(plain.supported()); assertEquals(1, plain.units());
        var doubleSlab = target("""
                [{"x":0,"y":0,"z":0,"block":"oak_slab","properties":[{"name":"type","value":"double"}]}]
                """).cells().getFirst().material();
        assertEquals(2, doubleSlab.units());
        assertFalse(doubleSlab.supported(), "two placements are not yet supported by the one-action core");
        for (String id : List.of("oak_door", "red_bed", "water", "torch", "chest"))
            assertFalse(target("[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"" + id + "\"}]")
                    .cells().getFirst().material().supported(), id);
        assertEquals(0, target("[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"air\"}]").cells().getFirst().material().units());
    }
}
