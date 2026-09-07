package com.neko.mcbot.agentcore.loop;

import java.util.concurrent.CompletableFuture;

/** 工具执行抽象：harness 里是本地实现；mod 里是"payload 发往服务器、回执异步回来"。 */
public interface ToolExecutor {

    record ToolOutcome(boolean ok, String feedback) {
    }

    CompletableFuture<ToolOutcome> execute(String name, String argsJson);
}
