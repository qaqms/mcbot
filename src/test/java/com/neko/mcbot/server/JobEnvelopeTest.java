package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;
import com.neko.mcbot.server.tools.BreakBlockTool;
import com.neko.mcbot.server.tools.MoveToTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R2-S4 受理即回执的**线契约**单测：文案前缀、相位映射、以及"哪些工具走 ACCEPT"的策略表。
 *
 * <p>为什么这些要用单测钉住而不是靠无头 SelfTest：无头 harness 没有连着的客户端，
 * `job_ack`/`job_event` 根本发不出去（`ServerPlayNetworking.send` 对着未连接的玩家是空操作），
 * 所以"信封长什么样"这件事只能在纯逻辑层验。真正的 job 往返（发出去→客户端 PARK→
 * 事件回来→续跑）由 agent-core 的 `AgentLoopParkTest` + `PendingJobsTest` 覆盖，
 * 两端之间的对接则要等主人联机时看 `[brain] job …` 日志。
 */
class JobEnvelopeTest {

    private static JsonObject args(int x, int y, int z) {
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        return o;
    }

    /**
     * 文案前缀是**跨模块契约**：服务端拼、客户端/模型认。它只有一个真源
     * （agent-core 的 {@code ToolOutcome.ACCEPTED_PREFIX}），这条断言就是防它被抄成两份后漂移。
     */
    @Test
    void acceptTextStartsWithTheContractPrefix() {
        String text = ServerToolDispatcher.acceptText(new MoveToTool(), args(12, 63, -4), "j7", 3600);
        assertTrue(text.startsWith(ToolOutcome.ACCEPTED_PREFIX),
                "受理回执必须以 ACCEPTED: 开头，模型才学得会'这不是结果'：" + text);
    }

    /** 三句教学缺一不可：还没有结果 / 别干等 / 做完我主动报编号。 */
    @Test
    void acceptTextTeachesAllThreeThingsAtOnce() {
        String text = ServerToolDispatcher.acceptText(new MoveToTool(), args(12, 63, -4), "j7", 3600);
        assertTrue(text.contains("还没有结果") || text.contains("这条还没有结果"), text);
        assertTrue(text.contains("别猜") && text.contains("别重发") && text.contains("后续工具等"), text);
        assertTrue(text.contains("j7"), "必须报出编号，模型才知道后续报的是哪一条：" + text);
        assertTrue(text.contains("12, 63, -4"), "要说清在干什么（可核对），而不是一句干巴巴的'已受理'：" + text);
    }

    /** 秒数是**按 cap 算的**，不是硬写的 60（CompanionScheduler 那处文案缺陷的同款病）。 */
    @Test
    void acceptTextDerivesSecondsFromTheCap() {
        String m = ServerToolDispatcher.acceptText(new MoveToTool(), args(0, 0, 0), "j1", 3600);
        assertTrue(m.contains("180 秒"), "3600 tick 必须说成 180 秒：" + m);
        String b = ServerToolDispatcher.acceptText(new BreakBlockTool(), args(0, 0, 0), "j2", 1200);
        assertTrue(b.contains("60 秒"), "1200 tick 必须说成 60 秒：" + b);
    }

    @Test
    void phaseMapsTheTerminalPrefixesBeforeOkFlag() {
        assertEquals("done", ServerToolDispatcher.phaseOf(true, "到了 (1,2,3) 附近。"));
        assertEquals("failed", ServerToolDispatcher.phaseOf(false, "NO_PATH:走不过去。"));
        // 前缀优先于 ok：叫停/顶替都是 ok=false，但语义不同，客户端补账的话术也不同
        assertEquals("cancelled", ServerToolDispatcher.phaseOf(false, "CANCELLED:主人叫停了。"));
        assertEquals("superseded", ServerToolDispatcher.phaseOf(false, "SUPERSEDED:被新指令顶了。"));
        assertEquals("failed", ServerToolDispatcher.phaseOf(false, null));
    }

    /**
     * 策略表：只有"跨 tick 且真的要花几秒以上"的活走 ACCEPT。
     *
     * <p>短活走 ACCEPT 是**净亏**：先回受理再回结果 = 白多一跳，而且模型还得再问一次"好了没"。
     * 所以这条表是性能取舍的载体，任何人往里加工具都该先读这段注释。
     */
    @Test
    void onlyLongRunningToolsAccept() {
        assertEquals(ServerTool.Acceptance.ACCEPT, new MoveToTool().acceptanceMode(), "move_to");
        assertEquals(ServerTool.Acceptance.ACCEPT, new BreakBlockTool().acceptanceMode(), "break_block");
        assertEquals(ServerTool.Acceptance.SYNC, new ServerTool() {
            @Override
            public String name() {
                return "fake_short";
            }
        }.acceptanceMode(), "没覆写的工具必须默认 SYNC");
    }

    /**
     * cap 只能有一个来源：工具自己 submit 用它、派发层报给客户端也用它。
     * 两边各写一份就是"服务端跑 180s、客户端 90s 判超时"那条真缺陷的成因。
     */
    @Test
    void capTicksIsTheSingleSourceForBothSides() {
        assertEquals(3600, new MoveToTool().capTicks(new JsonObject()));
        assertEquals(1200, new BreakBlockTool().capTicks(new JsonObject()));
        // 客户端据此算的等待上限必须严格大于服务端帽（agent-core 的那条不变式）
        assertTrue(com.neko.mcbot.agentcore.loop.PendingJobs.jobTimeoutMs(3600) > 3600L * 50L);
    }
}
