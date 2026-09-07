package com.neko.mcbot.agentcore;

import com.neko.mcbot.agentcore.llm.AssistantTurn;
import com.neko.mcbot.agentcore.llm.ChatEngine;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolSpec;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 测试替身：按脚本逐轮吐 turn，脚本耗尽后重复最后一轮（用于卡死护栏测试）。 */
public final class ScriptedEngine implements ChatEngine {

    private final Deque<AssistantTurn> script = new ArrayDeque<>();
    private AssistantTurn last = new AssistantTurn("…", List.of(), 0, 0, "stop");
    public int calls;

    public ScriptedEngine queue(AssistantTurn... turns) {
        script.addAll(List.of(turns));
        return this;
    }

    @Override
    public CompletableFuture<AssistantTurn> chat(String systemPrompt, List<Msg> convo, List<ToolSpec> tools) {
        calls++;
        AssistantTurn t = script.isEmpty() ? last : script.poll();
        last = t;
        return CompletableFuture.completedFuture(t);
    }
}
