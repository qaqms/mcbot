package com.neko.mcbot.agentcore.loop;

import com.neko.mcbot.agentcore.ScriptedEngine;
import com.neko.mcbot.agentcore.llm.AssistantTurn;
import com.neko.mcbot.agentcore.llm.Msg;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R2-S4「受理即回执 + PARK」的行为测试。
 *
 * <p>这一卡只改一件事的语义：**"受理"不等于"有结果"**。所以这里钉的也是这件事的三种后果：
 * <ol>
 *   <li>受理之后**不许再问模型**（PARK）——否则"别干等"就白说了，模型还是会拿到一条
 *       假装是结果的回执；</li>
 *   <li>真结果到了要**按 index 原序**补进对话再开新轮（协议要求 tool 消息与
 *       assistant.tool_calls 严格同序配对，"第 0 条在跑、第 1 条当场回了"是最容易写错的形状）；</li>
 *   <li>PARK 被解锁（叫停 / 新指令）之前，**每一条 in-flight 的 tool_call 都必须有回执**，
 *       哪怕是本地合成的 {@code CANCELLED:}/{@code SUPERSEDED:}——少一条，下一个请求直接 400，
 *       一整段历史当场作废。</li>
 * </ol>
 */
class AgentLoopParkTest {

    /** "job*" 名字的工具返回受理回执；其余当场返回结果。 */
    private static final class FakeTools implements ToolExecutor {
        final CopyOnWriteArrayList<String> called = new CopyOnWriteArrayList<>();
        final java.util.Map<String, String> jobIds = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public CompletableFuture<ToolOutcome> execute(String name, String argsJson) {
            called.add(name);
            if (name.startsWith("job")) {
                String jobId = "J-" + name;
                jobIds.put(name, jobId);
                return CompletableFuture.completedFuture(ToolOutcome.accepted(jobId,
                        ToolOutcome.ACCEPTED_PREFIX + "已开始 " + name + "，这条还没有结果。"));
            }
            return CompletableFuture.completedFuture(new ToolOutcome(true, name + " 当场好了"));
        }
    }

    private static final class Recorder implements AgentLoop.Listener {
        final List<String> replies = new CopyOnWriteArrayList<>();
        final AtomicInteger parks = new AtomicInteger();
        final List<Integer> parkOutstanding = new CopyOnWriteArrayList<>();
        final List<String> accepts = new CopyOnWriteArrayList<>();
        final List<String> invoked = new CopyOnWriteArrayList<>();

        @Override
        public void onReply(String text) {
            replies.add(text);
        }

        @Override
        public void onParked(boolean parked, int outstanding) {
            if (parked) {
                parks.incrementAndGet();
                parkOutstanding.add(outstanding);
            }
        }

        @Override
        public void onToolAccepted(String name, String argsJson, String feedback, String jobId) {
            accepts.add(name + "->" + jobId);
        }

        @Override
        public void onToolInvoked(String name, String argsJson, boolean ok, String feedback) {
            invoked.add(name + "|" + feedback);
        }
    }

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

    private record Fixture(ScriptedEngine engine, FakeTools tools, Recorder rec, AgentLoop loop) {
    }

    private static Fixture fixture(AssistantTurn... turns) {
        var engine = new ScriptedEngine().queue(turns);
        var tools = new FakeTools();
        var rec = new Recorder();
        var loop = new AgentLoop(engine, List.of(), tools, AgentLoop.Config.defaults(), rec,
                () -> "SYS", 6000);
        return new Fixture(engine, tools, rec, loop);
    }

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

    private static List<Msg.Tool> toolMsgs(AgentLoop loop) {
        List<Msg.Tool> out = new ArrayList<>();
        for (Msg m : loop.conversation().outboundHistory()) {
            if (m instanceof Msg.Tool t) {
                out.add(t);
            }
        }
        return out;
    }

    // ---- 1. 受理 ⇒ PARK，不再问模型 ----

    @Test
    void acceptedCallParksInsteadOfStepping() {
        var f = fixture(toolTurn("jobDig"), textTurn("我继续"));

        f.loop().submit("挖一下");
        awaitTrue("受理回调", () -> f.rec().parks.get() == 1);

        assertEquals(1, f.engine().calls, "受理之后不许再问模型——这正是 PARK 的意义");
        assertEquals(List.of("jobDig->J-jobDig"), f.rec().accepts);
        assertTrue(toolMsgs(f.loop()).isEmpty(),
                "受理不是结果：绝不能写进对话（写了就等于告诉模型事情做完了）");
        // 工具回执通道没被误用：受理不触发 onToolInvoked
        assertTrue(f.rec().invoked.isEmpty());
        // 对话末尾是那条 assistant(tool_calls)，正好停在"等结果"的形状上
        List<Msg> hist = f.loop().conversation().outboundHistory();
        assertTrue(hist.get(hist.size() - 1) instanceof Msg.Assistant);
    }

    // ---- 2. job 事件 ⇒ 补账 + 开新轮 ----

    @Test
    void jobEventRecordsTheToolMessageAndOpensANewTurn() {
        var f = fixture(toolTurn("jobDig"), textTurn("我继续"));
        f.loop().submit("挖一下");
        awaitTrue("PARK", () -> f.rec().parks.get() == 1);

        f.loop().onJobEvent("J-jobDig", new ToolOutcome(true, "挖完了 3 格"));
        awaitTrue("续跑", () -> f.engine().calls == 2);

        List<Msg.Tool> tools = toolMsgs(f.loop());
        assertEquals(1, tools.size());
        assertEquals("c0", tools.get(0).callId(), "callId 必须配对回原 tool_call");
        assertEquals("挖完了 3 格", tools.get(0).content());
        assertEquals(List.of("我继续"), f.rec().replies);
    }

    // ---- 3. 混合顺序：第 0 条在跑、第 1 条当场有结果 ----

    @Test
    void mixedTurnWritesToolMessagesInIndexOrder() {
        var f = fixture(toolTurn("jobDig", "status"), textTurn("我继续"));
        f.loop().submit("边挖边看");
        awaitTrue("PARK", () -> f.rec().parks.get() == 1);

        // 第 1 条（status）当场就有结果，但第 0 条还在跑 —— 此时一条都不能写
        assertTrue(toolMsgs(f.loop()).isEmpty(), "第 1 条不能越过第 0 条先写（协议要求同序配对）");
        // 而且整轮的工具都问过了（串行链照跑，只是不落账）
        assertEquals(List.of("jobDig", "status"), f.tools().called);

        f.loop().onJobEvent("J-jobDig", new ToolOutcome(true, "挖完了"));
        awaitTrue("续跑", () -> f.engine().calls == 2);

        List<Msg.Tool> tools = toolMsgs(f.loop());
        assertEquals(2, tools.size());
        assertEquals("c0", tools.get(0).callId());
        assertEquals("挖完了", tools.get(0).content());
        assertEquals("c1", tools.get(1).callId());
        assertEquals("status 当场好了", tools.get(1).content());
    }

    // ---- 4. 叫停：PARK 解锁必须补齐 CANCELLED ----

    @Test
    void cancelWhileParkedSynthesizesACancelledReceipt() {
        var f = fixture(toolTurn("jobDig"), textTurn("我继续"));
        f.loop().submit("挖一下");
        awaitTrue("PARK", () -> f.rec().parks.get() == 1);

        f.loop().cancelDirective();
        awaitTrue("合成回执", () -> !toolMsgs(f.loop()).isEmpty());

        List<Msg.Tool> tools = toolMsgs(f.loop());
        assertEquals(1, tools.size());
        assertEquals("c0", tools.get(0).callId());
        assertTrue(tools.get(0).content().startsWith("CANCELLED:"),
                "叫停补的必须是 CANCELLED 前缀：" + tools.get(0).content());
        assertFalse(tools.get(0).ok());
        assertEquals(1, f.engine().calls, "叫停不该再问模型");
        assertEquals(1, f.rec().replies.size(), "叫停要回主人一句话");
    }

    // ---- 5. 新指令顶替 PARK + 后续事件幂等 ----

    @Test
    void newDirectiveSupersedesTheParkedJobAndLateEventIsIgnored() {
        var f = fixture(toolTurn("jobDig"), textTurn("新指令的回话"));
        f.loop().submit("挖一下");
        awaitTrue("PARK", () -> f.rec().parks.get() == 1);

        f.loop().submit("先别挖了，看看脚下");
        awaitTrue("新指令推进", () -> f.engine().calls == 2);

        List<Msg.Tool> tools = toolMsgs(f.loop());
        assertEquals(1, tools.size(), "被顶替的那条 tool_call 必须有回执，否则下一个请求 400");
        assertTrue(tools.get(0).content().startsWith("SUPERSEDED:"),
                "换发补的必须是 SUPERSEDED 前缀：" + tools.get(0).content());

        // 服务端那条真结果随后才到：应当被幂等丢掉，不许改写已经落账的对话
        f.loop().onJobEvent("J-jobDig", new ToolOutcome(true, "其实挖完了"));
        assertEquals(1, toolMsgs(f.loop()).size());
        assertEquals(2, f.engine().calls);
    }

    /** 每个 assistant(tool_calls) 里的 id 都必须能在后面的 tool 消息里找到——这就是"不 400"的判据。 */
    @Test
    void everyToolCallIsAnsweredBeforeTheNextModelRequest() {
        var f = fixture(toolTurn("jobDig", "jobScan"), textTurn("我继续"));
        f.loop().submit("两件长活");
        awaitTrue("PARK", () -> f.rec().parks.get() == 1);
        assertEquals(2, f.rec().parkOutstanding.get(0), "两条都在飞");

        // 只回一条：还差一条，不许 step（否则那次请求里 c1 没有配对回执）
        f.loop().onJobEvent("J-jobDig", new ToolOutcome(true, "挖完了"));
        assertEquals(1, f.engine().calls, "缺口没补齐就不该开新轮");

        f.loop().onJobEvent("J-jobScan", new ToolOutcome(true, "扫完了"));
        awaitTrue("两条齐了才续跑", () -> f.engine().calls == 2);

        List<Msg.Tool> tools = toolMsgs(f.loop());
        assertEquals(2, tools.size());
        assertEquals("c0", tools.get(0).callId());
        assertEquals("c1", tools.get(1).callId());
    }

    /** 未知 jobId（例如叫停后服务端才回）永远不该抛、也不该动对话。 */
    @Test
    void unknownJobEventIsANoOp() {
        var f = fixture(toolTurn("jobDig"), textTurn("我继续"));
        f.loop().submit("挖一下");
        awaitTrue("PARK", () -> f.rec().parks.get() == 1);

        f.loop().onJobEvent("不存在的 job", new ToolOutcome(true, "？"));
        f.loop().onJobEvent(null, new ToolOutcome(true, "？"));
        assertEquals(1, f.engine().calls);
        assertTrue(toolMsgs(f.loop()).isEmpty());
    }
}
