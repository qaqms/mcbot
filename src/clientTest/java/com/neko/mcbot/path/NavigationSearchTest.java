package com.neko.mcbot.path;

import com.neko.mcbot.server.BlockActionFixtures;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class NavigationSearchTest {
    @BeforeAll static void registries() { BlockActionFixtures.bootstrap(); }

    @SuppressWarnings("unchecked")
    private PalettedContainer<BlockState>[] sections() {
        var strategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        return new PalettedContainer[]{
                new PalettedContainer<>(Blocks.STONE.defaultBlockState(), strategy),
                new PalettedContainer<>(Blocks.AIR.defaultBlockState(), strategy),
                new PalettedContainer<>(Blocks.AIR.defaultBlockState(), strategy)};
    }
    private NavigationSnapshot capture(PalettedContainer<BlockState>[] sections) {
        Thread world = Thread.currentThread();
        return new NavigationSnapshot(new NavigationSnapshot.Bounds(-16, 48, -100, 100, -100, 100),
                (x, z) -> {
                    assertSame(world, Thread.currentThread(), "capture is the only call to the source");
                    return x == 0 && z == 0 ? sections : null;
                }, new BlockPos(4, 0, 4), .6, 1.8, .6, PathSpec.DEFAULT, PathSpec.DEFAULT);
    }
    private DigAStar search(DigSampler terrain, NavigationGoal goal) {
        var search = new DigAStar(terrain, 4, 0, 4, 10, 0, 4, NavigationBudget.NODES, 0, goal);
        search.totalBudget(NavigationBudget.NANOS);
        return search;
    }
    private void await(NavigationSearch pending) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!pending.done() && System.nanoTime() < until) Thread.sleep(1);
        assertTrue(pending.done());
    }

    @Test void copiedSectionsRemainStableAndUnknownChunksNeverBecomeAir() {
        var source = sections();
        var snapshot = capture(source);
        source[1].set(7, 0, 4, Blocks.STONE.defaultBlockState());
        assertTrue(snapshot.passable(7, 0, 4));
        assertFalse(capture(source).passable(7, 0, 4));
        assertFalse(snapshot.known(20, 0, 4));
        assertFalse(snapshot.passable(20, 0, 4));
        assertNull(snapshot.geometry().foot(20, 0, 4, TerrainEdits.EMPTY));
        assertFalse(snapshot.placeable(4, 0, 4));
        assertFalse(DigSampler.feasibleDig(snapshot.digSeconds(4, 0, 4)));
    }

    @Test void workerFindsTheSameDryRoofDescentAndKeepsTheCapturedFallPolicy() throws Exception {
        var source = sections();
        for (int y = 0; y < 3; y++) source[1].set(4,y,4,Blocks.OAK_LOG.defaultBlockState());
        var start = new BlockPos(4,3,4);
        var bounds = new NavigationSnapshot.Bounds(-16,48,-100,100,-100,100);
        var snapshot = new NavigationSnapshot(bounds,(x,z) -> x==0 && z==0 ? source : null,
                start,.6,1.8,.6,PathSpec.DEFAULT,PathSpec.DEFAULT,3);
        var route = new DigAStar(snapshot,4,3,4,10,0,4,1000,0,NavigationGoal.position(10,0,4,0));
        var pending = NavigationSearch.submit(route);
        await(pending);
        assertNull(pending.take().failure());
        assertNull(PathTask.planFailure(route.path(),snapshot,0));
        assertTrue(route.path().stream().allMatch(s -> s.dig().isEmpty() && s.place().isEmpty()));
        var reduced = new NavigationSnapshot(bounds,(x,z) -> x==0 && z==0 ? source : null,
                start,.6,1.8,.6,PathSpec.DEFAULT,PathSpec.DEFAULT,1);
        assertNotNull(PathTask.planFailure(route.path(),reduced,0));
        source[1].set(4,3,4,Blocks.STONE.defaultBlockState());
        assertTrue(snapshot.passable(4,3,4), "worker terrain remains isolated from the live world");
    }

    @Test void searchRunsOnWorkerAndLiveGeometryStillRejectsAChangedRoute() throws Exception {
        var source = sections();
        var snapshot = capture(source);
        var thread = new AtomicReference<Thread>();
        var goal = new NavigationGoal() {
            @Override public boolean reached(int x, int y, int z) {
                thread.set(Thread.currentThread()); return x == 10 && y == 0 && z == 4;
            }
            @Override public double remaining(int x, int y, int z) {
                return Math.abs(x - 10) + Math.abs(y) + Math.abs(z - 4);
            }
        };
        var pending = NavigationSearch.submit(search(snapshot, goal));
        await(pending);
        var result = pending.take();
        assertNull(result.failure());
        assertTrue(PathTask.startsAt(result.path(), new BlockPos(4, 0, 4)));
        assertFalse(PathTask.startsAt(result.path(), new BlockPos(5, 0, 4)),
                "a body pushed while searching must not adopt the old start");
        assertNotSame(Thread.currentThread(), thread.get());
        assertTrue(thread.get().getName().startsWith("mcbot-navigation-"));
        assertNull(PathTask.planFailure(result.path(), snapshot, 0));
        source[1].set(7, 0, 4, Blocks.STONE.defaultBlockState());
        assertNotNull(PathTask.planFailure(result.path(), capture(source), 0));
    }

    @Test void pickupWorkerUsesOnlyCopiedTerrainAndCanStopBelowTheObservedItemCell() throws Exception {
        var source = sections();
        var snapshot = capture(source);
        var item = new net.minecraft.world.phys.AABB(10.3, 2.1, 4.3, 10.55, 2.35, 4.55);
        var liveGoal = new com.neko.mcbot.server.PickupGoal(item, .6, 1.8,
                capture(source).geometry());
        var goal = liveGoal.snapshot(snapshot);
        source[0].set(9, 15, 4, Blocks.AIR.defaultBlockState());
        source[0].set(10, 15, 4, Blocks.AIR.defaultBlockState());
        var pending = NavigationSearch.submit(search(snapshot, goal));
        await(pending);
        var result = pending.take();
        assertNull(result.failure(), result.failure());
        var end = result.path().getLast();
        assertEquals(0, end.y());
        assertTrue(goal.reached(end.x(), end.y(), end.z()));
        assertNull(PathTask.planFailure(result.path(), snapshot, 0));
        assertNotNull(PathTask.planFailure(result.path(), capture(source), 0),
                "the live route still rejects support removed after snapshot capture");
    }

    @Test void cancellationDiscardsTheLateWorkerResult() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var terrain = capture(sections());
        var goal = new NavigationGoal() {
            @Override public boolean reached(int x, int y, int z) {
                entered.countDown();
                try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                finally { exited.countDown(); }
                return true;
            }
            @Override public double remaining(int x, int y, int z) { return 1; }
        };
        var pending = NavigationSearch.submit(search(terrain, goal));
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            pending.cancel();
            assertTrue(pending.done());
        } finally { release.countDown(); }
        assertTrue(exited.await(3, TimeUnit.SECONDS));
        assertThrows(java.util.concurrent.CancellationException.class, pending::take);
    }

    @Test void aPublishedSnapshotFrontierContinuesThroughTheProductionTripPolicy() throws Exception {
        var snapshot = capture(sections());
        var search = new DigAStar(snapshot, 4, 0, 4, 10, 0, 4, 2, 0,
                NavigationGoal.position(10, 0, 4, 0)).segments();
        var pending = NavigationSearch.submit(search);
        await(pending);
        var completed = pending.take();
        assertSame(search, completed);
        assertEquals("NODES", completed.budgetKind());
        var shared = new NavigationBudget();
        shared.recordCompleted(completed);
        var allowance = new NavigationAllowance(6);
        assertTrue(PathTask.continueSearch(completed, allowance, shared));
        assertEquals(2, allowance.used());
        while (!completed.advance(300, 6_000_000L)) { }
        shared.observe(completed);
        allowance.observe(completed);
        assertNull(completed.failure());
        assertEquals(2, completed.batches());
        assertEquals(completed.chargedNodes(), shared.nodes());
        assertEquals(completed.chargedNodes(), allowance.used());
        assertNull(PathTask.planFailure(completed.path(), snapshot, 0));
    }

    @Test void accumulatedTripDiagnosticsDoNotPreventContinuingTheNextBatch() throws Exception {
        var snapshot = capture(sections());
        var search = new DigAStar(snapshot, 4, 0, 4, 10, 0, 4, 2, 0,
                NavigationGoal.position(10, 0, 4, 0)).segments();
        while (!search.advance(1)) { }
        var allowance = new NavigationAllowance(6);
        var used = NavigationAllowance.class.getDeclaredField("used");
        used.setAccessible(true);
        used.setLong(allowance, allowance.limit() - 2);
        assertTrue(PathTask.continueSearch(search, allowance, null));
        assertEquals(allowance.limit(), allowance.used());
        assertFalse(search.done());
        assertEquals(2, search.batches());
    }

    @Test void highTreeSamplesDoNotForceClimbingWhenAGroundWorkStanceIsReachable() throws Exception {
        var source = sections();
        var target = new BlockPos(10, 4, 4);
        source[1].set(10, 4, 4, Blocks.OAK_LOG.defaultBlockState());
        var snapshot = capture(source);
        var live = com.neko.mcbot.server.NavigationWork.goal(snapshot.sight(net.minecraft.world.phys.Vec3.ZERO),
                target, 1.62, 4.5, true, pos -> { fail("live safe predicate reached the worker"); return false; },
                snapshot.geometry());
        var frozen = live.snapshot(snapshot);
        assertNotNull(frozen);
        var route = new DigAStar(snapshot, 4, 0, 4, 10, 4, 4, 4000, 0, frozen).segments();
        var pending = NavigationSearch.submit(route);
        await(pending);
        assertNull(pending.take().failure());
        var landing = route.path().getLast();
        assertEquals(0, landing.y(), "the tree can be mined from ground level");
        assertTrue(frozen.reached(landing.x(), landing.y(), landing.z()));
        assertTrue(route.path().stream().allMatch(step -> step.dig().isEmpty() && step.place().isEmpty()));
        var exact = PathTask.searchFor(snapshot, new BlockPos(4, 0, 4), new BlockPos(9, 4, 4), true, true);
        while (!exact.advance(300)) { }
        assertNotNull(exact.failure(), "a sampled high stand is not a reachable route");
        source[1].set(10, 4, 4, Blocks.AIR.defaultBlockState());
        assertTrue(frozen.reached(landing.x(), landing.y(), landing.z()), "copied target stays stable");
        assertFalse(capture(source).workGoal(target, 1.62, 4.5).reached(landing.x(), landing.y(), landing.z()),
                "a fresh goal rejects the removed target");
    }

    @Test void snapshotEditedRaysRespectRemovedCellsPlacedSupportsAndUnknownChunks() {
        var source = sections();
        source[1].set(7, 1, 4, Blocks.STONE.defaultBlockState());
        source[1].set(10, 1, 4, Blocks.OAK_LOG.defaultBlockState());
        var snapshot = capture(source);
        var goal = snapshot.workGoal(new BlockPos(10, 1, 4), 1.62, 5.5);
        assertFalse(goal.reached(5, 0, 4));
        var opening = TerrainEdits.EMPTY.after(java.util.List.of(DigAStar.pack(7, 1, 4)), java.util.List.of());
        assertTrue(goal.reached(5, 0, 4, opening));
        assertFalse(goal.reached(5, 0, 4,
                opening.after(java.util.List.of(), java.util.List.of(DigAStar.pack(8, 1, 4)))));
        assertFalse(snapshot.workGoal(new BlockPos(17, 1, 4), 1.62, 5.5).reached(14, 0, 4));
    }
}
