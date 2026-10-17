package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.neko.mcbot.task.TickTask;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.Set;

/** Finite stock-only maintenance; priority, authority and failure never buy a fresh episode. */
public final class BodyMaintenance {
    public static final int CAP_TICKS = 240, MAX_ACTIONS = 4, MAX_EPISODES = 64;
    public record Frame(Object world, boolean allowed, boolean higherPriority, int food) { }
    public record Candidate(int source, String target, ItemStack expected) {
        public Candidate { expected = expected.copy(); }
        public boolean consume() { return target.equals("consume"); }
    }
    public interface Access {
        Candidate candidate(boolean food, Set<String> attemptedArmor);
        boolean acquire();
        boolean valid();
        TickTask consume(Candidate candidate);
        ServerTool.Result equip(Candidate candidate);
        void release();
    }
    private final Access access;
    private final Set<String> attemptedArmor = new HashSet<>();
    private TickTask work;
    private Candidate pending;
    private Object world;
    private boolean active, blocked, hungryHandled, feeding;
    private int elapsed, actions, episodes, fedTicks, clock, consumed;
    private String state = "idle";
    private ServerTool.Result last;

    public BodyMaintenance(Access access) { this.access = access; }
    public boolean active() { return active; }
    public void tick(Frame frame) {
        if (frame.food() >= 14) {
            if (fedTicks < 20) fedTicks++;
            if (fedTicks == 20 && !active) hungryHandled = false;
        } else fedTicks = 0;
        if (active) {
            elapsed++;
            if (!frame.allowed() || frame.world() != world || !access.valid()) { finish("authority_lost"); return; }
            if (frame.higherPriority()) { finish("interrupted"); return; }
            if (elapsed >= CAP_TICKS) { finish("limit"); return; }
        } else {
            if (blocked || !frame.allowed() || frame.higherPriority()) return;
            boolean hungry = frame.food() <= 6 && !hungryHandled;
            Candidate first = access.candidate(hungry, Set.copyOf(attemptedArmor));
            if (first == null) {
                if (hungry) {
                    hungryHandled = true;
                    state = "no_food";
                }
                return;
            }
            if (episodes >= MAX_EPISODES) { state = "episode_limit"; return; }
            feeding = hungry;
            if (hungry) hungryHandled = true;
            if (!first.consume()) attemptedArmor.add(first.target());
            pending = first;
            world = frame.world();
            active = true;
            elapsed = 1;
            actions = consumed = 0;
            last = null;
            if (!access.acquire()) { finish("unavailable"); return; }
            if (!active || blocked) {
                access.release();
                return;
            }
            episodes++;
            if (!access.valid()) { finish("authority_lost"); return; }
        }
        if (work != null) {
            var progress = work.tickSafe(null);
            if (progress instanceof TickTask.Progress.Done done) {
                last = done.result();
                if (last.data() != null && last.data().has("consumed_count"))
                    consumed += last.data().get("consumed_count").getAsInt();
                work.onFinish();
                work = null;
                if (!last.ok()) { finish("failed"); return; }
            }
            return;
        }
        if (feeding && frame.food() >= 14) feeding = false;
        if (actions >= MAX_ACTIONS) { finish("action_limit"); return; }
        Candidate next = pending == null ? access.candidate(feeding, Set.copyOf(attemptedArmor)) : pending;
        // DEBUG
        pending = null;
        if (next == null) { finish(feeding ? "no_food" : "local_done"); return; }
        if (!next.consume()) attemptedArmor.add(next.target());
        actions++;
        state = next.consume() ? "consuming" : "equipping";
        if (next.consume()) work = access.consume(next);
        else {
            last = access.equip(next);
            if (!last.ok()) finish("failed");
        }
    }
    public void equipmentBroken(String slot) { attemptedArmor.remove(slot); }
    public void cancel() { blocked = true; if (active) finish("cancelled"); else state = "cancelled"; }
    public void stopCurrent() { if (active) finish("task_ended"); }
    public void preempt() { if (active) finish("interrupted"); }
    public void newTask() { blocked = false; }
    private void finish(String reason) {
        active = false;
        state = reason;
        var original = work;
        work = null;
        pending = null;
        try {
            if (original != null) {
                try { original.onSuspend(); }
                finally {
                    last = original.interruptedResult(new ServerTool.Result(false,
                            "INTERRUPTED:补给停止，保留实际消费；原工作和外层目标未完成。", null));
                    if (last.data() != null && last.data().has("consumed_count"))
                        consumed += last.data().get("consumed_count").getAsInt();
                }
            }
        } finally {
            access.release();
        }
    }
    public JsonObject observation() {
        var data = new JsonObject();
        data.addProperty("active", active);
        data.addProperty("state", state);
        data.addProperty("elapsed_ticks", elapsed);
        data.addProperty("cap_ticks", CAP_TICKS);
        data.addProperty("actions", actions);
        data.addProperty("max_actions", MAX_ACTIONS);
        data.addProperty("episodes", episodes);
        data.addProperty("max_episodes", MAX_EPISODES);
        data.addProperty("consumed_count", consumed);
        data.addProperty("hunger_episode_closed", hungryHandled);
        data.addProperty("automatic_resume_blocked", blocked);
        data.addProperty("goal_satisfied", false);
        if (work != null) data.add("work", work.observation());
        if (last != null) {
            var result = new JsonObject();
            result.addProperty("ok", last.ok());
            result.addProperty("feedback", last.feedback());
            if (last.data() != null) result.add("data", last.data().deepCopy());
            data.add("last_result", result);
        }
        return data;
    }
}
