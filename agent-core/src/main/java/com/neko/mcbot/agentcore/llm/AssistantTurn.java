package com.neko.mcbot.agentcore.llm;

import java.util.List;

/** 流式解析完成的一轮模型输出。cachedTokens<0 = 后端未报缓存命中数。 */
public record AssistantTurn(String text, List<ToolCall> toolCalls,
                            long promptTokens, long completionTokens, long cachedTokens,
                            String finishReason) {

    public AssistantTurn {
        toolCalls = List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
