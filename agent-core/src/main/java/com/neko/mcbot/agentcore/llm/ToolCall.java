package com.neko.mcbot.agentcore.llm;

/** 模型发起的一次工具调用。argsJson 为模型产出的原始 JSON 字符串（执行侧再校验）。 */
public record ToolCall(String id, String name, String argsJson) {
}
