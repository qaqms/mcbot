package com.neko.mcbot.building;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuildDesignReviewTest {
    @BeforeAll static void bootstrap() { com.neko.mcbot.server.BlockActionFixtures.bootstrap(); }

    @Test void pillarAxesAreExplicitDrawingRequirementsAndMissingAxesAreOnlyAdvice() {
        for (int rotation : new int[]{0,90,180,270}) {
            var target=BuildTarget.parse(JsonParser.parseString("""
                    {"anchor":{"x":0,"y":64,"z":0},"rotation":%d,"cells":[
                    {"x":0,"y":1,"z":0,"block":"oak_log"},
                    {"x":1,"y":1,"z":0,"block":"oak_log","properties":[{"name":"axis","value":"y"}]},
                    {"x":2,"y":1,"z":0,"block":"oak_log","properties":[{"name":"axis","value":"x"}]}]}
                    """.formatted(rotation)).getAsJsonObject());
            var input=target.input().deepCopy();
            var review=BuildDesignReview.describe(target);
            assertEquals(1,review.get("logs_missing_axis").getAsInt());
            assertTrue(review.get("advice").getAsString().contains("axis"));
            for (var cell:target.cells()) {
                var state=cell.desired();
                var other=state.setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS,
                        state.getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS)
                                ==net.minecraft.core.Direction.Axis.X ? net.minecraft.core.Direction.Axis.Z
                                : net.minecraft.core.Direction.Axis.X);
                assertEquals(!cell.properties().contains("axis"),cell.matches(other));
            }
            assertEquals(input,target.input(),"advice cannot rewrite a registered drawing");
        }
    }

    static BuildTarget slope(String facing,int rotation) {
        return BuildTarget.parse(JsonParser.parseString("""
                {"anchor":{"x":212,"y":69,"z":58},"rotation":%d,"regions":[
                {"from":{"x":0,"y":4,"z":0},"to":{"x":2,"y":4,"z":0},"block":"oak_stairs",
                "properties":[{"name":"facing","value":"%s"},{"name":"half","value":"bottom"}]},
                {"from":{"x":0,"y":5,"z":1},"to":{"x":2,"y":5,"z":1},"block":"oak_stairs",
                "properties":[{"name":"facing","value":"%s"},{"name":"half","value":"bottom"}]}]}
                """.formatted(rotation,facing,facing)).getAsJsonObject());
    }

    @Test void reversedRoofCoursesAreDiagnosedInEveryRotationWithoutChangingTheDrawing() {
        for (int rotation : new int[]{0,90,180,270}) {
            var backwards=slope("north",rotation);
            var saved=backwards.input();
            assertEquals(3,BuildDesignReview.describe(backwards).get("stairs_rising_behind_back").getAsInt());
            assertEquals(0,BuildDesignReview.describe(slope("south",rotation))
                    .get("stairs_rising_behind_back").getAsInt());
            assertEquals(saved,backwards.input());
        }
    }

    @Test void unqualifiedStairsAreAdviceAndGlassPanesUseTheActualNativePlacementMaterial() {
        var target=BuildTarget.parse(JsonParser.parseString("""
                {"anchor":{"x":0,"y":0,"z":0},"cells":[
                {"x":0,"y":0,"z":0,"block":"oak_stairs"},{"x":1,"y":0,"z":0,"block":"glass_pane"}]}
                """).getAsJsonObject());
        assertEquals(1,BuildDesignReview.describe(target).get("stairs_missing_orientation").getAsInt());
        assertTrue(target.cells().stream().allMatch(c->c.material().supported()));
    }

    @Test void authoredRoofFacingAndHalfStayExactWhileUnspecifiedNativeCornerShapeCanSettle() {
        for (int rotation:new int[]{0,90,180,270}) {
            var cell=slope("south",rotation).cells().getFirst();
            var desired=cell.desired();
            assertTrue(cell.matches(desired));
            assertFalse(cell.matches(desired.setValue(net.minecraft.world.level.block.StairBlock.FACING,
                    desired.getValue(net.minecraft.world.level.block.StairBlock.FACING).getOpposite())));
            assertFalse(cell.matches(desired.setValue(net.minecraft.world.level.block.StairBlock.HALF,
                    net.minecraft.world.level.block.state.properties.Half.TOP)));
            assertTrue(cell.matches(desired.setValue(net.minecraft.world.level.block.StairBlock.SHAPE,
                    net.minecraft.world.level.block.state.properties.StairsShape.OUTER_LEFT)));
        }
    }
}
