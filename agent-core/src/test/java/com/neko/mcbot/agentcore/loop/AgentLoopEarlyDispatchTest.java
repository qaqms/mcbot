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

    /**
     * 多个工具：结果<b>记账</b>必须按 index 升序，与"谁先完成"完全无关。
     *
     * <p>造法：只提前报第 0 个（第 1、2 个等整轮落地才派发），并把三个 future 全交给测试线程，
     * 按 <b>2 → 1 → 0 的逆序</b>完成，让"完成顺序"与"index 顺序"严格相反。
     *
     * <p><b>断言必须放在完成中途</b>：若只在全部完成后再看历史，{@code allOf}（只等齐、
     * 不排序）也会因为"最终都写进去了"而通过——那样的断言对实现没有鉴别力。
     * 这里在只完成 c 的那一刻就检查"已记账集合"，排序与非排序实现在此刻必然不同：
     * 有序实现一个都还没写（在等 a），非排序实现已经写了 c。
     */
    @Test
    void toolResultsAreRecordedInIndexOrder() {
        var engine = new ScriptedEngine().queue(toolTurn("a", "b", "c"), textTurn("都好了"));
        engine.streamingDelayMs = 100;
        engine.readyReportLimit = 1;
        var futures = new ConcurrentHashMap<String, CompletableFuture<ToolExecutor.ToolOutcome>>();
        var replies = new CopyOnWriteArrayList<String>();
        var dispatched = new CountDownLatch(3);

        var loop = new AgentLoop(engine, List.of(),
                (name, args) -> {
                    CompletableFuture<ToolExecutor.ToolOutcome> f = new CompletableFuture<>();
                    futures.put(name, f);
                    dispatched.countDown();
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
        assertTrue(await(dispatched), "三个工具都应被派发（早派发 1 个 + 整轮后 2 个）");

        // 只完成最后一条（index 2）：有序记账此时必须"一格都还没写"
        futures.get("c").complete(new ToolExecutor.ToolOutcome(true, "c ok"));
        List<String> midway = recordedToolNames(loop);
        assertTrue(midway.isEmpty(),
                "index 0/1 还没落地时不许先写 index 2 的回执（否则配对顺序就乱了），实际: " + midway);

        // 再把 1、0 逆序补齐；最终集合仍必须是 a,b,c
        futures.get("b").complete(new ToolExecutor.ToolOutcome(true, "b ok"));
        futures.get("a").complete(new ToolExecutor.ToolOutcome(true, "a ok"));
        awaitTrue("跑完", () -> replies.contains("都好了"));

        assertEquals(List.of("a", "b", "c"), recordedToolNames(loop),
                "工具回执必须按 index 原序写回（完成顺序被故意做成逆序）");
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
        assertTrue(stats.get(0).accumulated(), "默认路径要拼整轮");
    }
}
