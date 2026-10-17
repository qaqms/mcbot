package com.neko.mcbot.path;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NavigationTrailTest {
    @org.junit.jupiter.api.Test void actualPassageKeepsFractionalHeadroomAndFloorUntilTaskChanges() {
        var trail=new NavigationTrail(); var world=new Object();
        trail.bind("task",world); trail.observe(1,90,2,90.5,true);
        for(int y=89;y<=92;y++) org.junit.jupiter.api.Assertions.assertTrue(
                trail.protectsPassage(new net.minecraft.core.BlockPos(1,y,2)));
        org.junit.jupiter.api.Assertions.assertTrue(trail.passageComplete());
        trail.observe(8,90,2,90.5,true);
        org.junit.jupiter.api.Assertions.assertFalse(trail.passageComplete());
        trail.bind("next",world);
        org.junit.jupiter.api.Assertions.assertFalse(trail.protectsPassage(new net.minecraft.core.BlockPos(1,90,2)));
    }
    @Test void onlyGroundedObservationsCreateRecordsAndNeverPromiseAReturn() {
        var trail = new NavigationTrail();
        var world = new Object();
        trail.bind("task", world);
        trail.observe(0, 70, 0, 70.5, false);
        assertEquals(0, trail.report().get("recorded_points").getAsInt());
        trail.observe(0, 70, 0, 70.5, true);
        trail.observe(0, 70, 0, 70.5, true);
        trail.observe(1, 70, 0, 70.5, true);
        assertEquals(2, trail.report().get("recorded_points").getAsInt());
        assertFalse(trail.report().get("return_guaranteed").getAsBoolean());
        assertFalse(trail.report().get("has_observation_gap").getAsBoolean());
        trail.observe(10, 70, 0, 70, true);
        assertTrue(trail.report().get("has_observation_gap").getAsBoolean());
    }

    @Test void sameTaskBindsRetainRecordsButNewTasksWorldsAndBodiesCannotInheritIds() {
        var trail = new NavigationTrail();
        var world = new Object();
        trail.bind("task1", world);
        trail.observe(0, 70, 0, 70, true);
        var id = trail.report().getAsJsonArray("waypoints").get(0).getAsJsonObject().get("id").getAsLong();
        trail.bind("task1", world);
        assertNotNull(trail.point(id));
        trail.bind("task2", world);
        assertNull(trail.point(id));
        trail.observe(0, 70, 0, 70, true);
        assertNull(trail.point(id), "IDs cannot be reused within the same body");
        trail.bind("task2", new Object());
        assertEquals(0, trail.report().get("recorded_points").getAsInt());
        assertNull(new NavigationTrail().point(id));
    }

    @Test void finiteStorageRetainsTheOriginalAnchorAndReportsOmissionsWithoutEviction() {
        var trail = new NavigationTrail();
        trail.bind("task", new Object());
        var scope = new PathSpec(false, false, true, 12, new BlockPos(0, 70, 0), 64);
        for (int i = 0; i < 270; i++) {
            trail.observe(i, 70, 0, 70, true, scope);
            trail.edited("dig", i, 70, 0);
        }
        var report = trail.report();
        assertEquals(256, report.get("recorded_points").getAsInt());
        assertEquals(256, report.get("recorded_edits").getAsInt());
        assertTrue(report.get("recording_truncated").getAsBoolean());
        assertEquals(16, report.getAsJsonArray("waypoints").size());
        var first = report.getAsJsonArray("waypoints").get(0).getAsJsonObject();
        assertEquals(0, first.get("x").getAsInt());
        assertTrue(first.get("underground").getAsBoolean());
        assertEquals(64, first.getAsJsonObject("bounds").get("radius").getAsInt());
        assertEquals(scope, trail.point(first.get("id").getAsLong()).scope());
        assertEquals(240, report.get("samples_omitted").getAsInt());
        assertEquals(240, report.get("edit_samples_omitted").getAsInt());
    }

    @Test void selectedWorkAndItsSupportSurviveOtherMovesWithoutEvictingProtection() {
        var targets = new WorkTargets();
        targets.bind("task");
        var target = new BlockPos(0, 70, 0);
        assertTrue(targets.add(target));
        targets.bind("task");
        assertTrue(targets.protects(target));
        assertTrue(targets.protects(target.below()));
        assertFalse(targets.protects(target.east()));
        for (int i = 1; i < WorkTargets.LIMIT; i++) assertTrue(targets.add(target.offset(i, 0, 0)));
        assertFalse(targets.add(target.offset(100, 0, 0)));
        assertTrue(targets.protects(target));
        assertTrue(targets.add(target));
        targets.bind("next task");
        assertFalse(targets.protects(target));
    }
}
