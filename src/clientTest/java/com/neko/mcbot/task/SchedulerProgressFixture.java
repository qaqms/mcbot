package com.neko.mcbot.task;

import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.server.ServerTool;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Same scheduler tick/slot lifecycle, replacing only live body lookup. */
public final class SchedulerProgressFixture {
    private SchedulerProgressFixture() { }
    public static boolean submit(CompanionScheduler scheduler, UUID id, TickTask task,
                                 CompletableFuture<ServerTool.Result> result) {
        return scheduler.submit(id, null, task, result, 100);
    }
    public static void tick(CompanionScheduler scheduler) {
        scheduler.tick(new CompanionScheduler.Bodies() {
            @Override public CompanionPlayer get(UUID id) { return null; }
            @Override public boolean same(CompanionPlayer expected, CompanionPlayer fresh) { return true; }
        });
    }
}
