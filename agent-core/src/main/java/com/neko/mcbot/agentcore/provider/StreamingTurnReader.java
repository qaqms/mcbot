package com.neko.mcbot.agentcore.provider;

import com.google.gson.JsonObject;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.agentcore.llm.ToolSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 流式读取器（R2-A）：把 {@link TurnBuilder}（纯聚合）与"参数写完了没有"
 * （{@link ToolArgsScanner}）缝在一起，让传输层只做一件事——把行解析成 chunk 喂进来、
 * 把吐出来的增量与就绪信号交给宿主。
 *
 * <p>分工的理由：TurnBuilder 的职责是"喂碎片、末尾 build"，既有调用方与测试都依赖它；
 * 而"能否派发"必须逐碎片判定（早派发的前提）。把判定塞进 TurnBuilder，等于每动一次流式
 * 都要碰一个已被钉死的类。这里做适配层，TurnBuilder 只多两个只读访问器。
 *
 * <p><b>增量与就绪为什么分开取</b>：一片 arguments 增量可能同时包含"上一个调用闭合"
 * 与"下一个调用开头"两种信息量，甚至一片里闭合两个槽；反过来，某些片不携带任何 arguments
 * 却让上一个槽跨过临界点。所以 {@link #acceptChunk} 负责写、{@link #drainReady} 负责取，
 * 一个 chunk 走完这两步，顺序由调用方定（也方便测）。
 */
public final class StreamingTurnReader implements TurnSinkTarget {

    private final TurnBuilder builder = new TurnBuilder();
    private final Map<Integer, ToolArgsScanner> scanners = new LinkedHashMap<>();
    /** index → 该 index 的 schema required 名单（工具表顺序即 index 约定）。 */
    private final Map<Integer, List<String>> requiredByIndex = new HashMap<>();
    private final Set<Integer> alreadyReported = new HashSet<>();
    private final boolean accumulateText;

    /**
     * @param tools          本次请求带上的工具（按下发顺序，index 即模型回的 tool_call index）
     * @param accumulateText true = 仍把整轮文本攒在 {@link TurnBuilder} 里（默认路径：
     *                       AssistantTurn.text 要进对话历史）；false = 不攒，只算增量片段
     */
    public StreamingTurnReader(List<ToolSpec> tools, boolean accumulateText) {
        if (tools != null) {
            for (int i = 0; i < tools.size(); i++) {
                requiredByIndex.put(i, tools.get(i).requiredFields());
            }
        }
        this.accumulateText = accumulateText;
    }

    /** 传输层入口：一个已经解析好的 SSE data 载荷。 */
    public void acceptChunk(ChatProvider provider, JsonObject chunk) {
        provider.acceptChunk(chunk, this);
    }

    // ---- TurnSinkTarget：provider 写碎片的地方 ----

    @Override
    public void appendText(String delta) {
        builder.appendText(delta);
    }

    @Override
    public void toolCallDelta(int index, String id, String name, String argsDelta) {
        toolCallDeltaChecked(index, id, name, argsDelta);
    }

    /**
     * 写碎片并就地判定：这是**唯一**允许喂 arguments 的入口。
     * 返回非 null = 这个 index 的参数此刻刚好写完（早派发的触发器）。
     */
    @Override
    public ToolCall toolCallDeltaChecked(int index, String id, String name, String argsDelta) {
        builder.toolCallDelta(index, id, name, argsDelta);
        if (argsDelta == null || argsDelta.isEmpty()) {
            return null;
        }
        ToolArgsScanner sc = scanners.computeIfAbsent(index,
                i -> new ToolArgsScanner(requiredByIndex.get(i)));
        if (!sc.accept(argsDelta)) {
            return null;
        }
        return builder.snapshot(index);
    }

    @Override
    public void usage(long prompt, long completion, long cached) {
        builder.usage(prompt, completion, cached);
    }

    @Override
    public void finishReason(String reason) {
        builder.finishReason(reason);
    }

    // ---- 宿主侧读取 ----

    /** 喂一个 chunk。返回本片新增的文本增量（无则空串；accumulateText=false 时恒空）。 */
    public String accept(ChatProvider provider, JsonObject chunk) {
        int before = builder.textLength();
        acceptChunk(provider, chunk);
        return accumulateText ? builder.textTail(before) : "";
    }

    /** 本片之后新到达"可派发"的调用，按 index 升序；同一个 index 一生只出现一次。 */
    public List<ReadyCall> drainReady() {
        List<ReadyCall> out = new ArrayList<>();
        int slots = builder.callSlots();
        for (int idx = 0; idx < slots; idx++) {
            if (alreadyReported.contains(idx)) {
                continue;
            }
            ToolArgsScanner sc = scanners.get(idx);
            if (sc == null || !sc.isReady()) {
                continue;
            }
            ToolCall call = builder.snapshot(idx);
            if (call == null || call.name().isEmpty()) {
                continue; // name 还没到：协议上 name 先于 arguments，真碰上就当没就绪
            }
            alreadyReported.add(idx);
            out.add(new ReadyCall(idx, call, sc.normalizedArgs()));
        }
        return out;
    }

    public TurnBuilder builder() {
        return builder;
    }

    /** 一个已就绪的工具调用：index 用于与最终 turn 对齐，signature 用于打转判定。 */
    public record ReadyCall(int index, ToolCall call, String signature) {
    }
}
