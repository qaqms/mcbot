package com.neko.mcbot.agentcore.llm;

/**
 * 一个工具的对外契约。paramsJsonSchema 是内联的 JSON Schema 字符串，
 * provider 序列化时原样嵌入各家协议的工具声明位置。
 */
public record ToolSpec(String name, String description, String paramsJsonSchema) {

    public static ToolSpec of(String name, String description, String paramsJsonSchema) {
        return new ToolSpec(name, description, paramsJsonSchema);
    }

    /**
     * 本工具声明的顶层必填参数名（R2-A 早派发用：只靠括号配平会把
     * {@code {"x":1}} 这种半截参数当成写完）。
     * 没有 schema / 没有 required / schema 本身是坏的 ⇒ 空表（退化成"只看配平"）。
     */
    public java.util.List<String> requiredFields() {
        return com.neko.mcbot.agentcore.provider.ToolArgsScanner.requiredOf(paramsJsonSchema);
    }
}
