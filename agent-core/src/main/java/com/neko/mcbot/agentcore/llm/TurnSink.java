package com.neko.mcbot.agentcore.llm;

/**
 * 流式回调出口（R2-A）：模型边吐边把"文本增量 / 某个工具调用已写完 / 整轮结束"告诉宿主，
 * 让宿主能在整轮落地之前就开始执行工具。回调线程 = 传输层线程（mod 侧是 HttpClient 线程），
 * 实现方自己负责并发与线程跳转，**不要**在回调里做长阻塞操作。
 *
 * <p>与 {@link ChatEngine#chat(String, java.util.List, java.util.List)} 的关系：
 * 旧三参方法语义不变（等整轮）；带 sink 的重载多给一条"过程中的"通道。
 * 只实现旧方法的引擎（如单测替身）由接口默认实现兜底：先拿整轮、再补发一次
 * {@code onComplete}，绝不为了省事让上层看到"没有 sink"的空档。
 */
public interface TurnSink {

    /** 一段解码好的文本增量。 */
    default void onTextDelta(String delta) {
    }

    /**
     * 第 {@code index} 个工具调用的参数已经写完、可以派发了。
     * <b>只应触发一次</b>（由 {@link com.neko.mcbot.agentcore.provider.ToolArgsScanner} 保证单调）。
     * index 与最终 {@link AssistantTurn#toolCalls()} 的下标对应；宿主不应假设回调顺序，
     * 但最终写回执时必须按 index 原序（OpenAI 协议里 tool 消息要与 assistant.tool_calls 配对）。
     */
    default void onToolCallReady(int index, ToolCall call) {
    }

    /** 整轮结束（成功或失败恰好一次）。失败语义与旧三参方法一致：以异常完成。 */
    default void onComplete(AssistantTurn turn, Throwable error) {
    }

    /** 进度计数（可选）：收到的 SSE 数据行数、文本增量段数、工具调用就绪数。 */
    default void onCounters(int chunks, int deltas, int toolCallsReady) {
    }
}
