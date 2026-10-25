package com.neko.mcbot.building;

import com.neko.mcbot.server.ServerTool;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BuildSearchMemoryTest {
    @BeforeAll static void bootstrap() { com.neko.mcbot.server.BlockActionFixtures.bootstrap(); }
    private static final ServerTool.Result STOP = new ServerTool.Result(false, "NAVIGATION_STALLED:fixture", null);
    private static final BlockPos TARGET = new BlockPos(0, 6, 0);
    private final BuildSearchMemory memory = new BuildSearchMemory();
    private Vec3 position = new Vec3(.5, 0, .5);
    private String inventory = "tools:4;supports:12";
    private int edits;
    private boolean changed, unknown;

    private BuildSearchMemory.Facts observe() {
        return BuildSearchMemory.observe(position, inventory, edits, List.of(TARGET),
                p -> unknown ? null : changed && p.equals(TARGET.below())
                        ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
    }
    @Test void aNewProgramOrRepeatedQueryCannotRestartAnUnchangedStalledWorkRoute() {
        var reads = new AtomicInteger();
        java.util.function.Supplier<BuildSearchMemory.Facts> facts = () -> { reads.incrementAndGet(); return observe(); };
        assertNull(memory.check("roof", facts));
        assertEquals(0, reads.get(), "healthy new routes do not scan retry evidence");
        memory.terminal("roof", STOP, facts);
        for (int i = 0; i < 3; i++) {
            var denied = memory.check("roof", facts);
            assertTrue(denied.feedback().startsWith("NAVIGATION_STALLED:"));
            assertTrue(denied.data().get("unchanged_stalled_search").getAsBoolean());
            assertFalse(denied.data().get("search_started").getAsBoolean());
        }
        assertNull(memory.check("other work", facts));
    }
    @Test void realMovementToolsMaterialsConstructionOrLocalGeometryPermitReassessment() {
        for (int kind = 0; kind < 4; kind++) {
            memory.terminal("roof", STOP, this::observe);
            switch (kind) {
                case 0 -> position = position.add(.3, 0, 0);
                case 1 -> inventory += ";shovel:1";
                case 2 -> edits++;
                case 3 -> changed = true;
            }
            assertNull(memory.check("roof", this::observe), "new evidence " + kind);
        }
    }
    @Test void jitterDoesNotRenewTheWorkSearchAndUnknownGeometryIsNeverAssertedUnchanged() {
        memory.terminal("roof", STOP, this::observe);
        position = position.add(.02, 0, 0);
        assertNotNull(memory.check("roof", this::observe));
        unknown = true;
        assertNull(memory.check("roof", this::observe));
    }
    @Test void ordinaryFailuresAreNotMadePermanentAndSuccessClearsTheOldStall() {
        memory.terminal("roof", new ServerTool.Result(false, "TARGET_BLOCKED:fixture", null), this::observe);
        assertNull(memory.check("roof", this::observe));
        memory.terminal("roof", STOP, this::observe);
        memory.terminal("roof", new ServerTool.Result(true, "arrived", null), this::observe);
        assertNull(memory.check("roof", this::observe));
    }
    @Test void evidenceIsNotCarriedIntoANewWorksiteOrPersistedAsAnOldWorldClaim() {
        var site = new BuildSite();
        var task = new Object();
        var world = new Object();
        site.bind(task, world);
        site.searches().terminal("roof", STOP, this::observe);
        site.continueTask(task, new Object(), world);
        assertNotNull(site.searches().check("roof", this::observe));
        site.bind(new Object(), world);
        assertNull(site.searches().check("roof", this::observe));
        assertFalse(site.snapshot().has("searches"), "live geometry is not restart evidence");
    }
}
