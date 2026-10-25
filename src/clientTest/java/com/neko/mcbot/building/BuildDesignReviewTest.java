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

    @Test void residentialReviewFlagsACompactFlatShellWithoutRewritingItsDrawing() {
        var target=BuildTarget.parse(JsonParser.parseString("""
                {"anchor":{"x":0,"y":64,"z":0},"regions":[
                {"from":{"x":0,"y":0,"z":0},"to":{"x":6,"y":0,"z":6},"block":"oak_planks"},
                {"from":{"x":0,"y":1,"z":0},"to":{"x":6,"y":3,"z":0},"block":"cobblestone"},
                {"from":{"x":0,"y":1,"z":6},"to":{"x":6,"y":3,"z":6},"block":"cobblestone"},
                {"from":{"x":0,"y":1,"z":1},"to":{"x":0,"y":3,"z":5},"block":"cobblestone"},
                {"from":{"x":6,"y":1,"z":1},"to":{"x":6,"y":3,"z":5},"block":"cobblestone"},
                {"from":{"x":3,"y":1,"z":0},"to":{"x":3,"y":1,"z":0},"block":"oak_door",
                 "properties":[{"name":"half","value":"lower"},{"name":"facing","value":"south"}]},
                {"from":{"x":3,"y":2,"z":0},"to":{"x":3,"y":2,"z":0},"block":"oak_door",
                 "properties":[{"name":"half","value":"upper"},{"name":"facing","value":"south"}]},
                {"from":{"x":1,"y":1,"z":1},"to":{"x":1,"y":1,"z":1},"block":"crafting_table"},
                {"from":{"x":2,"y":1,"z":1},"to":{"x":2,"y":1,"z":1},"block":"chest"}]}
                """).getAsJsonObject());
        var review=BuildDesignReview.describe(target);
        var warnings=review.getAsJsonArray("design_warnings").toString();
        assertTrue(warnings.contains("compact_residential_footprint"));
        assertTrue(warnings.contains("roof_profile_missing"));
        assertEquals(7,review.get("footprint_width").getAsInt());
        assertEquals(7,review.get("footprint_depth").getAsInt());
        assertTrue(review.has("advice"));
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

    @Test void residentialVolumeAdviceFlagsThreeClearLayersButAllowsFourOrFiveWithoutChangingTheDrawing() {
        for (int clearHeight : new int[]{3, 4, 5}) {
            int roofY = clearHeight + 1;
            var target = BuildTarget.parse(JsonParser.parseString("""
                    {"anchor":{"x":0,"y":64,"z":0},"regions":[
                    {"from":{"x":0,"y":0,"z":0},"to":{"x":8,"y":0,"z":10},"block":"oak_planks"},
                    {"from":{"x":0,"y":1,"z":0},"to":{"x":8,"y":%1$d,"z":0},"block":"cobblestone"},
                    {"from":{"x":0,"y":1,"z":10},"to":{"x":8,"y":%1$d,"z":10},"block":"cobblestone"},
                    {"from":{"x":0,"y":1,"z":1},"to":{"x":0,"y":%1$d,"z":9},"block":"cobblestone"},
                    {"from":{"x":8,"y":1,"z":1},"to":{"x":8,"y":%1$d,"z":9},"block":"cobblestone"},
                    {"from":{"x":0,"y":%2$d,"z":0},"to":{"x":8,"y":%2$d,"z":10},"block":"oak_planks"},
                    {"from":{"x":1,"y":1,"z":1},"to":{"x":1,"y":1,"z":1},"block":"crafting_table"}]}
                    """.formatted(clearHeight, roofY)).getAsJsonObject());
            var input = target.input().deepCopy();
            var revision = target.revision();
            var review = BuildDesignReview.describe(target);
            assertEquals(clearHeight + 2, review.get("volume_height").getAsInt());
            assertEquals(clearHeight < 4,
                    review.getAsJsonArray("design_warnings").asList().stream()
                            .anyMatch(warning -> warning.getAsString().equals("low_residential_volume")),
                    "full-block floor and roof must leave at least four layers between them");
            assertTrue(review.get("advice").getAsString().contains("不证明室内净高"));
            assertEquals(input, target.input(), "review must not raise an existing roof");
            assertEquals(revision, target.revision());
        }
    }
}
