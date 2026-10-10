package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;

import java.util.Locale;

/** Owner directive policy, computed outside model arguments. Unknown tools fail closed. */
public final class TaskPolicy {
    private TaskPolicy() {
    }

    public static boolean readOnlyDirective(String text) {
        String value = text.toLowerCase(Locale.ROOT);
        return value.contains("只读") || value.contains("仅查看") || value.contains("仅扫描")
                || value.contains("不要移动") || value.contains("禁止移动")
                || value.contains("read-only") || value.contains("readonly")
                || value.contains("do not move") || value.contains("no world changes");
    }

    public static boolean observation(String name, JsonObject args) {
        return switch (name) {
            case "status", "inventory", "scan_area" -> true;
            case "craft" -> args.has("query") && args.get("query").isJsonPrimitive()
                    && args.get("query").getAsJsonPrimitive().isBoolean() && args.get("query").getAsBoolean();
            case "smelt" -> !args.has("action") || (args.get("action").isJsonPrimitive()
                    && args.get("action").getAsJsonPrimitive().isString()
                    && "query".equals(args.get("action").getAsString()));
            default -> false;
        };
    }

    public static boolean affirmative(String text) {
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "是", "是的", "同意", "确认", "允许", "可以", "批准", "yes", "approve", "confirm", "ok" -> true;
            default -> false;
        };
    }
}
