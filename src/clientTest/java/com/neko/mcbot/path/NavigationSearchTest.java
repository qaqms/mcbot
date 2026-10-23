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
        var search = new DigAStar(terrain, 4, 0, 4, 10, 0, 4, 8000, 0, goal);
        search.totalBudget(400_000_000L);
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
}
