package com.neko.mcbot.agentcore.loop;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两段式等待的记账与超时口径单测（R2-S4）。
 *
 * <p>最要紧的一条是**超时必须严格大于服务端能力帽**：效率评估里那条真缺陷就是
 * 客户端 90s &lt; 服务端 move 帽 180s，于是"还在走的移动"被判超时，模型收到一句
 * 错误的"先别重复这个操作"，真回执随后又被当迟到丢弃。所以这里不只钉数值，
 * 还钉"它是由 cap 算出来的"这件事。
 */
class PendingJobsTest {

    private static final long T0 = 1_700_000_000_000L;

    @Test
    void ackMigratesTheWaitFromSeqToJob() {
        PendingJobs<String> p = new PendingJobs<>();
        p.registerTool(7, "move_to", T0, 210_000, "tk");
        assertEquals(1, p.pendingTools());

        PendingJobs.Job<String> j = p.acceptAsJob(7, "j1", 3600, T0 + 300);
        assertNotNull(j);
        assertEquals("j1", j.jobId());
        assertEquals(7, j.seq());
        assertEquals("move_to", j.tool());
        assertEquals("tk", j.ticket(), "凭据要跟着转段走：受理回执兑现的是同一个 future");
        assertEquals(0, p.pendingTools(), "转段后不该在 seq 通道里留占位");
        assertEquals(1, p.pendingJobs());
        assertNull(p.takeTool(7), "转段之后 seq 通道已经关了");
    }

    @Test
    void jobTimeoutIsDerivedFromTheServerCapAndExceedsIt() {
        // move_to 帽 3600tick=180s ⇒ 195s；必须严格大于 180s，否则 TIMEOUT 是"客户端先跑了"
        assertEquals(195_000L, PendingJobs.jobTimeoutMs(3600));
        assertTrue(PendingJobs.jobTimeoutMs(3600) > 3600L * 50L);
        assertEquals(75_000L, PendingJobs.jobTimeoutMs(1200));
        // 服务端没报 cap 时用兜底（与 CompanionScheduler 同值），不是"立刻超时"
        assertEquals(PendingJobs.jobTimeoutMs(PendingJobs.FALLBACK_CAP_TICKS),
                PendingJobs.jobTimeoutMs(0));
    }

    @Test
    void ackForAnUnknownSeqIsLateAndChangesNothing() {
        PendingJobs<String> p = new PendingJobs<>();
        assertNull(p.acceptAsJob(99, "j9", 1200, T0));
        assertEquals(0, p.pendingJobs());
    }

    @Test
    void secondAckForTheSameSeqIsLate() {
        PendingJobs<String> p = new PendingJobs<>();
        p.registerTool(7, "move_to", T0, 210_000, "tk");
        assertNotNull(p.acceptAsJob(7, "j1", 3600, T0));
        assertNull(p.acceptAsJob(7, "j1", 3600, T0 + 1), "重复受理按迟到处理");
        assertEquals(1, p.pendingJobs());
    }

    @Test
    void takeJobIsIdempotent() {
        PendingJobs<String> p = new PendingJobs<>();
        p.registerTool(7, "move_to", T0, 210_000, "tk");
        p.acceptAsJob(7, "j1", 3600, T0);

        assertNotNull(p.takeJob("j1"));
        assertNull(p.takeJob("j1"), "同一个 job 的事件只该被消费一次");
        assertEquals(0, p.pendingJobs());
    }

    @Test
    void sweepsReportOnlyWhatExpired() {
        PendingJobs<String> p = new PendingJobs<>();
        p.registerTool(1, "scan_area", T0, 90_000, "tk");        // 90s 帽
        p.registerTool(2, "wait", T0, 80_000, "tk");             // 80s 帽
        p.registerTool(3, "move_to", T0, 210_000, "tk");
        assertEquals(List.of(), p.sweepTools(T0 + 79_000));

        List<PendingJobs.Tool<String>> expired = p.sweepTools(T0 + 85_000);
        assertEquals(1, expired.size(), "85s 时只有 80s 帽的那条到期");
        assertEquals(2, expired.get(0).seq());
        // 已被 sweep 收走的不会重复报；此时轮到 90s 帽的那条
        List<PendingJobs.Tool<String>> next = p.sweepTools(T0 + 90_100);
        assertEquals(1, next.size());
        assertEquals(1, next.get(0).seq());
        assertTrue(p.sweepTools(T0 + 90_200).isEmpty(), "同一条不该被报两次");
    }

    @Test
    void jobSweepUsesTheJobDeadlineNotTheToolOne() {
        PendingJobs<String> p = new PendingJobs<>();
        p.registerTool(7, "move_to", T0, 210_000, "tk");
        p.acceptAsJob(7, "j1", 3600, T0);

        // 一条 180s 帽的长活，在 200s 时还没超时（195s 才是线）
        assertEquals(0, p.sweepJobs(T0 + 194_000).size());
        List<PendingJobs.Job<String>> late = p.sweepJobs(T0 + 195_100);
        assertEquals(1, late.size());
        assertEquals("j1", late.get(0).jobId());
        assertEquals(0, p.pendingJobs());
    }
}
