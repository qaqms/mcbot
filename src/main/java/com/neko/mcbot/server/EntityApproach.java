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
        default boolean routeUseful(TickTask route) { return false; }
    }
    private final Access access;
    private final NavigationBudget budget;
    private TickTask route;
    private Vec3 heading;
    private boolean started;
    private int ticks, lastPlan = -10, outside;
    private int plans, retargets, rangeStops, arrivals, routeFailures, budgetStops;
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
            if (route != null && outside > 0) rangeStops++;
            access.stopInput();
            outside = 0;
            return null;
        }
        // A short boundary drift may delay movement, never permit an out-of-range strike.
        if (++outside < 3) { access.stopInput(); return null; }
        Vec3 position = access.targetPosition();
        boolean retarget = route != null && position.distanceToSqr(heading) >= 4
                && ticks - lastPlan >= 10 && !access.routeUseful(route);
        if (retarget) { retargets++; stop(); }
        if (route == null) {
            if (started && !budget.takeReplan() || budget.exhausted()) {
                budgetStops++;
                return fail("ACTION_LIMIT:动态目标接近的原搜索/换位账已到上限，未重开。");
            }
            route = access.route(budget);
            plans++;
            heading = position;
            lastPlan = ticks;
            started = true;
        }
        var progress = access.advance(route);
        // Movement and melee are independent: a target that entered native range is usable this tick.
        if (access.canStrike()) { rangeStops++; access.stopInput(); outside = 0; return null; }
        if (progress instanceof TickTask.Progress.Done done) {
            stop();
            if (!done.result().ok()) { routeFailures++; failure = done.result(); return failure; }
            arrivals++;
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
        data.addProperty("moving", route != null && outside > 0);
        data.addProperty("search_nodes", budget.nodes());
        data.addProperty("search_records", budget.records());
        data.addProperty("search_nanos", budget.nanos());
        data.addProperty("replans", budget.replans());
        data.addProperty("plans", plans);
        data.addProperty("retargets", retargets);
        data.addProperty("in_range_stops", rangeStops);
        data.addProperty("route_arrivals", arrivals);
        data.addProperty("route_failures", routeFailures);
        data.addProperty("budget_stops", budgetStops);
        data.addProperty("walk_only", true);
        if (route != null) data.add("route", route.observation());
        return data;
    }
}
