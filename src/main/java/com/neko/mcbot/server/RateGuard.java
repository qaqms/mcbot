package com.neko.mcbot.server;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** 闸②：每个发送者一个令牌桶（默认 20/s，突发 60）。 */
public final class RateGuard {

    private final double refillPerSecond;
    private final double burst;
    private final LongSupplier clock;

    private static final class Bucket {
        double tokens;
        long lastNanos;
    }

    private final Map<Object, Bucket> buckets = new HashMap<>();

    public RateGuard(double refillPerSecond, double burst) {
        this(refillPerSecond, burst, System::nanoTime);
    }

    RateGuard(double refillPerSecond, double burst, LongSupplier clock) {
        this.refillPerSecond = refillPerSecond;
        this.burst = burst;
        this.clock = clock;
    }

    public boolean tryAcquire(Object key) {
        long now = clock.getAsLong();
        Bucket b = buckets.computeIfAbsent(key, k -> {
            Bucket fresh = new Bucket();
            fresh.tokens = burst;
            fresh.lastNanos = now;
            return fresh;
        });
        double gained = (now - b.lastNanos) / 1_000_000_000.0 * refillPerSecond;
        b.lastNanos = now;
        b.tokens = Math.min(burst, b.tokens + gained);
        if (b.tokens < 1) {
            return false;
        }
        b.tokens -= 1;
        return true;
    }
}
