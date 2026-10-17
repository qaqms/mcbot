package com.neko.mcbot.server;

import com.neko.mcbot.path.DigAStar;
import com.neko.mcbot.path.DigSampler;
import com.neko.mcbot.path.NavigationGoal;
import com.neko.mcbot.path.PathSpec;
import com.neko.mcbot.path.TerrainEdits;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RetreatWorkTest {
    @BeforeAll static void registries() { BlockActionFixtures.bootstrap(); }

    private final ThreatFacts.Threat threat = new ThreatFacts.Threat(new Object(), 22, UUID.randomUUID(),
            new Vec3(2, 0, .5), new AABB(.5, 0, -.5, 3.5, 2, 1.5), 0, true);
    private final ThreatFacts.Snapshot facts = new ThreatFacts.Snapshot(List.of(threat), true);

    private static StandingGeometryTest.Grid floor() {
        var grid = new StandingGeometryTest.Grid();
        grid.blocks.clear();
        for (int x = -7; x <= 7; x++) for (int z = -7; z <= 7; z++)
            grid.blocks.put(new BlockPos(x, -1, z), Blocks.STONE.defaultBlockState());
        return grid;
    }
    private BodyDefense.Frame frame(ThreatFacts.Snapshot observed) {
        return new BodyDefense.Frame(this, true, false, false, new Vec3(.5, 0, .5),
                new AABB(.2, 0, .2, .8, 1.8, .8), 4, 20, true, observed);
    }

    @Test void destinationUsesRealStandingGeometryAndBothOriginalAndLocalScope() {
        var grid = floor();
        var geometry = grid.geometry();
        var scope = new PathSpec(false, false, false, 0, BlockPos.ZERO, 4);
        var p = RetreatWork.haven(frame(facts), BlockPos.ZERO, scope, geometry);
        assertNotNull(p);
        assertTrue(scope.contains(p.getX(), p.getY(), p.getZ()));
        assertTrue(p.getX() < 0);
        var foot = geometry.standing(p.getX(), p.getY(), p.getZ(), TerrainEdits.EMPTY);
        assertNotNull(foot);
        assertFalse(threat.dangerous(geometry.body(p.getX(), foot.height(), p.getZ()), 1.5));
        assertEquals(225, grid.blocks.size(), "destination choice changes no blocks");
    }

    @Test void unknownMissingSupportAndInsufficientOriginalRangeCannotInventAHaven() {
        var grid = floor();
        grid.blocks.clear();
        assertNull(RetreatWork.haven(frame(facts), BlockPos.ZERO, PathSpec.DEFAULT, grid.geometry()));
        grid = floor();
        for (var p : grid.blocks.keySet()) grid.unknown.add(p);
        assertNull(RetreatWork.haven(frame(facts), BlockPos.ZERO, PathSpec.DEFAULT, grid.geometry()));
        grid = floor();
        assertNull(RetreatWork.haven(frame(facts), BlockPos.ZERO,
                new PathSpec(false, false, true, 0, BlockPos.ZERO, 1), grid.geometry()));
    }

    @Test void blastGeometryCannotBeOverriddenByAnAttackPermission() {
        var blast = new ThreatFacts.Threat(new Object(), 23, UUID.randomUUID(), Vec3.ZERO,
                new AABB(0, 0, 0, 1, 2, 1), 12, true);
        var observed = new ThreatFacts.Snapshot(List.of(blast), true);
        assertNull(RetreatWork.haven(frame(observed), BlockPos.ZERO, PathSpec.DEFAULT, floor().geometry()));
        assertTrue(blast.dangerous(frame(observed).bounds(), 1.5));
    }

    @Test void allSourcesParticipateAndASafeStepCannotEnterAnotherThreat() {
        var safe = new AABB(-3, 0, 0, -2.4, 1.8, .6);
        var nearer = safe.move(2, 0, 0);
        assertFalse(ThreatFacts.progresses(facts, safe, nearer));
        assertTrue(ThreatFacts.progresses(facts, nearer, safe));
        assertFalse(ThreatFacts.progresses(new ThreatFacts.Snapshot(List.of(), false), safe, nearer));
        var other = new ThreatFacts.Threat(new Object(), 24, UUID.randomUUID(), new Vec3(-4, 0, 0),
                new AABB(-5, 0, -1, -3, 2, 1), 0, false);
        assertFalse(ThreatFacts.progresses(new ThreatFacts.Snapshot(List.of(threat, other), true),
                nearer, safe));
    }

    @Test void bodyWidthHeightAndBlastDistanceUseActualBounds() {
        assertFalse(threat.dangerous(new AABB(-1, 0, 0, 0, 2, 1), 0));
        assertTrue(threat.dangerous(new AABB(-1, 0, 0, 1, 2, 1), 0));
        assertFalse(threat.dangerous(new AABB(1, 8, 0, 2, 10, 1), .75));
        var blast = new ThreatFacts.Threat(new Object(), 30, UUID.randomUUID(),
                new Vec3(0, 0, 0), threat.attackBox(), 6, false);
        assertTrue(blast.dangerous(new AABB(5.9, 0, 0, 6.5, 2, .6), 0));
        assertFalse(blast.dangerous(new AABB(6.1, 0, 0, 6.7, 2, .6), 0));
    }

    @Test void productionSearchUsesSameDangerStepPredicateAndDoesNotDigOrPlace() {
        var geometry = floor().geometry();
        var origin = frame(facts).bounds();
        var target = RetreatWork.haven(frame(facts), BlockPos.ZERO, PathSpec.DEFAULT, geometry);
        assertNotNull(target);
        DigSampler terrain = new DigSampler() {
            @Override public boolean inBounds(int x, int y, int z) { return Math.abs(x) <= 6 && Math.abs(z) <= 6 && y >= -1 && y <= 2; }
            @Override public boolean passable(int x, int y, int z) { return inBounds(x, y, z) && y >= 0; }
            @Override public boolean support(int x, int y, int z) { return y == -1 && inBounds(x, y, z); }
            @Override public double digSeconds(int x, int y, int z) { return INFEASIBLE; }
            @Override public boolean placeable(int x, int y, int z) { return false; }
            @Override public double placeCost(int x, int y, int z) { return INFEASIBLE; }
            @Override public int maxPlaces() { return 0; }
            @Override public boolean allowsStep(int fx, int fy, int fz, int x, int y, int z) {
                return ThreatFacts.progresses(facts, RetreatWork.at(origin, new BlockPos(fx, fy, fz)),
                        RetreatWork.at(origin, new BlockPos(x, y, z)));
            }
        };
        var goal = NavigationGoal.position(target.getX(), target.getY(), target.getZ(), 0);
        var search = new DigAStar(terrain, 0, 0, 0, target.getX(), target.getY(), target.getZ(), 8000, 0, goal);
        while (!search.advance(300, 6_000_000L)) { }
        assertNull(search.failure());
        assertTrue(search.path().size() > 1);
        var route = search.path();
        for (int i = 1; i < route.size(); i++) {
            var p = route.get(i);
            var previous = route.get(i - 1);
            assertTrue(p.dig().isEmpty());
            assertTrue(p.place().isEmpty());
            assertTrue(terrain.allowsStep(previous.x(), previous.y(), previous.z(), p.x(), p.y(), p.z()));
        }
    }
}
