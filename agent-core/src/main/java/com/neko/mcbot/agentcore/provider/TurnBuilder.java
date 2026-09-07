package com.neko.mcbot.agentcore.provider;

import com.neko.mcbot.agentcore.llm.AssistantTurn;
import com.neko.mcbot.agentcore.llm.ToolCall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流式增量的收集器：文本碎片拼接；tool_call 碎片按 index 聚合
 * （id/name 只在首个碎片出现，arguments 跨碎片增长）。
 */
public final class TurnBuilder {

    private final StringBuilder text = new StringBuilder();
    private final Map<Integer, PartialCall> calls = new LinkedHashMap<>();
    private long promptTokens;
    private long completionTokens;
    private long cachedTokens = -1; // -1 = 后端没报这个字段（区别于报了 0）
    private String finishReason;

    private static final class PartialCall {
        String id = "";
        String name = "";
        final StringBuilder args = new StringBuilder();
    }

    public void appendText(String delta) {
        if (delta != null) {
            text.append(delta);
        }
    }

    public void toolCallDelta(int index, String id, String name, String argsDelta) {
        PartialCall p = calls.computeIfAbsent(index, k -> new PartialCall());
        if (id != null && !id.isEmpty()) {
            p.id = id;
        }
        if (name != null && !name.isEmpty()) {
            p.name = name;
        }
        if (argsDelta != null) {
            p.args.append(argsDelta);
        }
    }

    public void usage(long prompt, long completion) {
        usage(prompt, completion, -1);
    }

    public void usage(long prompt, long completion, long cached) {
        if (prompt > 0) {
            this.promptTokens = prompt;
        }
        if (completion > 0) {
            this.completionTokens = completion;
        }
        if (cached >= 0) {
            this.cachedTokens = cached;
        }
    }

    public void finishReason(String reason) {
        if (reason != null) {
            this.finishReason = reason;
        }
    }

    public AssistantTurn build() {
        List<ToolCall> list = new ArrayList<>();
        for (PartialCall p : calls.values()) {
            if (!p.name.isEmpty()) {
                list.add(new ToolCall(p.id, p.name, p.args.toString()));
            }
        }
        return new AssistantTurn(text.toString(), list, promptTokens, completionTokens,
                cachedTokens, finishReason);
    }
}
