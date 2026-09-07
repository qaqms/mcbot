package com.neko.mcbot.agentcore.llm;

/**
 * 一个工具的对外契约。paramsJsonSchema 是内联的 JSON Schema 字符串，
 * provider 序列化时原样嵌入各家协议的工具声明位置。
 */
public record ToolSpec(String name, String description, String paramsJsonSchema) {

    public static ToolSpec of(String name, String description, String paramsJsonSchema) {
        return new ToolSpec(name, description, paramsJsonSchema);
    }
}
