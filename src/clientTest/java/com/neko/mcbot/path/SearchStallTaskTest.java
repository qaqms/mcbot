package com.neko.mcbot.path;

import com.neko.mcbot.task.TickTask;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class SearchStallTaskTest {
    @BeforeAll static void bootstrap() { com.neko.mcbot.server.BlockActionFixtures.bootstrap(); }
    private void field(PathTask task,String name,Object value) throws Exception {
        var field=PathTask.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(task,value);
    }

    @Test void retargetingAndAChangedSearchObjectCannotResetTheLeaseOrOriginalAccount() throws Exception {
        var budget=new NavigationBudget();
        var task=PathTask.approach(null,new BlockPos(2,64,0),null,null,null,PathSpec.DEFAULT,
                NavigationGoal.position(2,64,0,0),budget);
        var clock=new AtomicLong();
        field(task,"searchStall",new SearchStall(clock::get));
        for (int tick=0;tick<600;tick++) {
            clock.addAndGet(50_000_000);
            if (tick%20==0) {
                task.retarget(new BlockPos(2+tick,64,0),NavigationGoal.position(2+tick,64,0,0));
                field(task,"search",new DigAStar(new Floor(),0,64,0,2+tick,64,0,4000,0));
            }
            assertNull(task.searchProgress(true,.5,64,.5));
        }
        clock.addAndGet(50_000_000);
        var done=assertInstanceOf(TickTask.Progress.Done.class,task.searchProgress(true,.5,64,.5));
        var result=done.result();
        assertFalse(result.ok());
        assertTrue(result.feedback().startsWith("NAVIGATION_STALLED:"),result.feedback());
        assertTrue(result.data().get("model_reassessment_required").getAsBoolean());
        assertFalse(result.data().get("goal_satisfied").getAsBoolean());
        assertEquals(30000,result.data().get("search_idle_ms").getAsInt());
        assertEquals(582,result.data().getAsJsonObject("requested_target").get("x").getAsInt());
        assertFalse(result.data().get("departure_blocked").getAsBoolean());
        assertEquals(0,budget.replans(),"the timeout neither renews nor replaces the shared account");
    }

    @Test void anIdleStopPreservesPriorEffectsAndDiagnostics() throws Exception {
        var task=new PathTask(null,new BlockPos(2,64,0),null,null,null,null);
        var clock=new AtomicLong();
        field(task,"searchStall",new SearchStall(clock::get));
        field(task,"actualDigs",2); field(task,"actualPlaces",1);
        var search=new DigAStar(new Floor(),0,64,0,2,64,0,4000,0);
        while (!search.advance(300)) { }
        field(task,"search",search);
        for (int tick=0;tick<600;tick++) {
            clock.addAndGet(50_000_000);
            assertNull(task.searchProgress(true,.5,64,.5));
        }
        clock.addAndGet(50_000_000);
        var done=assertInstanceOf(TickTask.Progress.Done.class,task.searchProgress(true,.5,64,.5));
        assertEquals(2,done.result().data().get("removed_blocks").getAsInt());
        assertEquals(1,done.result().data().get("placed_blocks").getAsInt());
        assertEquals(search.expanded(),done.result().data().getAsJsonObject("search_diagnostics")
                .get("expanded").getAsInt());
        assertTrue(done.result().feedback().contains("已完成的改动"));
    }

    @Test void aStalledBackgroundSearchIsCancelledWithoutWaitingForItsWorker() throws Exception {
        var entered=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var exited=new CountDownLatch(1);
        var goal=new NavigationGoal() {
            @Override public double remaining(int x,int y,int z) { return 1; }
            @Override public boolean reached(int x,int y,int z) {
                entered.countDown();
                try { assertTrue(release.await(3,TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                finally { exited.countDown(); }
                return true;
            }
        };
        var pending=NavigationSearch.submit(new DigAStar(new Floor(),0,64,0,2,64,0,4000,0,goal));
        var task=new PathTask(null,new BlockPos(2,64,0),null,null,null,null);
        var clock=new AtomicLong();
        field(task,"searchStall",new SearchStall(clock::get));
        field(task,"pendingSearch",pending);
        try {
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            for (int tick=0;tick<600;tick++) {
                clock.addAndGet(50_000_000);
                assertNull(task.searchProgress(true,.5,64,.5));
            }
            clock.addAndGet(50_000_000);
            assertInstanceOf(TickTask.Progress.Done.class,task.searchProgress(true,.5,64,.5));
            assertTrue(pending.done(),"the task releases the job before the held worker exits");
            assertThrows(CancellationException.class,pending::take);
            assertEquals(1,exited.getCount(),"the main thread did not wait for the worker");
        } finally { release.countDown(); task.onAbort(); }
        assertTrue(exited.await(3,TimeUnit.SECONDS));
    }

    static class Floor implements DigSampler {
        @Override public boolean passable(int x,int y,int z) { return y>=64; }
        @Override public boolean support(int x,int y,int z) { return y==63; }
        @Override public boolean inBounds(int x,int y,int z) {
            return x>=-2 && x<=4 && z>=-2 && z<=2 && y>=63 && y<=67;
        }
        @Override public double digSeconds(int x,int y,int z) { return INFEASIBLE; }
        @Override public boolean placeable(int x,int y,int z) { return false; }
        @Override public double placeCost(int x,int y,int z) { return INFEASIBLE; }
        @Override public int maxPlaces() { return 0; }
    }
}
