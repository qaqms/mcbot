package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.building.BuildTarget;
import com.neko.mcbot.path.LevelDigSampler;
import com.neko.mcbot.path.WorkRelocation;

/** Read-only next-step advice. Lua dispatches every edit through the original authorized tools. */
public final class BuildRelocation {
    private BuildRelocation() { }

    public static void describe(CompanionPlayer body, JsonObject data, boolean requested) {
        var site = body.buildSite();
        if (!site.active()) return;
        var feet = StandingGeometry.feetCell(body);
        boolean obstructing = site.obstructs(body.getBoundingBox()), onTemporary = site.temporary(feet.below());
        data.addProperty("body_obstructs_target", obstructing);
        data.addProperty("body_on_temporary", onTemporary);
        if (!requested && !obstructing && !onTemporary) return;
        var access = new BuildSiteAccess(body);
        var terrain = new LevelDigSampler(body.level(), body, feet,
                LevelDigSampler.countPlaceables(body.getInventory()), null, site.scope());
        var geometry = StandingGeometry.forPlayer(body, site::inScope);
        var next = WorkRelocation.choose(terrain, feet.getX(), feet.getY(), feet.getZ(), site.scope().maxDigs(),
                step -> !site.obstructs(geometry.body(step.x(), step.feetY(), step.z())));
        JsonObject plan = onTemporary ? lower(body, feet.below(), access) : null;
        if (plan == null && next != null) {
            plan = BuildTarget.coordinates(new net.minecraft.core.BlockPos(next.x(), next.y(), next.z()));
            plan.addProperty("operation", "move");
            plan.addProperty("walk_only", next.dig().isEmpty() && next.place().isEmpty());
            plan.addProperty("planned_digs", next.dig().size());
            plan.addProperty("planned_places", next.place().size());
        } else if (plan == null) plan = lower(body, feet.below(), access);
        if (plan != null) data.add("relocation", plan);
    }

    private static JsonObject lower(CompanionPlayer body, net.minecraft.core.BlockPos support, BuildSiteAccess access) {
        var site = body.buildSite();
        boolean cleanup = site.temporary(support);
        if (!access.canLowerThrough(support) || !access.canMine(access.state(support))
                || site.miningGuard(support, site.target().revision(), cleanup, access) != null) return null;
        var plan = BuildTarget.coordinates(support);
        plan.addProperty("operation", "lower");
        plan.addProperty("expected_block", net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(access.state(support).getBlock()).toString());
        plan.addProperty("temporary_cleanup", cleanup);
        return plan;
    }
}
