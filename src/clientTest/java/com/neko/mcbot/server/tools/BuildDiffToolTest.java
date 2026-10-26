package com.neko.mcbot.server.tools;

import com.google.gson.JsonParser;
import com.neko.mcbot.building.BuildTarget;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuildDiffToolTest {
    @BeforeAll static void bootstrap() { com.neko.mcbot.server.BlockActionFixtures.bootstrap(); }
    @Test void pairedDrawingErrorsRemainQueryableButCannotBecomeVerifiedGoalEvidence() {
        var placement=new com.neko.mcbot.server.BlockActionFixtures.Placement();
        var access=new com.neko.mcbot.building.BuildSurvey.Access() {
            @Override public net.minecraft.world.level.block.state.BlockState state(net.minecraft.core.BlockPos p) {
                return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            }
            @Override public boolean inBounds(net.minecraft.core.BlockPos p) { return true; }
            @Override public boolean protectedCell(net.minecraft.core.BlockPos p) { return false; }
            @Override public boolean near(net.minecraft.core.BlockPos p) { return false; }
            @Override public com.neko.mcbot.server.BlockPlacement.Access placement() { return placement; }
            @Override public com.google.gson.JsonObject terrain() {
                return JsonParser.parseString("{\"dimension\":\"fixture:world\",\"chunks\":{\"0,0\":\"fixed\"}}").getAsJsonObject();
            }
        };
        var args=JsonParser.parseString("""
                {"target":{"anchor":{"x":0,"y":90,"z":0},"cells":[
                {"x":0,"y":0,"z":0,"block":"red_bed","properties":[
                {"name":"part","value":"foot"},{"name":"facing","value":"south"}]}]}}
                """).getAsJsonObject();
        var query=BuildDiffTool.report(args,ignored->access,()->0);
        assertTrue(query.ok(),"a read-only diagnosis must stay available for correction");
        assertFalse(query.data().getAsJsonObject("object_validation").get("valid").getAsBoolean());
        args.addProperty("expected_revision",BuildTarget.parse(args.getAsJsonObject("target")).revision());
        args.addProperty("verify_only",true);
        var refused=BuildDiffTool.report(args,ignored->access,()->0);
        assertFalse(refused.ok());
        assertTrue(refused.feedback().startsWith("INVALID_BUILD_TARGET:"),refused.feedback());
        assertTrue(refused.feedback().contains("bed_pair_missing_or_inconsistent"));
        assertEquals(query.data().get("object_validation"),refused.data().get("object_validation"));
        assertEquals(0,placement.calls);
    }

    private static final String TARGET = """
            {"anchor":{"x":0,"y":90,"z":0},"fill":{"from":{"x":0,"y":0,"z":0},
            "to":{"x":7,"y":0,"z":7},"block":"stone"}}
            """;
    @Test void surfaceSeparatesGroundBlocksFeetAndFractionalCollisionAndNeverInventsUnknownSupport() {
        var blocks=new java.util.HashMap<net.minecraft.core.BlockPos,net.minecraft.world.level.block.state.BlockState>();
        var base=new net.minecraft.core.BlockPos(-1,69,2);
        blocks.put(base,net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState());
        blocks.put(base.above(),net.minecraft.world.level.block.Blocks.DANDELION.defaultBlockState());
        var unknown=new java.util.HashSet<net.minecraft.core.BlockPos>();
        var geometry=new com.neko.mcbot.server.StandingGeometry(new com.neko.mcbot.server.StandingGeometry.Access() {
            @Override public boolean known(net.minecraft.core.BlockPos p) { return !unknown.contains(p); }
            @Override public java.util.List<net.minecraft.world.phys.AABB> boxes(net.minecraft.core.BlockPos p) {
                assertFalse(unknown.contains(p),"unknown cells cannot be read");
                return com.neko.mcbot.server.StandingGeometry.collision(blocks.getOrDefault(p,
                        net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()),
                        net.minecraft.world.level.EmptyBlockGetter.INSTANCE,p);
            }
        },.6,1.8,.6);
        java.util.function.Function<net.minecraft.core.BlockPos,net.minecraft.world.level.block.state.BlockState> state=
                p->blocks.getOrDefault(p,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        var surface=BuildDiffTool.observedSurface(geometry,base,state);
        assertEquals(69,surface.getAsJsonObject("support").get("y").getAsInt());
        assertEquals(70,surface.getAsJsonObject("feet").get("y").getAsInt());
        assertEquals(70,surface.get("feet_y").getAsDouble());
        assertEquals("minecraft:grass_block",surface.get("support_block").getAsString());
        blocks.put(base,net.minecraft.world.level.block.Blocks.OAK_SLAB.defaultBlockState());
        blocks.remove(base.above());
        surface=BuildDiffTool.observedSurface(geometry,base,state);
        assertEquals(69,surface.getAsJsonObject("feet").get("y").getAsInt());
        assertEquals(69.5,surface.get("feet_y").getAsDouble());
        unknown.add(base);
        assertNull(BuildDiffTool.observedSurface(geometry,base,state));
        assertEquals(1,blocks.size());
    }

    @Test void invalidOrForgedModificationArgumentsAreRejectedBeforeAnyWorldAccess() {
        for (String extra : java.util.List.of(",\"execute\":true", ",\"offset\":1",
                ",\"offset\":16", ",\"material_offset\":16",
                ",\"expected_revision\":\"invalid\"")) {
            var input = JsonParser.parseString("{\"target\":" + TARGET + extra + "}").getAsJsonObject();
            var result = BuildDiffTool.report(input, target -> {
                fail("invalid input must be rejected before body/world access");
                return null;
            }, () -> 0);
            assertFalse(result.ok());
            assertTrue(result.feedback().startsWith("DENIED:"), result.feedback());
        }
    }

    @Test void changedTargetCannotReuseARevisionFromAnotherPageEvenAtTheSameAnchor() {
        var input = JsonParser.parseString("{\"target\":" + TARGET + ",\"offset\":16}").getAsJsonObject();
        var target = input.getAsJsonObject("target");
        String previous = BuildTarget.parse(target).revision();
        target.getAsJsonObject("fill").addProperty("block", "dirt");
        input.addProperty("expected_revision", previous);
        var result = BuildDiffTool.report(input, ignored -> {
            fail("mismatched revision must be rejected before body/world access");
            return null;
        }, () -> 0);
        assertFalse(result.ok());
        assertTrue(result.feedback().startsWith("TARGET_REVISION_CHANGED:"));
    }
    @Test void missingPageRevisionNamesTheMissingFieldAndAcceptsLuaIntegralNumbersWithIt() {
        var args=JsonParser.parseString("{\"target\":"+TARGET+",\"offset\":16.0}").getAsJsonObject();
        var denied=BuildDiffTool.report(args, ignored->{ fail("must reject before world lookup"); return null; },()->0);
        assertFalse(denied.ok());
        assertTrue(denied.feedback().contains("expected_revision"),denied.feedback());
        args.addProperty("expected_revision",BuildTarget.parse(args.getAsJsonObject("target")).revision());
        var reached=new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(IllegalStateException.class,()->BuildDiffTool.report(args, ignored->{
            reached.set(true); throw new IllegalStateException("valid input reached world boundary");
        },()->0));
        assertTrue(reached.get(),"16.0 from Lua is a valid integral offset");
    }
    @Test void lastLargePageAndUnalignedWorkCursorValidateBeforeWorldLookup() {
        var input=JsonParser.parseString("""
                {"anchor":{"x":0,"y":90,"z":0},"fill":{"from":{"x":-32,"y":0,"z":0},
                "to":{"x":31,"y":31,"z":7},"block":"stone"}}
                """).getAsJsonObject();
        var args=new com.google.gson.JsonObject(); args.add("target",input);
        args.addProperty("expected_revision",BuildTarget.parse(input).revision());
        args.addProperty("offset",16368);
        assertThrows(IllegalStateException.class,()->BuildDiffTool.report(args,ignored->{
            throw new IllegalStateException("validated final page");
        },()->0));
        args.addProperty("offset",0); args.addProperty("work_scan",true); args.addProperty("work_cursor",16383);
        assertThrows(IllegalStateException.class,()->BuildDiffTool.report(args,ignored->{
            throw new IllegalStateException("validated canonical cursor");
        },()->0));
        args.remove("expected_revision");
        assertFalse(BuildDiffTool.report(args,ignored->{fail("cursor continuation requires revision"); return null;},()->0).ok());
    }
}
