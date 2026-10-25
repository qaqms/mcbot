package com.neko.mcbot.building;

import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;
import com.neko.mcbot.agentcore.script.BoundedProgram;
import com.neko.mcbot.server.BlockPlacement;
import com.neko.mcbot.server.ServerTool;
import com.neko.mcbot.task.TickTask;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class DoorHouseRegressionTest {
    @BeforeAll static void boot() { com.neko.mcbot.server.BlockActionFixtures.bootstrap(); }

    static BuildTarget house() throws Exception {
        try (var resource = DoorHouseRegressionTest.class.getResourceAsStream("/construction/door-house-172.json")) {
            assertNotNull(resource);
            return BuildTarget.parse(JsonParser.parseString(new String(resource.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject());
        }
    }

    @Test void originalHouseKeepsItsRevisionAndBuildsTheLastTwoCellsWithOneNativeAction() throws Exception {
        var target = house();
        assertEquals("a7113431e5db23a2f424f124e22897baae308275d168fb168c73360288fe3b56", target.revision());
        assertEquals(172, target.cells().size());
        var w = new DoorBuildTest.World();
        w.primary = new net.minecraft.core.BlockPos(-115,89,340);
        w.floorY = 87;
        target.cells().stream().filter(c -> !BuildObjects.paired(c.authored()))
                .forEach(c -> w.blocks.put(c.pos(),c.desired()));
        w.overrideProposed = target.index().get(w.primary).desired().setValue(DoorBlock.HINGE,DoorHingeSide.RIGHT);
        w.site = new BuildSite(); w.site.bind("house",w);
        var before = w.query(target);
        assertEquals(170,before.getAsJsonObject("counts").get("satisfied").getAsInt());
        assertEquals(0,before.getAsJsonObject("counts").get("unsupported").getAsInt());
        assertNull(w.site.begin(target,List.of(),w.primary.north(2),w.evidence));
        int doorOffset = target.cells().indexOf(target.index().get(w.primary)) / 16 * 16;
        var result = DoorBuildTest.finish(new BuildWork(target,w,doorOffset,12,()->0));
        assertTrue(result.ok(),result.feedback());
        assertTrue(result.data().get("goal_satisfied").getAsBoolean());
        assertEquals(1,w.actions); assertEquals(3,w.inventory.getItem(2).getCount());
        assertEquals(target.revision(),w.site.target().revision());
        assertTrue(BuildSurvey.verify(target,w,0,()->0).data().get("goal_satisfied").getAsBoolean());
    }

    @Test void unspecifiedHingeAcceptsEitherNativeSideAcrossAllRotationsWithoutChangingTheDrawing() {
        for (int rotation:List.of(0,90,180,270)) for (var hinge:DoorHingeSide.values()) {
            var input=DoorBuildTest.target().input(); input.addProperty("rotation",rotation);
            for (var cell:input.getAsJsonArray("cells"))
                cell.getAsJsonObject().getAsJsonArray("properties").remove(2);
            var target=BuildTarget.parse(input); var primary=target.cells().getFirst();
            assertEquals("",BuildObjects.problem(target,primary));
            var w=new DoorBuildTest.World();
            w.overrideProposed=primary.desired().setValue(DoorBlock.HINGE,hinge);
            assertNull(BlockPlacement.assessDesired(w,primary.pos(),2,Direction.UP,target,primary));
            assertFalse(primary.properties().contains("hinge"));
            assertEquals(target.revision(),BuildTarget.parse(target.input()).revision());
        }
    }

    @Test void optionalPropertyOnOneHalfStillConstrainsTheWholeNativeAction() {
        var input=DoorBuildTest.target().input();
        input.getAsJsonArray("cells").get(0).getAsJsonObject().getAsJsonArray("properties").remove(2);
        var target=BuildTarget.parse(input);
        assertEquals("",BuildObjects.problem(target,target.cells().getFirst()));
        var w=new DoorBuildTest.World();
        w.overrideProposed=target.cells().getFirst().desired().setValue(DoorBlock.HINGE,DoorHingeSide.RIGHT);
        assertNotNull(BlockPlacement.assessDesired(w,DoorBuildTest.LOWER,2,Direction.UP,target,target.cells().getFirst()));
        assertEquals(0,w.actions);
    }
}
