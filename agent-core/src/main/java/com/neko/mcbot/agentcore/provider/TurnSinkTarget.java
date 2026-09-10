package com.neko.mcbot.agentcore.provider;

import com.neko.mcbot.agentcore.llm.ToolCall;

/**
 * provider 写流式碎片的目标（R2-A）：聚合器的最窄契约。
 *
 * <p>为什么要有这层抽象：{@code acceptChunk} 只需要"写进去"的能力；
 * "这个 index 的参数写完没有"是读取器的事，provider 不该知道。让 provider 直接
 * 依赖读取器也能编译，但那样协议形状的知识和判定时机的知识就粘在一起了——
 * 而正是为了把这两件事分开，{@link StreamingTurnReader} 才存在。
 */
public interface TurnSinkTarget {

    /** 文本增量。 */
    void appendText(String delta);

    /** tool_call 碎片：id/name 只在首个碎片出现；arguments 跨碎片增长。 */
    void toolCallDelta(int index, String id, String name, String argsDelta);

    void usage(long prompt, long completion, long cached);

    void finishReason(String reason);

    /**
     * 带"是否刚刚写完"回答的碎片写入（R2-A）。默认转调
     * {@link #toolCallDelta}，返回 null（= 不关心就绪）；
     * {@link StreamingTurnReader} 覆盖它：写进去的同时把判定结果带出来。
     *
     * <p><b>为什么必须由 provider 调这一个而不是让外层自己喂</b>：外层若在调用
     * {@code acceptChunk} 之外自己再喂一遍碎片，同一个 arguments 会被累积两次
     * （真实踩到：raw 变成 {@code {"x":1{"x":1,"y":2,"z":3}}，永远解析失败）。
     * 判定必须挂在"唯一那次写入"上。
     */
    default ToolCall toolCallDeltaChecked(int index, String id, String name, String argsDelta) {
        toolCallDelta(index, id, name, argsDelta);
        return null;
    }
}
