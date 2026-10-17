package com.neko.mcbot.body;

import com.google.gson.JsonObject;

/** Environment ownership is bounded separately from the work it interrupts. */
final class EnvironmentRecovery {
    interface Access extends WaterRecovery.Access {
        int air();
        int maxAir();
        String hazard();
        boolean acquire();
        boolean valid();
        void release();
    }

    private static final int CAP_TICKS = 600, CALM_TICKS = 20, EPISODES = 64;
    private WaterRecovery water = new WaterRecovery();
    private Access owner;
    private Object world;
    private boolean active, blocked, ended;
    private int elapsed, calm, episodes, lowestAir;
    private String state = "idle", reason = "";

    boolean active() { return active; }
    boolean holdsWork() { return active || ended; }
    String state() { return state; }
    String waterState() { return water.state(); }

    static String hazard(boolean lava, boolean fire, boolean wall, boolean powder, boolean belowWorld) {
        if (belowWorld) return "below_world";
        if (lava) return "lava";
        if (wall) return "suffocating";
        if (fire) return "on_fire";
        return powder ? "powder_snow" : "";
    }

    static com.neko.mcbot.server.ServerTool.Result workFailure(String hazard, boolean submerged, boolean active) {
        if (hazard.isEmpty() && !submerged && !active) return null;
        var data = new JsonObject();
        data.addProperty("environment_state", hazard.isEmpty() ? submerged ? "submerged" : "recovering" : hazard);
        data.addProperty("environment_work_safe", false);
        return new com.neko.mcbot.server.ServerTool.Result(false,
                "ENVIRONMENT_UNSAFE:身体环境尚不安全，普通工作已停；先status核对空气、危险和避险结果。"
                        + "局部避险不完成原目标，失败不自动续开范围或预算。", data);
    }

    MovementInput.Frame tick(Access body) {
        if (world != body.worldIdentity()) {
            if (world != null) stop("world_changed");
            world = body.worldIdentity();
        }
        boolean safe = !body.submerged() && body.hazard().isEmpty() && body.air() >= body.maxAir();
        if (active) {
            elapsed = Math.min(CAP_TICKS, elapsed + 1);
            lowestAir = Math.min(lowestAir, body.air());
            if (!body.allowed() || !body.valid()) stop("authority_lost");
            else if (elapsed >= CAP_TICKS) stop("limit");
        }
        if (safe) {
            if (active || ended) {
                if (++calm >= CALM_TICKS) {
                    if (active) stop("local_clear");
                    ended = false;
                } else if (active) {
                    state = "recovering";
                    return MovementInput.Frame.IDLE;
                }
            }
            return null;
        }
        calm = 0;
        if (!body.allowed()) {
            if (!active && !ended) state = "suppressed";
            return null;
        }
        if (blocked || ended) return null;
        if (!active) {
            // Low air alone on dry ground needs native refilling, not a new movement episode.
            if (!body.submerged() && body.hazard().isEmpty()) return null;
            if (episodes >= EPISODES) { state = "episode_limit"; return null; }
            owner = body;
            active = true;
            if (!body.acquire()) { stop("unavailable"); return null; }
            if (!active || blocked) {
                // Acquisition may finish installing a lease after a reentrant cancellation.
                body.release();
                return null;
            }
            episodes++;
            elapsed = 1;
            lowestAir = body.air();
            water = new WaterRecovery();
            // Ownership cleanup may synchronously cancel the work whose authority was borrowed.
            if (!body.allowed() || !body.valid()) { stop("authority_lost"); return null; }
        }
        if (!body.hazard().isEmpty()) {
            state = "environment_stop";
            reason = body.hazard();
            return MovementInput.Frame.IDLE;
        }
        if (!body.submerged()) {
            state = "recovering";
            return MovementInput.Frame.IDLE;
        }
        var input = water.tick(body);
        state = water.state();
        if (state.equals("limit") || state.equals("scope_limit") || state.equals("search_limit"))
            stop(state);
        return input;
    }

    void cancel() {
        blocked = true;
        if (active) stop("cancelled");
        else state = reason = "cancelled";
    }

    void stopCurrent() {
        if (active) stop("task_ended");
    }

    void newTask() { blocked = false; }

    void unavailable() {
        if (active) stop("unknown");
    }

    private void stop(String why) {
        state = reason = why;
        ended = true;
        active = false;
        var original = owner;
        owner = null;
        if (original != null) original.release();
    }

    JsonObject observation() {
        var data = new JsonObject();
        data.addProperty("active", active);
        data.addProperty("state", state);
        data.addProperty("last_reason", reason);
        data.addProperty("elapsed_ticks", elapsed);
        data.addProperty("cap_ticks", CAP_TICKS);
        data.addProperty("calm_ticks", calm);
        data.addProperty("episodes", episodes);
        data.addProperty("max_episodes", EPISODES);
        data.addProperty("episode_known", episodes > 0);
        if (episodes > 0) data.addProperty("lowest_air_supply", lowestAir);
        data.addProperty("automatic_resume_blocked", blocked || ended);
        data.addProperty("goal_satisfied", false);
        data.add("water", water.observation());
        return data;
    }
}
