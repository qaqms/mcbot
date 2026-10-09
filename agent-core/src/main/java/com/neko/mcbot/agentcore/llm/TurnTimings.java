package com.neko.mcbot.agentcore.llm;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 流式时延打点（R2-A §打点）：回答"这一轮到底卡在哪"——是首包慢、首字慢，还是工具明明
 * 早写完了却等到整轮才开跑。
 *
 * <p>口径（全部以毫秒计，未发生 = -1）：
 * <ul>
 *   <li>{@code ttfb}：发起请求 → 收到 HTTP 响应头（首个数据行之前）。</li>
 *   <li>{@code ttft}：发起请求 → 第一段文本增量。</li>
 *   <li>{@code first_tool}：发起请求 → 第一个工具调用的参数写完，不代表执行器已派发。</li>
 *   <li>{@code after_chunk}：第一个数据行 → 第一段文本增量（中转站"攒够一波再吐"的直接证据）。</li>
 *   <li>{@code after_tool}：第一个数据行 → 第一个工具就绪（"文本慢但工具早"是常见形态）。</li>
 * </ul>
 * 线程：各 setter 可能被传输线程调用，字段用原子量；读侧不保证快照一致（观测用，不影响逻辑）。
 */
public final class TurnTimings implements TurnSink {

    private final long t0;
    private final java.util.function.LongSupplier clock;

    private final AtomicLong ttfb = new AtomicLong(-1);
    private final AtomicLong ttft = new AtomicLong(-1);
    private final AtomicLong firstTool = new AtomicLong(-1);
    private final AtomicLong firstChunk = new AtomicLong(-1);
    private final AtomicLong afterChunkToText = new AtomicLong(-1);
    private final AtomicLong afterChunkToTool = new AtomicLong(-1);

    private final AtomicInteger chunks = new AtomicInteger();
    private final AtomicInteger deltas = new AtomicInteger();
    private final AtomicInteger toolsReady = new AtomicInteger();

    public TurnTimings() {
        this(System::nanoTime);
    }

    /** 可注入时钟：单测把"耗时"变成确定值，真机用 nanoTime。 */
    public TurnTimings(java.util.function.LongSupplier nanoClock) {
        this.clock = nanoClock;
        this.t0 = nanoClock.getAsLong();
    }

    private long since(long base) {
        return (clock.getAsLong() - base) / 1_000_000L;
    }

    /** 收到响应头（含状态码）。 */
    public void markResponse() {
        ttfb.compareAndSet(-1, since(t0));
    }

    /** 收到一行非终止 SSE 数据（心跳/注释/[DONE] 不算，由调用方筛）。 */
    public void markChunk() {
        chunks.incrementAndGet();
        firstChunk.compareAndSet(-1, since(t0));
    }

    @Override
    public void onTextDelta(String delta) {
        deltas.incrementAndGet();
        if (ttft.compareAndSet(-1, since(t0))) {
            long c = firstChunk.get();
            if (c >= 0) {
                afterChunkToText.compareAndSet(-1, since(t0) - c);
            }
        }
    }

    @Override
    public void onToolCallReady(int index, ToolCall call) {
        if (toolsReady.incrementAndGet() == 1) {
            firstTool.compareAndSet(-1, since(t0));
            long c = firstChunk.get();
            if (c >= 0) {
                afterChunkToTool.compareAndSet(-1, since(t0) - c);
            }
        }
    }

    public long ttfbMs() {
        return ttfb.get();
    }

    public long ttftMs() {
        return ttft.get();
    }

    public long firstToolMs() {
        return firstTool.get();
    }

    public int chunks() {
        return chunks.get();
    }

    public int deltas() {
        return deltas.get();
    }

    public int toolsReady() {
        return toolsReady.get();
    }

    /** 首字相对首个数据行的滞后（中转站攒批的证据）。 */
    public long afterChunkMs() {
        return afterChunkToText.get();
    }

    /** 首个工具就绪相对首个数据行的滞后。 */
    public long afterToolMs() {
        return afterChunkToTool.get();
    }

    /** 在传输结束后冻结；客户端队列的延迟不能改变传输时间。 */
    public Snapshot snapshot() {
        return new Snapshot(t0, since(t0), ttfbMs(), ttftMs(), firstToolMs(),
                afterChunkMs(), afterToolMs(), chunks(), deltas(), toolsReady());
    }

    public record Snapshot(long requestStartedNanos, long elapsedMs, long ttfb, long ttft,
                           long firstTool, long afterChunk, long afterTool,
                           int chunks, int deltas, int toolsReady) {
        /** 仅用于同进程、同 nanoTime 时钟域的实际派发打点，不输出原始时钟值。 */
        public long sinceRequestMs(long eventNanos) {
            return (eventNanos - requestStartedNanos) / 1_000_000L;
        }
    }
}
