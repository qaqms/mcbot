package com.neko.mcbot.server;

import com.neko.mcbot.path.DigAStar;
import com.neko.mcbot.path.NavigationGeometry;
import com.neko.mcbot.path.TerrainEdits;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.neko.mcbot.path.NavigationGeometry.Motion.*;
import static org.junit.jupiter.api.Assertions.*;

class StandingGeometryTest {
    @BeforeAll static void registries() { BlockActionFixtures.bootstrap(); }
    static class Grid implements StandingGeometry.Access {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final Set<BlockPos> unknown = new HashSet<>(), unsupported = new HashSet<>();
        int reads;
        Grid() {
            for (int x = -2; x <= 4; x++) for (int z = -2; z <= 2; z++)
                blocks.put(new BlockPos(x, -1, z), Blocks.STONE.defaultBlockState());
        }
        @Override public boolean known(BlockPos pos) { return !unknown.contains(pos); }
        @Override public List<AABB> boxes(BlockPos pos) {
            assertFalse(unknown.contains(pos), "unknown cells must not be read");
            reads++;
            if (unsupported.contains(pos)) return null;
            return StandingGeometry.collision(blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()),
                    EmptyBlockGetter.INSTANCE, pos);
        }
        StandingGeometry geometry() { return new StandingGeometry(this, .6, 1.8, .6); }
    }

    @Test void slabAndSnowHeightsComeFromCollisionRatherThanOutlineOrSturdyFaces() {
        var grid = new Grid();
        var pos = BlockPos.ZERO;
        var geometry = grid.geometry();
        grid.blocks.put(pos, Blocks.OAK_SLAB.defaultBlockState());
        assertEquals(.5, geometry.standing(0, 0, 0, TerrainEdits.EMPTY).height());
        assertNull(geometry.foot(0, 1, 0, TerrainEdits.EMPTY));
        for (int layers = 1; layers <= 8; layers++) {
            grid.blocks.put(pos, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers));
            var foot = geometry.standing(0, 0, 0, TerrainEdits.EMPTY);
            assertNotNull(foot);
            assertEquals((layers - 1) / 8.0, foot.height());
            assertEquals(DigAStar.pack(0, layers == 1 ? -1 : 0, 0), foot.support());
        }
        for (var type : List.of(SlabType.TOP, SlabType.DOUBLE)) {
            grid.blocks.put(pos, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, type));
            assertEquals(1, geometry.standing(0, 1, 0, TerrainEdits.EMPTY).height());
        }
    }

    @Test void loweringAWorkColumnNeedsAKnownSafeLandingAndRejectsAHoleOrPartialSupport() {
        var grid = new Grid();
        grid.blocks.put(BlockPos.ZERO,Blocks.STONE.defaultBlockState());
        var landing = grid.geometry().lowerThrough(0,1,0);
        assertNotNull(landing);
        assertEquals(0,landing.height());
        assertEquals(DigAStar.pack(0,-1,0),landing.support());
        assertEquals(Blocks.STONE.defaultBlockState(),grid.blocks.get(BlockPos.ZERO));
        grid.blocks.remove(BlockPos.ZERO.below());
        assertNull(grid.geometry().lowerThrough(0,1,0));
        grid.blocks.put(BlockPos.ZERO.below(),Blocks.MAGMA_BLOCK.defaultBlockState());
        assertNull(grid.geometry().lowerThrough(0,1,0));
        grid.blocks.put(BlockPos.ZERO.below(),Blocks.STONE.defaultBlockState());
        grid.unknown.add(BlockPos.ZERO.below());
        assertNull(grid.geometry().lowerThrough(0,1,0));
        grid.unknown.clear();
        grid.blocks.put(BlockPos.ZERO,Blocks.OAK_SLAB.defaultBlockState());
        assertNull(grid.geometry().lowerThrough(0,0,0));
    }

    @Test void theLastRoofGapIsDeferredFromAnInteriorSupportButCanBeClosedFromOutside() {
        var grid=new Grid();
        grid.blocks.put(BlockPos.ZERO,Blocks.COBBLESTONE.defaultBlockState());
        grid.blocks.put(BlockPos.ZERO.above(),Blocks.COBBLESTONE.defaultBlockState());
        var gap=new BlockPos(0,3,-1);
        for (int x=-1;x<=1;x++) for(int z=-1;z<=1;z++)
            if ((x!=0 || z!=0) && !new BlockPos(x,3,z).equals(gap))
                grid.blocks.put(new BlockPos(x,3,z),Blocks.OAK_PLANKS.defaultBlockState());
        var original=Map.copyOf(grid.blocks);
        var proposed=Map.of(gap,Blocks.OAK_PLANKS.defaultBlockState());
        assertFalse(grid.geometry().preservesExit(0,2,0,proposed,TerrainEdits.EMPTY));
        assertTrue(grid.geometry().preservesExit(0,0,-2,proposed,TerrainEdits.EMPTY),
                "the same authored cell can be finished after moving outside");
        assertEquals(original,grid.blocks,"preflight never edits the world");
        grid.blocks.remove(new BlockPos(1,3,0));
        assertTrue(grid.geometry().preservesExit(0,2,0,proposed,TerrainEdits.EMPTY),
                "another existing exit is sufficient; do not impose a fixed route");
    }

    @Test void aPlannedSupportAndItsReplacementsAreIncludedInTheSameExitCheck() {
        var grid=new Grid();
        grid.blocks.put(BlockPos.ZERO,Blocks.COBBLESTONE.defaultBlockState());
        var edits=TerrainEdits.EMPTY.after(List.of(),List.of(DigAStar.pack(0,1,0)));
        var gap=new BlockPos(0,3,-1);
        for(int x=-1;x<=1;x++) for(int z=-1;z<=1;z++)
            if((x!=0 || z!=0) && !new BlockPos(x,3,z).equals(gap))
                grid.blocks.put(new BlockPos(x,3,z),Blocks.OAK_PLANKS.defaultBlockState());
        assertFalse(grid.geometry().preservesExit(0,2,0,Map.of(gap,Blocks.OAK_PLANKS.defaultBlockState()),edits));
        assertEquals(Set.of(DigAStar.pack(0,1,0)),edits.placed());
        assertTrue(grid.geometry().preservesExit(0,2,0,Map.of(gap,Blocks.AIR.defaultBlockState()),edits));
    }

    @Test void stairsDoNotOfferAFalseHalfHeightStanceAndTheirFrontWalksWhileTheBackJumps() {
        var grid = new Grid();
        grid.blocks.put(new BlockPos(1, 0, 0), Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.EAST));
        var geometry = grid.geometry();
        assertNull(geometry.standing(1, 0, 0, TerrainEdits.EMPTY));
        assertEquals(1, geometry.standing(1, 1, 0, TerrainEdits.EMPTY).height());
        assertEquals(WALK, geometry.motion(0, 0, 0, 1, 1, 0, TerrainEdits.EMPTY));
        assertEquals(JUMP, geometry.motion(2, 0, 0, 1, 1, 0, TerrainEdits.EMPTY));
    }

    @Test void lowCeilingsAndThinShapesAcrossCellEdgesCannotBeTunneledThrough() {
        var grid = new Grid();
        var geometry = grid.geometry();
        grid.blocks.put(new BlockPos(1, 0, 0), Blocks.OAK_SLAB.defaultBlockState());
        grid.blocks.put(new BlockPos(1, 2, 0), Blocks.STONE.defaultBlockState());
        assertNull(geometry.standing(1, 0, 0, TerrainEdits.EMPTY));
        assertEquals(BLOCKED, geometry.motion(0, 0, 0, 1, .5, 0, TerrainEdits.EMPTY));
        grid.blocks.clear();
        for (int x = 0; x <= 1; x++) grid.blocks.put(new BlockPos(x, -1, 0), Blocks.STONE.defaultBlockState());
        grid.blocks.put(new BlockPos(1, 0, 0), Blocks.OAK_DOOR.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DoorBlock.FACING, Direction.EAST));
        assertEquals(BLOCKED, geometry.motion(0, 0, 0, 1, 0, 0, TerrainEdits.EMPTY));
    }

    @Test void unknownAndUnsupportedGeometryCannotBecomeSupportEvenThroughAnEditOverlay() {
        var grid = new Grid();
        var geometry = grid.geometry();
        grid.unknown.add(new BlockPos(1, -1, 0));
        var edits = TerrainEdits.EMPTY.after(List.of(), List.of(DigAStar.pack(1, -1, 0)));
        assertNull(geometry.foot(1, 0, 0, edits));
        assertEquals(BLOCKED, geometry.motion(0, 0, 0, 1, 0, 0, edits));
        grid.unknown.clear();
        grid.unsupported.add(new BlockPos(1, 0, 0));
        assertNull(geometry.standing(1, 0, 0, TerrainEdits.EMPTY));
        assertEquals(BLOCKED, geometry.motion(0, 0, 0, 1, 0, 0, TerrainEdits.EMPTY));
    }

    @Test void editedSupportAndActualSupportChangesAreReadFreshWithoutMutatingTheWorld() {
        var grid = new Grid();
        var geometry = grid.geometry();
        var floor = DigAStar.pack(1, -1, 0);
        var removed = TerrainEdits.EMPTY.after(List.of(floor), List.of());
        assertNull(geometry.foot(1, 0, 0, removed));
        var placed = removed.after(List.of(), List.of(floor));
        assertEquals(0, geometry.standing(1, 0, 0, placed).height());
        assertTrue(grid.blocks.containsKey(new BlockPos(1, -1, 0)));
        grid.blocks.put(BlockPos.ZERO, Blocks.OAK_SLAB.defaultBlockState());
        assertEquals(.5, geometry.standing(0, 0, 0, TerrainEdits.EMPTY).height());
        grid.blocks.remove(BlockPos.ZERO);
        assertEquals(0, geometry.standing(0, 0, 0, TerrainEdits.EMPTY).height());
    }

    @Test void pickupContactAtAPitBottomUsesTheSameFractionalBodyBounds() {
        var grid = new Grid();
        grid.blocks.put(BlockPos.ZERO, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 5));
        var geometry = grid.geometry();
        var foot = geometry.standing(0, 0, 0, TerrainEdits.EMPTY);
        var item = new AABB(.4, .5, .4, .65, .75, .65);
        assertTrue(GroundItems.touches(geometry.body(0, foot.height(), 0), item));
        assertFalse(GroundItems.touches(geometry.body(0, 2, 0), item),
                "standing on the pit rim is not arrival for pickup");
    }

    @Test void fencesCannotBeClimbedFromFlatGroundAndExcessivelyComplexShapesStop() {
        var grid = new Grid();
        grid.blocks.put(new BlockPos(1, 0, 0), Blocks.OAK_FENCE.defaultBlockState());
        assertEquals(BLOCKED, grid.geometry().motion(0, 0, 0, 1, 1.5, 0, TerrainEdits.EMPTY));
        var complex = new StandingGeometry(new StandingGeometry.Access() {
            @Override public boolean known(BlockPos pos) { return true; }
            @Override public List<AABB> boxes(BlockPos pos) {
                return java.util.Collections.nCopies(17, new AABB(0, 0, 0, 1, 1, 1));
            }
        }, .6, 1.8, .6);
        assertNull(complex.foot(0, 0, 0, TerrainEdits.EMPTY));
    }

    @Test void feetCellToleratesCollisionRoundoffAtPositiveAndNegativeBoundaries() {
        assertEquals(1, NavigationGeometry.cell(1 - 1e-7));
        assertEquals(-1, NavigationGeometry.cell(-1 - 1e-7));
        assertEquals(-2, NavigationGeometry.cell(-1.5));
    }

    @Test void departureUsesTheColumnUnderTheActualSoleAtEitherSideOfACellBoundary() {
        for (int direction : new int[]{-1, 1}) {
            var grid = new Grid();
            grid.blocks.clear();
            var floor = new BlockPos(direction > 0 ? 1 : -1, -1, 0);
            grid.blocks.put(floor, Blocks.STONE.defaultBlockState());
            double x = direction > 0 ? .98 : .02;
            var geometry = grid.geometry();
            assertNull(geometry.standing(0, 0, 0, TerrainEdits.EMPTY),
                    "the floored center alone cannot seed a route here");
            assertEquals(floor.above(), geometry.departure(x, 0, .5, p -> true));
            assertTrue(geometry.canAlign(x, 0, .5, floor.above()));
            assertEquals(Map.of(floor, Blocks.STONE.defaultBlockState()), grid.blocks);
            assertNull(geometry.departure(x, 0, .5, p -> p.getX() == 0),
                    "alignment cannot leave the original allowed scope");
        }
    }

    @Test void departureNeedsActualSupportRatherThanBorrowingAnUnrelatedNearbyFloor() {
        var grid = new Grid();
        grid.blocks.clear();
        grid.blocks.put(new BlockPos(1, -1, 0), Blocks.STONE.defaultBlockState());
        assertNull(grid.geometry().departure(.5, 0, .5, p -> true));
        assertFalse(grid.geometry().canAlign(.5, 0, .5, new BlockPos(1, 0, 0)));
        assertNull(grid.geometry().departure(Double.NaN, 0, .5, p -> true));
        grid.blocks.put(new BlockPos(1, 0, 0), Blocks.OAK_SLAB.defaultBlockState());
        assertNull(grid.geometry().departure(.98, 0, .5, p -> true), "cannot silently lift the body");
        assertEquals(new BlockPos(1, 0, 0), grid.geometry().departure(.98, .5, .5, p -> true),
                "a genuinely supported fractional pose can align at its actual height");
    }

    @Test void cornerDepartureReadsOnlyTheRealSupportLayerAtTheWorldBottom() {
        var floor = new BlockPos(1, -1, 1);
        var geometry = new StandingGeometry(new StandingGeometry.Access() {
            @Override public boolean known(BlockPos pos) { return pos.getY() >= -1; }
            @Override public List<AABB> boxes(BlockPos pos) {
                assertTrue(pos.getY() >= -1);
                return floor.equals(pos) ? List.of(new AABB(0, 0, 0, 1, 1, 1)) : List.of();
            }
        }, .6, 1.8, .6);
        assertEquals(floor.above(), geometry.departure(.98, 0, .98, p -> true));
        assertNull(geometry.departure(.5, 0, .5, p -> true));
    }

    @Test void departureCannotCrossWallsOrUnknownGeometryAndRevalidatesChangedSupport() {
        var grid = new Grid();
        var geometry = grid.geometry();
        grid.blocks.put(BlockPos.ZERO, Blocks.OAK_DOOR.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DoorBlock.FACING, Direction.WEST));
        assertFalse(geometry.canAlign(1.31, 0, .5, BlockPos.ZERO));
        grid.blocks.remove(BlockPos.ZERO);
        assertTrue(geometry.canAlign(.98, 0, .5, BlockPos.ZERO));
        grid.unknown.add(new BlockPos(1, 1, 0));
        assertFalse(geometry.canAlign(.98, 0, .5, BlockPos.ZERO));
        grid.unknown.clear();
        grid.blocks.remove(BlockPos.ZERO.below());
        assertFalse(geometry.canAlign(.98, 0, .5, BlockPos.ZERO));
        grid.blocks.put(BlockPos.ZERO.below(), Blocks.MAGMA_BLOCK.defaultBlockState());
        assertFalse(geometry.canAlign(.98, 0, .5, BlockPos.ZERO));
    }

    @Test void dryGroundRejectsFluidsHazardsAndEntityDependentSupport() {
        for (var block : List.of(Blocks.WATER, Blocks.LAVA, Blocks.SCAFFOLDING, Blocks.POWDER_SNOW,
                Blocks.FIRE, Blocks.COBWEB, Blocks.CACTUS, Blocks.MAGMA_BLOCK)) {
            assertNull(StandingGeometry.collision(block.defaultBlockState(), EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
        }
    }

    @Test void fractionalWorkEyesKeepTheirSupportInTheRayAndUseTheActualReach() {
        var world = new BlockAimTest.Grid();
        world.blocks.clear();
        world.blocks.put(BlockPos.ZERO, Blocks.OAK_SLAB.defaultBlockState());
        var below = BlockPos.ZERO.below();
        world.blocks.put(below, Blocks.STONE.defaultBlockState());
        var geometry = new StandingGeometry(new StandingGeometry.Access() {
            @Override public boolean known(BlockPos pos) { return true; }
            @Override public List<AABB> boxes(BlockPos pos) {
                return world.blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState())
                        .getCollisionShape(EmptyBlockGetter.INSTANCE, pos).toAabbs();
            }
        }, .6, 1.8, .6);
        var goal = NavigationWork.goal(world, below, 1.62, 4.5, false, p -> false, geometry);
        assertFalse(goal.reached(0, 0, 0), "hypothetical work must not erase the slab carrying the body");
        var target = new BlockPos(3, 2, 0);
        world.blocks.put(target, Blocks.STONE.defaultBlockState());
        var eye = new net.minecraft.world.phys.Vec3(.5, 2.12, .5);
        for (double reach : new double[]{2, 2.5, 3, 4.5}) {
            assertEquals(BlockAim.mining(BlockAim.fromEye(world, eye), target, reach).ok(),
                    NavigationWork.goal(world, target, 1.62, reach, false, p -> false, geometry).reached(0, 0, 0));
        }
    }
}
