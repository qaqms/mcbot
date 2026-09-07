package com.neko.mcbot.agentcore.llm;

import java.util.List;

/** 对话消息（provider 无关的内部表示，序列化成各家线格式由 ChatProvider 负责）。 */
public sealed interface Msg {

    record User(String text) implements Msg {
    }

    /** 模型一轮输出：可能带文本，可能带 tool 调用，可能两者都有。 */
    record Assistant(String text, List<ToolCall> toolCalls) implements Msg {
        public Assistant {
            toolCalls = List.copyOf(toolCalls);
        }
    }

    /** 工具执行回执（ok=false 时 content 是给模型看的教学式失败说明）。 */
    record Tool(String callId, String name, String content, boolean ok) implements Msg {
    }

    /** 循环护栏注入的系统提示（各家都按 user/extra 角色映射，简单起见走 user）。 */
    record Nudge(String text) implements Msg {
    }
}
