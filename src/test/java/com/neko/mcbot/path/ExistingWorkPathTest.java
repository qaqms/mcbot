package com.neko.mcbot.path;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExistingWorkPathTest {
    private static final class Site implements DigSampler {
        final Set<Long> blocks = new java.util.HashSet<>();
        int stock = 16;
        int radius = 5;
        boolean levelOnly;
        @Override public boolean inBounds(int x, int y, int z) {
            return x >= -2 && x <= 12 && y >= 0 && y <= 6 && Math.abs(z) <= radius;
        }
        @Override public boolean passable(int x, int y, int z) { return !blocks.contains(DigAStar.pack(x,y,z)); }
        @Override public boolean support(int x, int y, int z) { return !passable(x,y,z); }
        @Override public double digSeconds(int x, int y, int z) { return y == 0 ? INFEASIBLE : .1; }
        @Override public boolean placeable(int x, int y, int z) { return passable(x,y,z); }
        @Override public double placeCost(int x, int y, int z) { return 1; }
        @Override public int maxPlaces() { return stock; }
        @Override public boolean allowsStep(int fx,int fy,int fz,int x,int y,int z) {
            return !levelOnly || fy == y;
        }
        Site() {
            for (int x=-2;x<=12;x++) for (int z=-radius;z<=radius;z++) block(x,0,z);
        }
        void block(int x,int y,int z) { blocks.add(DigAStar.pack(x,y,z)); }
    }

    private static NavigationGoal workAt(int tx,int ty,int tz) {
        var position = NavigationGoal.position(tx,ty,tz,0);
        return new NavigationGoal() {
            @Override public boolean estimateWorkStances() { return true; }
            @Override public Bounds estimateBounds() { return position.estimateBounds(); }
            @Override public boolean reached(int x,int y,int z) { return position.reached(x,y,z); }
            @Override public double remaining(int x,int y,int z) { return position.remaining(x,y,z); }
        };
    }

    private static DigAStar search(Site site,NavigationGoal goal,int nodes,int digs,TerrainEdits initial) {
        var route = new DigAStar(site,0,1,0,10,1,0,nodes,digs,goal).segments().after(initial);
        for (int i=0;i<20000 && !route.done();i++) route.advance(1);
        assertTrue(route.done());
        return route;
    }

    @Test void aDoorAroundTheWallIsPreferredToTheEditableStraightLine() {
        var site = new Site();
        for (int z=-3;z<=3;z++) for (int y=1;y<=3;y++) site.block(4,y,z);
        var original = Set.copyOf(site.blocks);
        var route = search(site,workAt(10,1,0),4000,32,TerrainEdits.EMPTY);
        assertNull(route.failure());
        assertEquals(0,route.dugCount(),"use the existing doorway instead of reopening the wall");
        assertEquals(0,route.placedCount());
        assertTrue(route.path().stream().anyMatch(step->Math.abs(step.z())>=4));
        assertEquals(original,site.blocks);
    }

    @Test void aWalkwayAroundAGapIsPreferredToANewBridge() {
        var site = new Site();
        for (int x=4;x<=6;x++) for(int z=-1;z<=1;z++) site.blocks.remove(DigAStar.pack(x,0,z));
        var route = search(site,workAt(10,1,0),4000,32,TerrainEdits.EMPTY);
        assertNull(route.failure());
        assertEquals(0,route.dugCount());
        assertEquals(0,route.placedCount(),"the existing side walkway needs no scaffolding");
        assertTrue(route.path().stream().anyMatch(step->Math.abs(step.z())>=2));
    }

    @Test void anEnclosedSiteStillAllowsTheOriginalExcavationFallback() {
        var site = new Site();
        for (int z=-site.radius;z<=site.radius;z++) for (int y=1;y<=4;y++) site.block(4,y,z);
        var route = search(site,workAt(10,1,0),4000,32,TerrainEdits.EMPTY);
        assertNull(route.failure());
        assertTrue(route.dugCount()>0);
        assertTrue(route.dugCount()<=32);
    }

    @Test void aMissingWalkwayStillAllowsRealSupportAndUsesTheSameStock() {
        var site = new Site();
        for (int x=4;x<=6;x++) for (int z=-site.radius;z<=site.radius;z++)
            site.blocks.remove(DigAStar.pack(x,0,z));
        site.stock=3;
        var route = search(site,workAt(10,1,0),4000,0,TerrainEdits.EMPTY);
        assertNull(route.failure());
        assertEquals(3,route.placedCount());
        assertEquals(0,route.dugCount());
        assertEquals(3,site.stock,"planning does not spend real inventory");
    }

    @Test void aCombinedWorkSetCanChooseAnAccessibleMemberOverTheNearestBlockedMember() {
        var site = new Site();
        for (int z=-site.radius;z<=site.radius;z++) for (int y=1;y<=4;y++) site.block(2,y,z);
        var goal=NavigationGoal.anyOf(List.of(workAt(3,1,0),workAt(0,1,4)));
        var route=search(site,goal,4000,32,TerrainEdits.EMPTY);
        assertNull(route.failure());
        assertEquals(0,route.dugCount());
        assertEquals(0,route.placedCount());
        assertEquals(4,route.path().getLast().z());
    }

    @Test void aContinuationKeepsItsEarlierSupportsWithoutChargingThemAgain() {
        var site = new Site();
        for (int x=4;x<=6;x++) for(int z=-site.radius;z<=site.radius;z++)
            site.blocks.remove(DigAStar.pack(x,0,z));
        site.stock=0;
        var initial=TerrainEdits.EMPTY.after(List.of(),List.of(
                DigAStar.pack(4,0,0),DigAStar.pack(5,0,0),DigAStar.pack(6,0,0)));
        var route=search(site,workAt(10,1,0),4000,32,initial);
        assertNull(route.failure());
        assertEquals(0,route.dugCount());
        assertEquals(0,route.placedCount());
        assertEquals(3,initial.placed().size());
    }

    @Test void aFailedPreferenceProbeDoesNotResetTheExplicitSearchAccount() {
        var site = new Site();
        for (int z=-site.radius;z<=site.radius;z++) for(int y=1;y<=4;y++) site.block(4,y,z);
        var route=search(site,workAt(10,1,0),16,0,TerrainEdits.EMPTY);
        assertTrue(route.budgetReached());
        assertEquals("NODES",route.budgetKind());
        assertTrue(route.chargedNodes()<=16,"both phases spend the original account");
    }

    @Test void siteMovementCanOptInWithoutTurningEveryMiningRouteIntoAWalkingProbe() {
        var site=new Site();
        for (int z=-3;z<=3;z++) for(int y=1;y<=3;y++) site.block(4,y,z);
        var goal=NavigationGoal.position(10,1,0,0);
        var ordinary=new DigAStar(site,0,1,0,10,1,0,4000,32,goal).segments();
        while (!ordinary.advance(100)) { }
        assertEquals(0,ordinary.existingPathExpanded());
        assertTrue(ordinary.dugCount()>0 || ordinary.placedCount()>0);
        var construction=new DigAStar(site,0,1,0,10,1,0,4000,32,goal).segments().preferExistingPaths();
        while (!construction.advance(100)) { }
        assertNull(construction.failure());
        assertTrue(construction.usedExistingPath());
        assertEquals(0,construction.dugCount());
        assertEquals(0,construction.placedCount());
    }

    @Test void thePreferenceProbeCannotReadTerrainAfterAnExpiredTimeBudgetOrSlice() {
        var reads=new int[1];
        var sampler=new DigSampler() {
            @Override public boolean passable(int x,int y,int z) { reads[0]++; return true; }
            @Override public boolean support(int x,int y,int z) { reads[0]++; return true; }
            @Override public double digSeconds(int x,int y,int z) { reads[0]++; return 0; }
            @Override public boolean placeable(int x,int y,int z) { reads[0]++; return true; }
            @Override public double placeCost(int x,int y,int z) { reads[0]++; return 1; }
            @Override public boolean inBounds(int x,int y,int z) { reads[0]++; return true; }
            @Override public int maxPlaces() { reads[0]++; return 16; }
        };
        var expired=new DigAStar(sampler,0,1,0,10,1,0,4000,32,workAt(10,1,0)).segments();
        expired.totalBudget(0);
        assertTrue(expired.advance(100));
        assertEquals("TIME",expired.budgetKind());
        assertEquals(0,reads[0]);
        var deferred=new DigAStar(sampler,0,1,0,10,1,0,4000,32,workAt(10,1,0)).segments();
        assertFalse(deferred.advance(100,0));
        assertEquals(0,reads[0]);
    }

    @Test void aPreferenceFailureIsNotEvidenceThatTheBodyCannotLeaveWhenEditsAreAllowed() {
        var site=new Site();
        site.levelOnly=true;
        for (int x=-2;x<=12;x++) for(int z=-site.radius;z<=site.radius;z++)
            if (x!=0 || z!=0) for(int y=1;y<=4;y++) site.block(x,y,z);
        var trapped=search(site,workAt(10,1,0),4000,0,TerrainEdits.EMPTY);
        assertTrue(trapped.departureBlocked());
        assertTrue(trapped.existingPathExpanded()>0);
        var escaped=search(site,workAt(2,1,0),4000,32,TerrainEdits.EMPTY);
        assertNull(escaped.failure());
        assertFalse(escaped.departureBlocked());
        assertTrue(escaped.dugCount()>0);
    }
}
