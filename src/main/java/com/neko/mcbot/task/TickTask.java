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

    public static Progress running() {
        return RUNNING;
    }

    /** 超时中止时的收尾（默认回执教学式 TIMEOUT）。可覆写以清理状态（如中止挖掘进度）。 */
    public void onAbort() {
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
