package com.neko.mcbot.agentcore.loop;

import com.neko.mcbot.agentcore.ScriptedEngine;
import com.neko.mcbot.agentcore.llm.AssistantTurn;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R2-A 早派发的行为测试：工具必须在**整轮落地之前**就开始跑，而且结果写回对话时
 * 仍按 index 原序（协议要求 tool 消息与 assistant.tool_calls 严格配对同序）。
 *
 * <p>用 {@link ScriptedEngine#streamingDelayMs} 造出"就绪信号先到、整轮后到"的时序，
 * 否则纯同步替身下这两种顺序不可区分，测试也就证明不了什么。
 */
class AgentLoopEarlyDispatchTest {

    private static AssistantTurn toolTurn(String... names) {
        List<ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            calls.add(new ToolCall("c" + i, names[i], "{\"i\":" + i + "}"));
        }
        return new AssistantTurn("", calls, 0, 0, -1, "tool_calls");
    }

    private static AssistantTurn textTurn(String text) {
        return new AssistantTurn(text, List.of(), 0, 0, -1, "stop");
    }

    /** 等到回调条件成立，最多等 5s；避免用 sleep 猜时序。 */
    private static void awaitTrue(String what, java.util.function.BooleanSupplier cond) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("等待超时：" + what);
    }

    @Test
    void toolStartsBeforeTurnLands() {
        var engine = new ScriptedEngine().queue(toolTurn("dig"), textTurn("挖完了"));
        engine.streamingDelayMs = 150;
        var startedAt = new ConcurrentHashMap<String, Long>();
        var replies = new CopyOnWriteArrayList<String>();
        long t0 = System.nanoTime();

        var loop = new AgentLoop(engine, List.of(),
                (name, args) -> {
                    startedAt.put(name, (System.nanoTime() - t0) / 1_000_000L);
                    return CompletableFuture.completedFuture(
                            new ToolExecutor.ToolOutcome(true, "挖掉了"));
                },
                AgentLoop.Config.defaults(),
                new AgentLoop.Listener() {
                    @Override
                    public void onReply(String text) {
                        replies.add(text);
                    }
                },
                () -> "sys", 1_000_000);

        loop.submit("挖一块");
        awaitTrue("模型第二次作答", () -> replies.contains("挖完了"));

        Long at = startedAt.get("dig");
        assertTrue(at != null, "工具必须被执行");
        assertTrue(at < 120, "工具应在整轮落地（约 150ms）之前就起跑，实际 " + at + "ms");
    }

    /** 早派发只启动一次：整轮落地后不得再执行同一个 index。 */
    @Test
    void earlyDispatchedToolIsNotExecutedTwice() {
        var engine = new ScriptedEngine().queue(toolTurn("dig"), textTurn("好"));
        engine.streamingDelayMs = 120;
        var counts = new ConcurrentHashMap<String, AtomicInteger>();
        var replies = new CopyOnWriteArrayList<String>();

        var loop = new AgentLoop(engine, List.of(),
                (name, args) -> {
                    counts.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
                    return CompletableFuture.completedFuture(
                            new ToolExecutor.ToolOutcome(true, "ok"));
                },
                AgentLoop.Config.defaults(),
                new AgentLoop.Listener() {
                    @Override
                    public void onReply(String text) {
                        replies.add(text);
                    }
                },
                () -> "sys", 1_000_000);

        loop.submit("挖");
        awaitTrue("跑完", () -> replies.contains("好"));

        assertEquals(1, counts.get("dig").get(), "同一个 index 只许执行一次");
        // user / assistant(tool_call) / tool / assistant
        var h = loop.conversation().history();
        assertEquals(4, h.size(), "历史结构不得因早派发而变，实际: " + h);
        assertTrue(h.get(2) instanceof Msg.Tool, "第三条应是工具回执");
    }

    /** 首个工具可以早起跑，后续工具必须等前一个回执，记账仍与调用顺序一致。 */
    @Test
    void earlyToolFinishesBeforeLaterToolsAreDispatchedAndRecorded() {
        var engine = new ScriptedEngine().queue(toolTurn("a", "b", "c"), textTurn("都好了"));
        engine.streamingDelayMs = 100;
        engine.readyReportLimit = 1;
        var futures = new ConcurrentHashMap<String, CompletableFuture<ToolExecutor.ToolOutcome>>();
        var replies = new CopyOnWriteArrayList<String>();

        var loop = new AgentLoop(engine, List.of(),
                (name, args) -> {
                    CompletableFuture<ToolExecutor.ToolOutcome> f = new CompletableFuture<>();
                    futures.put(name, f);
                    return f;
                },
                AgentLoop.Config.defaults(),
                new AgentLoop.Listener() {
                    @Override
                    public void onReply(String text) {
                        replies.add(text);
                    }
                },
                () -> "sys", 1_000_000);

        loop.submit("三件事");
        awaitTrue("整轮已落地", () -> loop.conversation().history().size() >= 2);
        assertEquals(java.util.Set.of("a"), futures.keySet(),
                "整轮落地也不能提前派发 b/c：服务端身体仍在执行 a");
        assertTrue(recordedToolNames(loop).isEmpty());
        futures.get("a").complete(new ToolExecutor.ToolOutcome(true, "a ok"));
        awaitTrue("b 起跑", () -> futures.containsKey("b"));
        assertEquals(java.util.Set.of("a", "b"), futures.keySet(),
                "b 没回执时 c 也不能起跑");
        futures.get("b").complete(new ToolExecutor.ToolOutcome(true, "b ok"));
        awaitTrue("c 起跑", () -> futures.containsKey("c"));
        futures.get("c").complete(new ToolExecutor.ToolOutcome(true, "c ok"));
        awaitTrue("跑完", () -> replies.contains("都好了"));

        assertEquals(List.of("a", "b", "c"), recordedToolNames(loop),
                "工具回执必须按 index 原序写回");
        var h = loop.conversation().history();
        var assistant = (Msg.Assistant) h.get(1);
        assertEquals(List.of("a", "b", "c"),
                assistant.toolCalls().stream().map(ToolCall::name).toList(),
                "assistant 里的调用顺序是配对的基准，不能被改动");
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 当前已经写进对话的工具回执名字（按写入顺序）。 */
    private static List<String> recordedToolNames(AgentLoop loop) {
        List<String> out = new ArrayList<>();
        for (Msg m : loop.conversation().history()) {
            if (m instanceof Msg.Tool t) {
                out.add(t.name());
            }
        }
        return out;
    }

    /** 流式打点要经 listener 暴露出来（宿主接 [brain] llm stream 日志）。 */
    @Test
    void streamStatsAreReported() {
        var engine = new ScriptedEngine().queue(textTurn("嗯"));
        var stats = new CopyOnWriteArrayList<AgentLoop.StreamStats>();
        var loop = new AgentLoop(engine, List.of(),
                (n, a) -> CompletableFuture.completedFuture(new ToolExecutor.ToolOutcome(true, "")),
                AgentLoop.Config.defaults(),
                new AgentLoop.Listener() {
                    @Override
                    public void onStreamStats(AgentLoop.StreamStats s) {
                        stats.add(s);
                    }
                },
                () -> "sys", 1_000_000);

        loop.submit("随便说句");
        assertEquals(1, stats.size(), "每步应报一次打点");
        assertTrue(stats.get(0).format().contains("ttfb="), "打点格式要可 grep");
        assertTrue(stats.get(0).format().contains("cache_waste="), "缓存浪费指标要在打点里");
        assertTrue(stats.get(0).accumulated(), "默认路径要拼整轮");
    }

    /**
     * 同轮多个工具时，**只有第一个**能提前派发。
     *
     * <p>钉的是 R2-S3 引入、又在效率评估里被修掉的一个真缺陷：早派发在流式过程中就把 payload
     * 发出去，绕过了 {@code awaitAndRecord} 的串行链；而 mod 侧只有一具身体 + 单槽调度器，
     * 于是"同轮 3 个占身体的调用一起早派发"会让后两个白拿 BUSY（各换一次白跑的往返）。
     * 现在的纪律：只有最先就绪的那个提前起跑，其余等整轮落地后按 index 串行。
     *
     * <p>断言必须落在"整轮还没落地"的时刻，才分得出"早发"与"轮落地后才发"。
     */
    @Test
    void onlyFirstToolIsDispatchedEarlyWhenSeveralAreReady() {
        var engine = new ScriptedEngine().queue(toolTurn("a", "b", "c"), textTurn("都好了"));
        engine.streamingDelayMs = 200;
        engine.readyReportLimit = -1; // 三个都在整轮落地前报就绪 → 三个都想抢额度
        var starts = new ConcurrentHashMap<String, Long>();
        var replies = new CopyOnWriteArrayList<String>();
        var allDispatched = new CountDownLatch(3);
        long t0 = System.nanoTime();

        var loop = new AgentLoop(engine, List.of(),
                (name, args) -> {
                    starts.putIfAbsent(name, (System.nanoTime() - t0) / 1_000_000L);
                    allDispatched.countDown();
                    return CompletableFuture.completedFuture(
                            new ToolExecutor.ToolOutcome(true, name + " ok"));
                },
                AgentLoop.Config.defaults(),
                new AgentLoop.Listener() {
                    @Override
                    public void onReply(String text) {
                        replies.add(text);
                    }
                },
                () -> "sys", 1_000_000);

        loop.submit("三件事");
        assertTrue(await(allDispatched), "三个工具最终都要被执行");
        awaitTrue("跑完", () -> replies.contains("都好了"));

        Long aAt = starts.get("a");
        Long bAt = starts.get("b");
        Long cAt = starts.get("c");
        assertTrue(aAt != null && aAt < 160, "第一个工具应早派发（实际 " + aAt + "ms）");
        assertTrue(bAt != null && bAt >= 160,
                "第二个工具不得抢早派发额度（实际 " + bAt + "ms）——否则服务端单槽会回 BUSY");
        assertTrue(cAt != null && cAt >= 160, "第三个工具同上（实际 " + cAt + "ms）");
    }

    /** 缓存浪费：正常延续应贴近 0；前缀被换掉才抬头。首轮/后端没报 cached 时不判定。 */
    @Test
    void cacheWasteMetricDetectsPrefixBreak() {
        // 正常：上轮 3000、本轮 3200、命中 3000 ⇒ 3000-3000-1024 < 0 ⇒ 0
        assertEquals(0, AgentLoop.cacheWasteOf(3000, 3200, 3000));
        // 前缀被换：命中只剩 500 ⇒ 3000-500-1024 = 1476
        assertEquals(1476, AgentLoop.cacheWasteOf(3000, 3200, 500));
        // 首轮没有"上一轮" ⇒ 不判定
        assertEquals(-1, AgentLoop.cacheWasteOf(0, 3200, 0));
        // 后端没报 cached ⇒ 不判定
        assertEquals(-1, AgentLoop.cacheWasteOf(3000, 3200, -1));
        // 噪声底之内不报
        assertEquals(0, AgentLoop.cacheWasteOf(3000, 3000, 2500));
    }
}
