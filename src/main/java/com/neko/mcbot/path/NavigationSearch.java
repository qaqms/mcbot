package com.neko.mcbot.path;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Worker ownership ends before a completed search is published to the world thread. */
final class NavigationSearch {
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), work -> {
                var thread = new Thread(work, "mcbot-navigation-" + IDS.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });
    static { WORKERS.allowCoreThreadTimeOut(true); }
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CompletableFuture<DigAStar> result = new CompletableFuture<>();
    private final Runnable work;

    private NavigationSearch(DigAStar search) {
        work = () -> {
            try {
                while (!cancelled.get() && !search.advance(128, 20_000_000L)) { }
                if (!cancelled.get()) result.complete(search);
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        };
    }
    static NavigationSearch submit(DigAStar search) {
        var pending = new NavigationSearch(search);
        WORKERS.execute(pending.work);
        return pending;
    }
    boolean done() { return result.isDone(); }
    DigAStar take() { return result.join(); }
    void cancel() {
        cancelled.set(true);
        WORKERS.remove(work);
        result.cancel(false);
    }
}
