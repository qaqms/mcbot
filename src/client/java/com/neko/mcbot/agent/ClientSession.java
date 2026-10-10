package com.neko.mcbot.agent;

import com.neko.mcbot.common.Envelope;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Owns the runner and bridge lifetime; entry points run on the client thread. */
public final class ClientSession {
    private final Supplier<AgentRunner> createRunner;
    private final Runnable startBridge;
    private final Runnable stopBridge;
    private volatile AgentRunner runner;

    public ClientSession(Supplier<AgentRunner> createRunner, Runnable startBridge, Runnable stopBridge) {
        this.createRunner = createRunner;
        this.startBridge = startBridge;
        this.stopBridge = stopBridge;
    }

    public AgentRunner runner() {
        return runner;
    }

    public void join() {
        if (runner != null) disconnect();
        runner = createRunner.get();
        runner.start();
        runner.sendLifecycle("companion_status", "");
        startBridge.run();
    }

    public void disconnect() {
        AgentRunner old = runner;
        if (old == null) return;
        // Keep ownership until close has initiated cancellation and emitted terminal events.
        old.close();
        stopBridge.run();
        runner = null;
    }

    public void receive(Envelope envelope, Executor clientThread) {
        AgentRunner target = runner;
        if (envelope == null || target == null) return;
        clientThread.execute(() -> {
            if (runner == target) target.handleS2c(envelope);
        });
    }
}
