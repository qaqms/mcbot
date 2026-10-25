package com.neko.mcbot.server;

import com.google.gson.JsonParser;
import com.neko.mcbot.building.BuildSurvey;
import com.neko.mcbot.building.BuildTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConstructionPrerequisiteTest {
    @BeforeAll static void bootstrap() { BlockActionFixtures.bootstrap(); }

    @Test void theLoggedUnsupportedEaveWaitsForTheAdjacentRoofCourseInsteadOfSearchingForAStance() {
        var world=new BlockAimTest.Grid(); world.blocks.clear();
        BuildTarget target;
        try (var input=new java.io.InputStreamReader(java.util.Objects.requireNonNull(
                getClass().getResourceAsStream("/fixtures/logged-roof-410.json")),java.nio.charset.StandardCharsets.UTF_8)) {
            target=BuildTarget.parse(JsonParser.parseReader(input).getAsJsonObject());
        } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
        assertEquals(410,target.cells().size());
        assertEquals("b406a7b2df4a7053ff88e4beff5209b0bd49e51c80ae62bc501088e60826f27d",target.revision());
        for (var cell:target.cells()) if (cell.pos().getY()<69 && !cell.authored().isAir())
            world.blocks.put(cell.pos(),cell.desired());
        var eave=new BlockPos(190,69,435);
        var inner=new BlockPos(190,69,436);
        var inventory=BlockActionFixtures.inventory();
        inventory.setItem(0,new ItemStack(Items.OAK_STAIRS,64));
        var access=new BuildSurvey.Access() {
            @Override public net.minecraft.world.level.block.state.BlockState state(BlockPos p) { return world.getBlockState(p); }
            @Override public boolean inBounds(BlockPos p) { return true; }
            @Override public boolean protectedCell(BlockPos p) { return false; }
            @Override public boolean near(BlockPos p) { return false; }
            @Override public BlockAim.Anchor placementAnchor(BlockPos p) { return BlockAim.placementAnchor(world,p); }
            @Override public BlockPlacement.Access placement() {
                return new BlockPlacement.Access() {
                    @Override public net.minecraft.world.Container inventory() { return inventory; }
                    @Override public ServerTool.Result guard(BlockPos p) { return null; }
                    @Override public net.minecraft.world.level.block.state.BlockState state(BlockPos p) { return world.getBlockState(p); }
                    @Override public ServerTool.Result sight(BlockPos p,net.minecraft.core.Direction d) { fail("far cells need no stance preflight"); return null; }
                    @Override public net.minecraft.world.InteractionResult place(BlockPos p,ItemStack s,net.minecraft.core.Direction d) { fail("read only"); return null; }
                };
            }
            @Override public com.google.gson.JsonObject terrain() {
                return JsonParser.parseString("{\"dimension\":\"fixture:world\",\"chunks\":{\"11,27\":\"a\"}}").getAsJsonObject();
            }
        };
        assertEquals("needs_support",BuildSurvey.cellState(target,target.index().get(eave),access,world.getBlockState(eave)));
        assertEquals("out_of_reach",BuildSurvey.cellState(target,target.index().get(inner),access,world.getBlockState(inner)));
        var scan=BuildSurvey.workScan(target,access,0,()->0).data();
        assertEquals(inner.getZ(),scan.getAsJsonArray("cells").get(0).getAsJsonObject().get("z").getAsInt());
        assertEquals("out_of_reach",scan.getAsJsonArray("cells").get(0).getAsJsonObject().get("state").getAsString());
        var before=scan.get("progress_observation_key");
        world.blocks.put(inner,target.index().get(inner).desired());
        assertEquals("out_of_reach",BuildSurvey.cellState(target,target.index().get(eave),access,world.getBlockState(eave)));
        var next=BuildSurvey.workScan(target,access,0,()->0).data();
        assertNotEquals(before,next.get("progress_observation_key"));
        assertEquals(eave.getZ(),next.getAsJsonArray("cells").get(0).getAsJsonObject().get("z").getAsInt());
        assertTrue(world.getBlockState(eave).isAir());
        assertEquals(64,inventory.getItem(0).getCount());
        int outlines=world.outlines;
        assertFalse(BuildSurvey.verify(target,access,0,()->0).data().get("goal_satisfied").getAsBoolean());
        assertEquals(outlines,world.outlines,"whole-target acceptance does not search placement prerequisites");
    }

    @Test void unknownAndOccludedAnchorsAreNotMistakenForEmptySpace() {
        var world=new BlockAimTest.Grid(); world.blocks.clear();
        var target=new BlockPos(190,69,435);
        assertEquals(BlockAim.Anchor.ABSENT,BlockAim.placementAnchor(world,target));
        world.unknown.add(target.north());
        assertEquals(BlockAim.Anchor.UNKNOWN,BlockAim.placementAnchor(world,target));
        world.blocks.put(target.east(),Blocks.OAK_STAIRS.defaultBlockState());
        world.blocks.put(target.east().above(),Blocks.STONE.defaultBlockState());
        assertEquals(BlockAim.Anchor.PRESENT,BlockAim.placementAnchor(world,target),
                "the prerequisite observes outlines, not visibility from the current eye");
        world.blocks.clear(); world.unknown.clear();
        world.blocks.put(target,Blocks.SNOW.defaultBlockState());
        assertEquals(BlockAim.Anchor.PRESENT,BlockAim.placementAnchor(world,target),"a replaceable target can itself be clicked");
    }
}
