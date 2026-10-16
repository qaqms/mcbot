package com.neko.mcbot.agentcore.llm;

import java.time.Duration;
import java.util.function.LongSupplier;

/** A request deadline includes the body; network activity never extends it. */
final class StreamDeadline {
    private final LongSupplier clock;
    private final long started, total, idle;
    private long lastActivity;
    private long longestGap;

    StreamDeadline(Duration timeout, LongSupplier clock) {
        this.clock = clock;
        started = lastActivity = clock.getAsLong();
        total = timeout.toNanos();
        idle = Math.min(total, Duration.ofSeconds(90).toNanos());
    }

    synchronized void received() {
        long now = clock.getAsLong();
        longestGap = Math.max(longestGap, now - lastActivity);
        lastActivity = now;
    }

    synchronized long idleMillis() { return Math.max(0, (clock.getAsLong() - lastActivity) / 1_000_000); }
    synchronized long longestGapMillis() { return Math.max(longestGap / 1_000_000, idleMillis()); }

    synchronized LlmFailure.Kind expired() {
        long now = clock.getAsLong();
        if (now - started >= total) return LlmFailure.Kind.DEADLINE;
        if (now - lastActivity >= idle) return LlmFailure.Kind.STREAM_IDLE;
        return null;
    }
}
