package com.neko.mcbot.building;

import com.google.gson.JsonParser;
import com.neko.mcbot.server.BlockPlacement;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.task.TickTask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FurnitureBuildTest {
    @BeforeAll static void boot() { com.neko.mcbot.server.BlockActionFixtures.bootstrap(); }

    static BuildTarget bed(int rotation) {
        return BuildTarget.parse(JsonParser.parseString("""
                {"anchor":{"x":0,"y":90,"z":0},"rotation":%d,"cells":[
                {"x":0,"y":0,"z":0,"block":"red_bed","properties":[
                  {"name":"part","value":"foot"},{"name":"facing","value":"north"}]},
                {"x":0,"y":0,"z":-1,"block":"red_bed","properties":[
                  {"name":"part","value":"head"},{"name":"facing","value":"north"}]}]}
                """.formatted(rotation)).getAsJsonObject());
    }
    static class World extends BuildSurveyTest.World implements BuildWork.Access {
        int actions, recorded, consumption=1;
        boolean partial, throwsAfter, valid=true, secondaryBlocked;
        Map<BlockPos,BlockState> recordedBefore, recordedAfter;
        Runnable beforeReserve=()->{};
        @Override public BuildSurvey.Access survey() { return this; }
        @Override public ServerTool.Result valid() {
            return valid ? null : new ServerTool.Result(false,"DENIED:changed task",null);
        }
        @Override public boolean reserve(BuildTarget.Cell cell) { beforeReserve.run(); return true; }
        @Override public ServerTool.Result secondaryCell(BlockPos pos,BlockState state) {
            return secondaryBlocked ? new ServerTool.Result(false,"TARGET_BLOCKED:occupied secondary cell",null) : null;
        }
        @Override public InteractionResult place(BlockPos pos,ItemStack stack,Direction face) {
            actions++; stack.shrink(consumption); blocks.put(pos,proposed);
            if (BuildObjects.paired(proposed) && !partial)
                blocks.put(BuildObjects.other(pos,proposed),BuildObjects.partnerState(proposed));
            if (throwsAfter) throw new IllegalStateException("native callback after partial effect");
            return InteractionResult.SUCCESS;
        }
        @Override public void recordedObject(BlockPos primary,Map<BlockPos,BlockState> before,
                                              Map<BlockPos,BlockState> after,int consumed) {
            recorded++; recordedBefore=before; recordedAfter=after;
        }
    }
    static ServerTool.Result finish(BuildWork work) {
        for (int tick=0;tick<30;tick++)
            if (work.tickSafe(null) instanceof TickTask.Progress.Done done) return done.result();
        throw new AssertionError("work did not settle");
    }
    static BuildTarget.Cell foot(BuildTarget target) {
        return target.cells().stream().filter(c->BuildObjects.bed(c.authored())
                && !BuildObjects.secondary(c.authored())).findFirst().orElseThrow();
    }
    static World world(BuildTarget target) {
        var world=new World();
        world.inventory.setItem(0,new ItemStack(Items.RED_BED,2));
        world.overrideProposed=foot(target).desired();
        return world;
    }

    @Test void headBeforeFootAndAllRotationsStillIssueOneActionAndVerifyBothCells() {
        assertTrue(BuildObjects.secondary(bed(0).cells().getFirst().authored()),
                "north-facing bed deliberately has the head before the foot in canonical order");
        for (int rotation:java.util.List.of(0,90,180,270)) {
            var target=bed(rotation); var world=world(target);
            assertEquals("",BuildObjects.problem(target,foot(target)));
            assertEquals(target.revision(),BuildTarget.parse(target.input()).revision());
            var initial=world.query(target);
            assertEquals(1,initial.getAsJsonObject("counts").get("paired_pending").getAsInt());
            var bill=initial.getAsJsonArray("materials").get(0).getAsJsonObject();
            assertEquals(1,bill.get("design_required").getAsInt());
            assertEquals(1,bill.get("allocated").getAsInt());
            var done=finish(new BuildWork(target,world,-1,12,()->0));
            assertTrue(done.ok(),done.feedback());
            assertTrue(done.data().get("goal_satisfied").getAsBoolean());
            assertEquals(1,world.actions);
            assertEquals(1,world.inventory.getItem(0).getCount());
            assertEquals(2,world.recordedBefore.size());
            assertEquals(2,world.recordedAfter.size());
            assertTrue(finish(new BuildWork(target,world,-1,12,()->0)).ok());
            assertEquals(1,world.actions,"a satisfied head and foot cannot consume a second bed");
        }
    }

    @Test void missingOrWrongPartnerIsNeverDroppedFromTheTargetOrPlacedSeparately() {
        for (String bad:java.util.List.of("missing","facing","part")) {
            var input=bed(0).input(); var cells=input.getAsJsonArray("cells");
            if (bad.equals("missing")) cells.remove(0);
            else for (var property:cells.get(0).getAsJsonObject().getAsJsonArray("properties")) {
                var pair=property.getAsJsonObject();
                if (pair.get("name").getAsString().equals(bad))
                    pair.addProperty("value",bad.equals("part")?"foot":"east");
            }
            var target=BuildTarget.parse(input); var world=world(target);
            var done=finish(new BuildWork(target,world,-1,12,()->0));
            assertFalse(done.ok(),bad);
            assertFalse(done.data().get("goal_satisfied").getAsBoolean(),bad);
            assertEquals(0,world.actions,bad);
            assertEquals(2,world.inventory.getItem(0).getCount(),bad);
        }
    }

    @Test void aHeadOnOnePageAndFootOnTheNextStillNeedOneActionAndBothPageChecks() {
        var input=bed(0).input();
        for (int z=0;z<15;z++) input.getAsJsonArray("cells").add(JsonParser.parseString(
                "{\"x\":-1,\"y\":0,\"z\":"+z+",\"block\":\"stone\"}"));
        var target=BuildTarget.parse(input); var world=world(target);
        target.cells().stream().filter(c->!BuildObjects.bed(c.authored()))
                .forEach(c->world.blocks.put(c.pos(),c.desired()));
        assertTrue(BuildObjects.secondary(target.cells().get(15).authored()));
        assertFalse(BuildObjects.secondary(target.cells().get(16).authored()));
        assertFalse(finish(new BuildWork(target,world,0,12,()->0)).ok());
        assertEquals(0,world.actions,"the head never becomes an independent placement when its foot is on another page");
        var done=finish(new BuildWork(target,world,-1,12,()->0));
        assertTrue(done.ok(),done.feedback());
        assertEquals(1,world.actions);
        for (int offset:new int[]{0,16})
            assertTrue(BuildSurvey.query(target,world,offset,0,()->0).data().get("goal_satisfied").getAsBoolean());
    }

    @Test void eitherCellsUnknownScopeProtectionAndOccupancyAreRecheckedBeforeTheNativeCallback() {
        for (String bad:java.util.List.of("unknown","scope","protected","occupied","permission")) {
            var target=bed(0); var world=world(target);
            var head=BuildObjects.other(foot(target).pos(),foot(target).desired());
            world.beforeReserve=()-> {
                switch (bad) {
                    case "unknown" -> world.unknown.add(head);
                    case "scope" -> world.outside.add(head);
                    case "protected" -> world.protectedCells.add(head);
                    case "occupied" -> world.secondaryBlocked=true;
                    default -> world.valid=false;
                }
            };
            assertFalse(finish(new BuildWork(target,world,-1,12,()->0)).ok(),bad);
            assertEquals(0,world.actions,bad);
        }
    }

    @Test void partialExceptionAndFreePlacementKeepRealEffectsWithoutReplayingEitherHalf() {
        for (String kind:java.util.List.of("partial","exception","free")) {
            var target=bed(0); var world=world(target);
            world.partial=kind.equals("partial");
            world.throwsAfter=kind.equals("exception");
            if (kind.equals("free")) world.consumption=0;
            var result=finish(new BuildWork(target,world,-1,12,()->0));
            assertFalse(result.ok(),kind);
            assertEquals(1,world.actions,kind);
            assertEquals(kind.equals("partial")?1:2,world.blocks.size(),kind);
            assertEquals(kind.equals("free")?0:1,result.data().get("consumed_count").getAsInt(),kind);
            assertEquals(2,world.recordedAfter.size(),kind);
            var interrupted=new BuildWork(target,world,-1,12,()->0); interrupted.onAbort();
            assertFalse(finish(interrupted).ok());
            assertEquals(1,world.actions,kind);
        }
    }

    @Test void nativeEmptyFurnitureAndDecorationsConsumeRealItemsAndNeedFreshWholeTargetVerification() {
        for (String id:java.util.List.of("chest","barrel","crafting_table","lantern","torch",
                "oak_trapdoor","oak_fence","cobblestone_wall","red_carpet")) {
            var target=BuildTarget.parse(JsonParser.parseString("""
                    {"anchor":{"x":0,"y":90,"z":0},"cells":[{"x":0,"y":0,"z":0,"block":"%s"}]}
                    """.formatted(id)).getAsJsonObject());
            var material=target.material(target.cells().getFirst());
            assertTrue(material.supported(),id);
            var world=new World(); world.inventory.setItem(0,new ItemStack(material.item(),1));
            var result=finish(new BuildWork(target,world,-1,12,()->0));
            assertTrue(result.ok(),id+": "+result.feedback());
            assertEquals(1,world.actions,id);
            assertTrue(world.inventory.getItem(0).isEmpty(),id);
            assertTrue(result.data().get("goal_satisfied").getAsBoolean(),id);
            world.blocks.clear();
            assertFalse(BuildSurvey.verify(target,world,0,()->0).data().get("goal_satisfied").getAsBoolean(),id);
        }
    }

    @Test void mergingNeighbourChestAndContainerPayloadAreRejectedBeforeEitherCanChangeTheWorld() {
        var world=new World(); world.inventory.setItem(0,new ItemStack(Items.CHEST,2));
        world.overrideProposed=Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE,ChestType.LEFT);
        assertNotNull(BlockPlacement.assess(world,new BlockPos(0,90,0),0,Direction.UP));
        world.overrideProposed=Blocks.CHEST.defaultBlockState();
        world.inventory.getItem(0).set(DataComponents.CUSTOM_NAME,Component.literal("retain payload"));
        assertNotNull(BlockPlacement.assess(world,new BlockPos(0,90,0),0,Direction.UP));
        assertEquals(0,world.actions);
        assertTrue(world.blocks.isEmpty());
        assertEquals(2,world.inventory.getItem(0).getCount());
    }
}
