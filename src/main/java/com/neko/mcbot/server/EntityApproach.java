package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.neko.mcbot.path.NavigationBudget;
import com.neko.mcbot.task.TickTask;
import net.minecraft.world.phys.Vec3;

/** Finite moving-target scheduling; route planning and motion stay in the existing PathTask. */
public final class EntityApproach {
    public interface Access {
        Vec3 targetPosition();
        boolean canStrike();
        TickTask route(NavigationBudget budget);
        TickTask.Progress advance(TickTask route);
        void stopInput();
    }
    private final Access access;
    private final NavigationBudget budget;
    private TickTask route;
    private Vec3 heading;
    private boolean started;
    private int ticks, lastPlan = -10, outside;
    private ServerTool.Result failure;

    public EntityApproach(Access access) { this(access, new NavigationBudget()); }
    public EntityApproach(Access access, NavigationBudget budget) {
        this.access = access;
        this.budget = java.util.Objects.requireNonNull(budget);
    }

    public ServerTool.Result tick() {
        ticks++;
        if (failure != null) return failure;
        if (access.canStrike()) {
            stop();
            outside = 0;
            return null;
        }
        // A short boundary drift may delay movement, never permit an out-of-range strike.
        if (++outside < 3) { access.stopInput(); return null; }
        Vec3 position = access.targetPosition();
        boolean retarget = route != null && position.distanceToSqr(heading) >= 4 && ticks - lastPlan >= 10;
        if (retarget) stop();
        if (route == null) {
            if (started && !budget.takeReplan() || budget.exhausted())
                return fail("ACTION_LIMIT:动态目标接近的原搜索/换位账已到上限，未重开。");
            route = access.route(budget);
            heading = position;
            lastPlan = ticks;
            started = true;
        }
        var progress = access.advance(route);
        // Movement and melee are independent: a target that entered native range is usable this tick.
        if (access.canStrike()) { stop(); outside = 0; return null; }
        if (progress instanceof TickTask.Progress.Done done) {
            stop();
            if (!done.result().ok()) { failure = done.result(); return failure; }
            // Reaching an old target snapshot is only a route result, not an attack or kill.
        }
        return null;
    }

    private ServerTool.Result fail(String feedback) {
        stop();
        failure = new ServerTool.Result(false, feedback, null);
        return failure;
    }
    public void stop() {
        if (route != null) route.onAbort();
        route = null;
        access.stopInput();
    }
    public JsonObject observation() {
        var data = new JsonObject();
        data.addProperty("moving", route != null);
        data.addProperty("search_nodes", budget.nodes());
        data.addProperty("search_records", budget.records());
        data.addProperty("search_nanos", budget.nanos());
        data.addProperty("replans", budget.replans());
        data.addProperty("walk_only", true);
        if (route != null) data.add("route", route.observation());
        return data;
    }
}
