package com.neko.mcbot.agentcore.llm;

import java.util.List;

/** 流式解析完成的一轮模型输出。 */
public record AssistantTurn(String text, List<ToolCall> toolCalls,
                            long promptTokens, long completionTokens, String finishReason) {

    public AssistantTurn {
        toolCalls = List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
