package com.neko.mcbot.task;

import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.ServerTool;

/**
 * 跨 tick 任务：由 CompanionScheduler 每服务器 tick 推进一次，直到产出 Done 或被超时中止。
 * 全部在主线程执行——可以直接摸世界。
 */
public abstract class TickTask {

    public sealed interface Progress {
        record Running() implements Progress {
        }

        record Done(ServerTool.Result result) implements Progress {
        }
    }

    private static final Progress RUNNING = new Progress.Running();

    protected int age;

    public final Progress tickSafe(CompanionPlayer companion) {
        age++;
        return tick(companion);
    }

    public abstract Progress tick(CompanionPlayer companion);

    /** Read facts and settle lifecycle only. Runs even during dormancy/reflex; must not emit input. */
    public Progress poll(CompanionPlayer companion, int elapsedTicks) { return running(); }

    /** State selected by poll; false releases input, not the task slot, lease or deadline. */
    public boolean wantsBody() { return true; }

    /** Actual counters only; elapsed ticks do not establish a completion percentage. */
    public com.google.gson.JsonObject observation() {
        return new com.google.gson.JsonObject();
    }

    public enum Activity {
        SEARCH("正在寻路"), MOVE("正在移动"), DIG("正在挖掘"),
        PLACE("正在放置"), WAIT("正在等待落稳"), SUSPENDED("正在避险");
        private final String label;
        Activity(String label) { this.label = label; }
        public String label() { return label; }
    }
    public Activity activity() { return null; }

    public static Progress running() {
        return RUNNING;
    }

    /** 超时中止时的收尾（默认回执教学式 TIMEOUT）。可覆写以清理状态（如中止挖掘进度）。 */
    public void onAbort() {
    }

    /** Release transient state without settling the call or resetting its counters. */
    public void onSuspend() { }

    public com.neko.mcbot.path.PathSpec movementScope() {
        return com.neko.mcbot.path.PathSpec.DEFAULT;
    }

    /** A task must opt into resumption with fresh action-specific validation. */
    public ServerTool.Result onResume(CompanionPlayer companion) {
        return new ServerTool.Result(false,
                "INTERRUPTED:身体曾被避险占用，当前动作不能安全续接；先核对实际状态。", null);
    }

    /** Preserve completed effects when cancellation, timeout or an exception interrupts a task. */
    public ServerTool.Result interruptedResult(ServerTool.Result reason) {
        return reason;
    }

    /** Release transient action state after an ordinary terminal outcome. */
    public void onFinish() {
        onAbort();
    }
}
