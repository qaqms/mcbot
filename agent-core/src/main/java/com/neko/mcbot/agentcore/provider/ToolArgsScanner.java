package com.neko.mcbot.agentcore.provider;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * tool_call 参数的"何时算写完"判定器（R2-A 早派发的核心）。
 *
 * <p><b>为什么不能只看括号配平：</b>模型把 arguments 一片一片吐过来，第一片常常就是一个
 * <b>合法但残缺</b>的 JSON——例如 {@code {"x":1}} 先落地、后面才补 {@code ,"y":2}}。
 * 只看"深度归 0 且末字符是 '}'"就会把半截参数当成品派发，工具会拿着错坐标真去挖方块。
 * 所以判定两道门：①顶层括号配平；②schema 声明的 required 字段在顶层**全都出现过**。
 *
 * <p><b>为什么不做完整 JSON 解析：</b>括号可能出现在字符串值里（{@code {"n":"}"}）、
 * 也可能被转义（{@code {"n":"\"}"}）。所以必须自己维护"在不在字符串里 / 这一格是否被转义"
 * 两个状态，不能用 {@code indexOf('}')} 之类的近似。解析不便宜而这一步每个碎片都要跑，
 * 因此策略是"先廉价配平+收键，再让 Gson 真解一次"。
 *
 * <p><b>两个独立对象形态：</b>个别端点/中转站会把同一个 index 的 arguments 吐成
 * 两个独立对象（先 {@code {"x":1}}、再 {@code {"y":2}}）而不是续写。续写拼起来是合法 JSON，
 * 独立对象拼起来是非法 JSON——坚持累积就会永远解析失败、永远不就绪。所以规则定为：
 * 顶层对象在场、又出现新的顶层 '{' ⇒ 丢弃旧的、以新的为准。真协议不这么发，
 * 真端点什么都干得出来；"取最后看到的完整对象"比"永远不派发"安全。
 */
public final class ToolArgsScanner {

    /** required 字段（顶层；来自 paramsJsonSchema 的 required 数组）。 */
    private final List<String> required;

    private final StringBuilder raw = new StringBuilder();
    private final Set<String> seenKeys = new LinkedHashSet<>();

    private int depth;
    private boolean inString;
    private boolean escaped;
    /** 当前位置是不是"顶层键位"：'{' / ',' 之后为真，':' 之后为假。 */
    private boolean expectingKey = true;
    /** 正在读的顶层键原文（含转义），到冒号才结算。 */
    private StringBuilder currentKey;

    private boolean ready;
    private boolean dirty = true;
    private boolean cached;

    public ToolArgsScanner(List<String> required) {
        this.required = required == null ? List.of() : List.copyOf(required);
    }

    /** 从会话声明的 schema 建：没有 required / 坏 schema ⇒ 空表（退化成"只看配平"）。 */
    public static ToolArgsScanner forSchema(String paramsJsonSchema) {
        return new ToolArgsScanner(requiredOf(paramsJsonSchema));
    }

    /**
     * 追加一片增量并返回"此刻是否已可派发"。单调：返回过 true 之后恒 true。
     */
    public boolean accept(String delta) {
        if (delta != null && !delta.isEmpty()) {
            int deltaLen = delta.length();
            raw.append(delta);
            resetIfNewTopLevelObject(deltaLen);
            int freshFrom = Math.max(0, raw.length() - deltaLen);
            scan(raw, freshFrom);
            dirty = true;
        }
        if (ready) {
            return true;
        }
        if (!dirty) {
            return cached;
        }
        dirty = false;
        cached = evaluate();
        ready = cached;
        return ready;
    }

    /** 判定结果：单调；一旦 true 永不回退。 */
    public boolean isReady() {
        return ready;
    }

    /** 供打点/诊断：当前累积的原始 arguments 文本。 */
    public String rawArgs() {
        return raw.toString();
    }

    /** 空白归一化后的签名：{@code {"a":1}} 与 {@code {"a": 1}} 视为同一调用。 */
    public String normalizedArgs() {
        return normalize(raw.toString());
    }

    /** 诊断用：内部状态快照（不进生产逻辑；排"为什么没就绪"时很有用）。 */
    public String debugState() {
        return "depth=" + depth + " inString=" + inString + " expectingKey=" + expectingKey
                + " seen=" + seenKeys + " raw=" + raw + " ready=" + ready;
    }

    // ---- 新对象重置 ----

    /**
     * 顶层对象已闭合、新片段又以 '{' 开头 ⇒ 这是"另一个对象"，丢弃旧的重新开始。
     * 只看新片段里的第一个有效字符，避免把续写里的 {@code "{"}（字符串内容）误判。
     */
    private void resetIfNewTopLevelObject(int deltaLen) {
        if (depth != 0) {
            return; // 还在对象内部：这是续写
        }
        int from = raw.length() - deltaLen;
        for (int i = from; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (c == '{' && i > 0) {
                // 只留新对象：留着旧的拼起来必然非法
                raw.delete(0, i);
                seenKeys.clear();
                depth = 0;
                inString = false;
                escaped = false;
                expectingKey = true;
                currentKey = null;
            }
            return;
        }
    }

    // ---- 逐字符状态机 ----

    private void scan(CharSequence text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (currentKey != null) {
                    currentKey.append(c);
                    if (escaped) {
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        inString = false;
                        currentKey.setLength(currentKey.length() - 1); // 去掉收尾引号
                    }
                } else if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    inString = true;
                    escaped = false;
                    // 只有顶层键位上的字符串才是键；所有值字符串一概不记。
                    if (depth == 1 && expectingKey) {
                        currentKey = new StringBuilder();
                    }
                }
                case '{', '[' -> {
                    depth++;
                    // 根对象刚打开（0→1）时，下一个有效字符就是键；
                    // 进入更深一层则顶层这一层不再等键（否则内层的键会被误收）。
                    expectingKey = depth == 1;
                    currentKey = null;
                }
                case '}', ']' -> {
                    depth--;
                    expectingKey = false;
                    currentKey = null;
                }
                case ':' -> {
                    if (depth == 1 && currentKey != null) {
                        seenKeys.add(unquoteKey(currentKey.toString()));
                    }
                    currentKey = null;
                    if (depth == 1) {
                        expectingKey = false;
                    }
                }
                case ',' -> {
                    currentKey = null;
                    if (depth == 1) {
                        expectingKey = true;
                    }
                }
                default -> {
                    // 其余字符（含数字/true/null）不改变键位状态
                }
            }
        }
    }

    /** currentKey 累积的是转义后的原文（含反斜杠），解回真实键名才能与 required 比。 */
    private static String unquoteKey(String esc) {
        if (esc.indexOf('\\') < 0) {
            return esc;
        }
        try {
            JsonElement el = JsonParser.parseString("\"" + esc + "\"");
            return el.isJsonPrimitive() ? el.getAsString() : esc;
        } catch (RuntimeException badEscape) {
            return esc;
        }
    }

    // ---- 判定 ----

    private boolean evaluate() {
        if (depth != 0 || inString) {
            return false;
        }
        String text = raw.toString().trim();
        if (text.isEmpty() || text.charAt(text.length() - 1) != '}') {
            return false;
        }
        // 廉价扫描只是近似，只有解析器能判"是不是合法 JSON"和"required 齐没齐"
        try {
            JsonElement el = JsonParser.parseString(text);
            if (!el.isJsonObject()) {
                return false;
            }
            JsonObject obj = el.getAsJsonObject();
            for (String k : required) {
                if (!obj.has(k) || obj.get(k).isJsonNull()) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException notYetValidJson) {
            return false;
        }
    }

    // ---- 静态工具 ----

    /**
     * 归一化一段 JSON 文本里的"无意义空白"（字符串内的空白与转义原样保留）。
     * 打转判定用：模型把同一个调用换一下空格不该算"换了个做法"。
     */
    public static String normalize(String json) {
        if (json == null || json.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(json.length());
        boolean in = false;
        boolean esc = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (in) {
                sb.append(c);
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    in = false;
                }
                continue;
            }
            if (c == '"') {
                in = true;
                sb.append(c);
            } else if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 从 ToolSpec 的 paramsJsonSchema 取顶层 required 名单；没有/非法 ⇒ 空表。 */
    public static List<String> requiredOf(String paramsJsonSchema) {
        if (paramsJsonSchema == null || paramsJsonSchema.isBlank()) {
            return List.of();
        }
        try {
            JsonElement el = JsonParser.parseString(paramsJsonSchema);
            if (!el.isJsonObject()) {
                return List.of();
            }
            JsonElement req = el.getAsJsonObject().get("required");
            if (req == null || !req.isJsonArray()) {
                return List.of();
            }
            java.util.List<String> out = new java.util.ArrayList<>();
            for (JsonElement e : req.getAsJsonArray()) {
                if (e.isJsonPrimitive()) {
                    out.add(e.getAsString());
                }
            }
            return out;
        } catch (RuntimeException badSchema) {
            return List.of();
        }
    }
}
