package com.neko.mcbot.body;

import com.google.gson.JsonObject;
import com.neko.mcbot.common.WireSize;

import java.util.UUID;

/** Death retires a body once. Recovery never inherits an action, input or task authority. */
public final class BodyLifecycle {
    public static final int DELAY_TICKS = 40, SEARCH_INTERVAL = 20, MAX_ATTEMPTS = 8;

    public interface Access {
        boolean current();
        void announce();
        void revoke();
        void stop();
        boolean ownerReady();
        boolean rebuild();
    }

    private final UUID id;
    private UUID deathId;
    private String state = "alive", cause = "";
    private int elapsed, attempts;
    private boolean nativeComplete, cleanupComplete;

    public BodyLifecycle(UUID id) { this.id = id; this.deathId = id; }
    public boolean retired() { return !state.equals("alive"); }
    public boolean dead() { return state.equals("dead"); }
    public boolean rebuilding() { return state.equals("rebuilding") && nativeComplete && cleanupComplete; }
    public String state() { return state; }
    public String cause() { return cause; }

    public void die(Access access, String reason, Runnable nativeDeath) {
        if (retired()) return;
        state = "dead";
        cause = WireSize.truncateToBytes(reason == null ? "unknown" : reason, 512);
        boolean notified = false, revoked = false;
        // The notice must precede any terminal tool receipt, which could wake the old model.
        try {
            access.announce();
            notified = true;
        } finally {
            try {
                access.revoke();
                revoked = true;
            } finally {
                try {
                    access.stop();
                    cleanupComplete = notified && revoked;
                } finally {
                    try {
                        nativeDeath.run();
                        nativeComplete = true;
                    } finally {
                        if (!cleanupComplete || !nativeComplete) state = "recovery_failed";
                    }
                }
            }
        }
    }

    /** An unverified zero-health save is never treated as proof that native drops were settled. */
    public void unavailable(Access access) {
        unavailable(access, "unverified_death");
    }

    public void unavailable(Access access, String reason) {
        if (retired()) return;
        try { die(access, reason, () -> { }); }
        finally { nativeComplete = false; state = "recovery_failed"; }
    }

    public void tick(Access access) {
        if (!dead() || !nativeComplete || !cleanupComplete) return;
        if (!access.current()) { state = "retired"; return; }
        if (elapsed < DELAY_TICKS) { elapsed++; return; }
        if (!access.ownerReady()) return;
        if (++elapsed % SEARCH_INTERVAL != 1) return;
        if (++attempts > MAX_ATTEMPTS) { state = "recovery_blocked"; access.announce(); return; }
        // An exception after removing/joining a body is not safe to retry.
        state = "rebuilding";
        try {
            if (access.rebuild()) state = "retired";
            else state = attempts == MAX_ATTEMPTS ? "recovery_blocked" : "dead";
        } catch (RuntimeException | Error failure) {
            state = "recovery_failed";
            throw failure;
        }
        if (state.equals("recovery_blocked")) access.announce();
    }

    public JsonObject observation() {
        var data = new JsonObject();
        data.addProperty("body_id", id.toString());
        if (retired()) data.addProperty("death_body_id", deathId.toString());
        data.addProperty("state", state);
        data.addProperty("death_cause", cause);
        data.addProperty("native_death_complete", nativeComplete);
        data.addProperty("cleanup_complete", cleanupComplete);
        data.addProperty("recovery_elapsed_ticks", elapsed);
        data.addProperty("recovery_attempts", attempts);
        data.addProperty("goal_satisfied", false);
        data.addProperty("automatic_resume", false);
        return data;
    }

    public void save(net.minecraft.world.level.storage.ValueOutput output) {
        output.putInt("version", 1);
        output.putString("state", state);
        output.putString("death_body_id", deathId.toString());
        output.putString("cause", cause);
        output.putBoolean("native_complete", nativeComplete);
        output.putBoolean("cleanup_complete", cleanupComplete);
        output.putInt("elapsed", elapsed);
        output.putInt("attempts", attempts);
    }

    /** Only a completed native death can recover; an interrupted replacement fails closed. */
    public void load(net.minecraft.world.level.storage.ValueInput input) {
        String saved = input.getStringOr("state", "recovery_failed");
        if (input.getIntOr("version", 0) == 1 && saved.equals("alive")) return;
        state = "recovery_failed";
        cause = WireSize.truncateToBytes(input.getStringOr("cause", "unverified_death"), 512);
        nativeComplete = input.getBooleanOr("native_complete", false);
        cleanupComplete = input.getBooleanOr("cleanup_complete", false);
        int savedElapsed = input.getIntOr("elapsed", -1);
        int savedAttempts = input.getIntOr("attempts", -1);
        if (savedElapsed < 0 || savedElapsed > DELAY_TICKS + MAX_ATTEMPTS * SEARCH_INTERVAL
                || savedAttempts < 0 || savedAttempts > MAX_ATTEMPTS) return;
        elapsed = savedElapsed;
        attempts = savedAttempts;
        try { deathId = UUID.fromString(input.getStringOr("death_body_id", "")); }
        catch (IllegalArgumentException invalid) { return; }
        if (input.getIntOr("version", 0) != 1) return;
        if (saved.equals("recovery_blocked") || attempts == MAX_ATTEMPTS) state = "recovery_blocked";
        else if (saved.equals("dead") && nativeComplete && cleanupComplete) state = "dead";
    }
}
