package com.neko.mcbot.agentcore.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具调用的**两段式等待**记账（R2-S4 受理即回执）。
 *
 * <pre>
 *   发出 tool_call ──(tool_result)──▶ 终局：一次调用一个结果
 *        │
 *        └──(job_ack)──▶ 转段：同一个 seq 从此改等 job 事件（超时口径也换）
 * </pre>
 *
 * <p><b>为什么要显式分两段</b>：跨 tick 长活的等待上限由**服务端的能力帽**决定
 * （{@code move_to} 3600 tick = 180s），而普通工具是"发出去就该马上回"。用同一个 90 秒帽
 * 盖住两者，就是效率评估里那条真缺陷：90–180s 的移动被判 TIMEOUT，模型收到一句
 * "先别重复这个操作"的**错误教学**，而它其实还在走；真回执到了又被当"迟到"丢弃。
 * 所以第二段的超时**必须由服务端随受理回执报出的 cap 算出来**，而不是客户端猜。
 *
 * <p><b>契约（改这里之前先读）</b>：
 * <ul>
 *   <li>超时必须**严格大于**服务端 cap（{@link #JOB_GRACE_MS} 余量），否则 TIMEOUT 不是
 *       "真超时"而是"客户端先跑了"；</li>
 *   <li>一个 seq 只会转段一次：重复受理按**迟到**处理（返回 null 交给调用方留痕），
 *       因为第一条受理回执已经把那个 future 兑现了，再兑现一次没有意义；</li>
 *   <li>{@link #takeTool(long)} 拿不到就是**迟到回执**——必须留痕，那是超时帽配错的直接证据；</li>
 *   <li>{@link #takeJob(String)} 拿不到是**正常**的：叫停/换发时本地已补过合成回执，
 *       服务端随后到达的真结果就该被丢掉（幂等）。</li>
 * </ul>
 *
 * <p>放在 agent-core 而不是 mod 侧，是因为这段"关联 + 超时口径"是纯逻辑、也最容易被改错，
 * 必须能被单测直接钉（mod 侧类进不了根工程单测）。
 *
 * @param <T> 调用方自己的凭据（mod 侧放"那个 CompletableFuture"）：受理回执要兑现的是
 *            **同一个**凭据，所以让它在转段时跟着一起搬，而不是让调用方另开一张表去对。
 */
public final class PendingJobs<T> {

    /** 受理后的余量：服务端 cap 之上再给这么多，覆盖回程与调度抖动。 */
    public static final long JOB_GRACE_MS = 15_000L;

    /** 服务端没报 cap 时的兜底（与 CompanionScheduler.DEFAULT_CAP_TICKS 同值）。 */
    public static final int FALLBACK_CAP_TICKS = 1200;

    /** 已发出、还没定性的工具调用。 */
    public record Tool<T>(long seq, String tool, long sentAtMs, long timeoutMs, T ticket) {
    }

    /** 已受理、还在跑的长活。 */
    public record Job<T>(String jobId, long seq, String tool, long acceptedAtMs, long timeoutMs,
                         T ticket) {
    }

    private final Map<Long, Tool<T>> tools = new ConcurrentHashMap<>();
    private final Map<String, Job<T>> jobs = new ConcurrentHashMap<>();

    /**
     * 受理后的等待上限。**从服务端报的 cap 算**，不是拍一个常数：
     * capTicks = 3600（move_to 帽）⇒ 180s + 15s = 195s。
     */
    public static long jobTimeoutMs(int capTicks) {
        int cap = capTicks > 0 ? capTicks : FALLBACK_CAP_TICKS;
        return cap * 50L + JOB_GRACE_MS;
    }

    /** 登记一次已发出的工具调用。 */
    public synchronized void registerTool(long seq, String tool, long nowMs, long timeoutMs, T ticket) {
        tools.put(seq, new Tool<>(seq, tool, nowMs, timeoutMs, ticket));
    }

    /** 取走并移除（拿到 = 这是它的终局回执）。null = 迟到的回执。 */
    public synchronized Tool<T> takeTool(long seq) {
        return tools.remove(seq);
    }

    /**
     * 把 seq 从"等回执"转成"等 job 事件"，凭据原样搬过去。
     *
     * @return 转段后的 Job；若该 seq 根本没登记（迟到/被叫停收走）返回 null
     */
    public synchronized Job<T> acceptAsJob(long seq, String jobId, int capTicks, long nowMs) {
        Tool<T> t = tools.remove(seq);
        if (t == null) {
            return null;
        }
        Job<T> j = new Job<>(jobId, seq, t.tool(), nowMs, jobTimeoutMs(capTicks), t.ticket());
        jobs.put(jobId, j);
        return j;
    }

    /** 取走并移除（拿到 = job 事件到了）。null = 未知/已被本地合成回执收走。 */
    public synchronized Job<T> takeJob(String jobId) {
        return jobId == null ? null : jobs.remove(jobId);
    }

    /** 超时的工具调用（调用方负责给每个补一条 TIMEOUT 教学）。 */
    public synchronized List<Tool<T>> sweepTools(long nowMs) {
        List<Tool<T>> out = new ArrayList<>();
        for (Tool<T> t : new ArrayList<>(tools.values())) {
            if (nowMs - t.sentAtMs() > t.timeoutMs() && tools.remove(t.seq()) != null) {
                out.add(t);
            }
        }
        return out;
    }

    /** 超时的长活（调用方负责给每个补一条 TIMEOUT 教学，并让 PARK 解锁）。 */
    public synchronized List<Job<T>> sweepJobs(long nowMs) {
        List<Job<T>> out = new ArrayList<>();
        for (Job<T> j : new ArrayList<>(jobs.values())) {
            if (nowMs - j.acceptedAtMs() > j.timeoutMs() && jobs.remove(j.jobId()) != null) {
                out.add(j);
            }
        }
        return out;
    }

    public int pendingTools() {
        return tools.size();
    }

    public synchronized Job<T> peekJob(String jobId) {
        return jobId == null ? null : jobs.get(jobId);
    }

    /** 移除一个任务的全部等待凭据；先移除再由宿主兑现，避免回调重入污染新任务。 */
    public synchronized List<T> drain(java.util.function.Predicate<T> belongsToTask) {
        List<T> out = new ArrayList<>();
        tools.values().removeIf(t -> {
            if (!belongsToTask.test(t.ticket())) return false;
            out.add(t.ticket());
            return true;
        });
        jobs.values().removeIf(j -> {
            if (!belongsToTask.test(j.ticket())) return false;
            out.add(j.ticket());
            return true;
        });
        return out;
    }

    public int pendingJobs() {
        return jobs.size();
    }
}
