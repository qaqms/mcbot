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
public final class TurnBuilder implements TurnSinkTarget {

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

    @Override
    public void appendText(String delta) {
        if (delta != null) {
            text.append(delta);
        }
    }

    @Override
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

    @Override
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

    @Override
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

    /**
     * 某个 index 的"此刻快照"——R2-A 早派发用：参数刚判定写完就把这一份交给执行器。
     * 返回 null = 这个 index 还没有名字（id/name 属于首个碎片，没到就没法派发）。
     *
     * <p>为什么交快照而不是引用：PartialCall 之后可能还会被追加（协议上不该，但中转站什么都干得出来）。
     * 早派发省的是挂起时间，不承担"参数后来又变了也照样执行"的语义。
     */
    public ToolCall snapshot(int index) {
        PartialCall p = calls.get(index);
        if (p == null || p.name.isEmpty()) {
            return null;
        }
        return new ToolCall(p.id, p.name, p.args.toString());
    }

    /** 已聚拢的 tool_call 槽位数量（含尚无名字的残缺槽），供流式收尾做一致性检查。 */
    public int callSlots() {
        return calls.size();
    }

    /** 已累积文本的长度：流式侧用它算"这一片 delta 是哪一段"。 */
    public int textLength() {
        return text.length();
    }

    /** 从 {@code from} 起的新增文本（流式增量切片）。 */
    public String textTail(int from) {
        int f = Math.max(0, Math.min(from, text.length()));
        return text.substring(f);
    }
}
