package com.neko.mcbot.server;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 工具注册表（闸③的白名单）。v1 全内置；MCP/外部工具在 M6 之后另接。 */
public final class ToolRegistry {

    private final Map<String, ServerTool> tools = new LinkedHashMap<>();

    public void register(ServerTool tool) {
        tools.put(tool.name(), tool);
    }

    public ServerTool get(String name) {
        return tools.get(name);
    }

    public Set<String> names() {
        return tools.keySet();
    }
}
