package com.neko.mcbot.server;

import com.google.gson.JsonObject;
import com.neko.mcbot.McbotMod;
import com.neko.mcbot.body.CompanionPlayer;
import com.neko.mcbot.body.SummonService;
import com.neko.mcbot.common.Envelope;
import com.neko.mcbot.common.McbotPayloads;
import com.neko.mcbot.common.WireSize;
import com.neko.mcbot.task.CompanionScheduler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * C2S 信封处理：三道闸 → 工具执行 → S2C 回执。
 * 调用方（payload receiver）保证已在服务器线程。
 */
public final class ServerToolDispatcher {

    private final MinecraftServer server;
    private final SummonService summon;
    private final ToolRegistry registry;
    private final RateGuard rate;
    private final ActionPermissions permissions = new ActionPermissions();
    private final CompanionScheduler scheduler;
    private final ToolAccess access;

    record Body(UUID id, String dimension, Object identity, int x, int y, int z, String name) {
    }

    record Sender(UUID owner, ServerPlayer player, Consumer<String> output) {
    }

    // Only Minecraft lookup, tool effects and packet delivery are replaced in offline tests.
    interface ToolAccess {
        Body companion(UUID owner);
        default String bodyId(Body body) {
            return body.identity() instanceof CompanionPlayer cp ? cp.observationBodyId().toString() : "";
        }
        default boolean alive(Body body) {
            return !(body.identity() instanceof CompanionPlayer cp)
                    || cp.isAlive() && !cp.isRemoved() && !cp.lifecycle().retired();
        }
        default com.neko.mcbot.body.BodyControl control(Body body) {
            return body.identity() instanceof CompanionPlayer cp ? cp.bodyControl() : null;
        }
        default com.neko.mcbot.body.BodyChangeLog changes(Body body) {
            return body.identity() instanceof CompanionPlayer cp ? cp.bodyChanges() : null;
        }
        default ServerTool.Result environmentFailure(Body body) {
            return body.identity() instanceof CompanionPlayer cp ? cp.environmentFailure() : null;
        }
        CompletableFuture<ServerTool.Result> run(ServerTool tool, Body body, JsonObject args,
                ActionPermissions permissions, ActionPermissions.Context context);
    }

    public ServerToolDispatcher(MinecraftServer server, SummonService summon, ToolRegistry registry) {
        this.server = server;
        this.summon = summon;
        this.registry = registry;
        this.rate = new RateGuard(20, 60);
        this.scheduler = McbotMod.scheduler();
        this.access = new ToolAccess() {
            @Override public Body companion(UUID owner) {
                ServerPlayer player = summon.companionOf(owner);
                if (!(player instanceof CompanionPlayer cp)) return null;
                return new Body(cp.getUUID(), cp.level().dimension().identifier().toString(), cp,
                        cp.blockPosition().getX(), cp.blockPosition().getY(), cp.blockPosition().getZ(),
                        cp.getGameProfile().name());
            }
            @Override public CompletableFuture<ServerTool.Result> run(ServerTool tool, Body body,
                    JsonObject args, ActionPermissions permissions, ActionPermissions.Context context) {
                var companion = (CompanionPlayer) body.identity();
                companion.bindSpatialTask(context);
                return tool.runAuthorized(companion, args, scheduler, permissions, context);
            }
        };
    }

    ServerToolDispatcher(ToolRegistry registry, CompanionScheduler scheduler, RateGuard rate, ToolAccess access) {
        this.server = null;
        this.summon = null;
        this.registry = registry;
        this.scheduler = scheduler;
        this.rate = rate;
        this.access = access;
    }

    public boolean belongsTo(MinecraftServer candidate) {
        return server == candidate;
    }

    public boolean passivePickupAllowed(CompanionPlayer companion) {
        return !companion.lifecycle().retired() && permissions.passivePickupAllowed(companion.ownerUuid(), companion.getUUID(),
                companion.level().dimension().identifier().toString(), companion);
    }

    public boolean passiveBodyActionAllowed(CompanionPlayer companion) {
        return !companion.lifecycle().retired() && permissions.passiveBodyActionAllowed(companion.ownerUuid(), companion.getUUID(),
                companion.level().dimension().identifier().toString(), companion);
    }

    public ActionPermissions.BodyPermit defensePermit(CompanionPlayer companion) {
        if (companion.lifecycle().retired()) return null;
        return permissions.bodyPermit(companion.ownerUuid(), companion.getUUID(),
                companion.level().dimension().identifier().toString(), companion);
    }

    public void retireBody(CompanionPlayer body) {
        permissions.retire(body.ownerUuid(), body);
    }

    public void publishBody(CompanionPlayer body) {
        var owner = server.getPlayerList().getPlayer(body.ownerUuid());
        if (owner == null || summon.companionOf(body.ownerUuid()) != body) return;
        broadcast(new Sender(owner.getUUID(), owner, json ->
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(owner,
                        new McbotPayloads.S2c(json))), "companion_state",
                body.lifecycle().retired() ? "BODY_DIED:同伴原身体已终止；旧目标不会自动续跑。"
                        : "同伴身体已就绪；重新观测后才能开始新任务。");
    }

    public record ReadOnlyVerification(String status, long taskId, String tool, String feedback) {
    }

    public ReadOnlyVerification verifyReadOnly(ServerPlayer owner) {
        return verifyReadOnly(new Sender(owner.getUUID(), owner, ignored -> { }));
    }

    ReadOnlyVerification verifyReadOnly(Sender owner) {
        // An explicit check must never replace the owner's existing task or another body.
        final long id = Long.MAX_VALUE;
        final int seq = -3;
        Body body = companionOfSender(owner);
        if (body == null) return new ReadOnlyVerification("BLOCKED", id, "wait", "先召唤自己名下的同伴。");
        if (permissions.body(owner.owner()) != null || scheduler.busy(body.id()))
            return new ReadOnlyVerification("BLOCKED", id, "wait", "先结束当前任务；验证不会替换正在执行的任务。");

        var receipts = new ArrayList<Envelope>();
        Sender probe = new Sender(owner.owner(), owner.player(), json -> {
            Envelope receipt = Envelope.decode(json);
            if (receipt != null) receipts.add(receipt);
        });
        var scope = new JsonObject();
        scope.addProperty("task_id", id);
        scope.addProperty("read_only", true);
        scope.addProperty("seq", -2);
        scope.addProperty("body_id", access.bodyId(body));
        try {
            handle(probe, new Envelope("task_begin", scope));
            ActionPermissions.Context context = permissions.current(owner.owner(), body.id(),
                    body.dimension(), id, body.identity());
            if (context == null || !context.readOnly())
                return new ReadOnlyVerification("BLOCKED", id, "wait", "只读任务未建立，稍后再试；不能记作权限门通过。");
            var request = new JsonObject();
            request.addProperty("body_id", access.bodyId(body));
            request.addProperty("task_id", id);
            request.addProperty("seq", seq);
            request.addProperty("tool", "wait");
            var args = new JsonObject();
            args.addProperty("seconds", 1);
            request.add("args", args);
            handle(probe, new Envelope("tool_call", request));
        } finally {
            var end = new JsonObject();
            end.addProperty("body_id", access.bodyId(body));
            end.addProperty("task_id", id);
            end.addProperty("seq", -4);
            try {
                handle(probe, new Envelope("task_end", end));
            } finally {
                // Rate limiting may reject task_end; revoke only this check's own context.
                if (permissions.end(owner.owner(), id)) scheduler.cancel(body.id(), "只读验证已结束。");
            }
        }
        for (Envelope receipt : receipts) {
            if (!"tool_result".equals(receipt.kind()) || receipt.num("seq", 0) != seq) continue;
            var denial = receipt.obj("data").get("permission_denial");
            if (!receipt.bool("ok") && denial != null && denial.isJsonObject()) {
                JsonObject detail = denial.getAsJsonObject();
                if ("server_action".equals(detail.get("gate").getAsString())
                        && "READ_ONLY".equals(detail.get("reason").getAsString())
                        && detail.get("task_id").getAsLong() == id
                        && "wait".equals(detail.get("tool").getAsString())
                        && body.dimension().equals(detail.get("dimension").getAsString()))
                    return new ReadOnlyVerification("PASS", id, "wait", receipt.str("feedback"));
            }
            return new ReadOnlyVerification("FAIL", id, "wait", receipt.str("feedback"));
        }
        return new ReadOnlyVerification("FAIL", id, "wait", "未取得可定位的服务端只读拒绝，不能记作通过。");
    }

    /**
     * 三道闸的②③＋执行在这里；闸①（尺寸）在 {@code McbotPayloads.C2s.CODEC} 里已完成，
     * 超限包根本进不到这个方法。
     */
    public void handle(ServerPlayer sender, Envelope env) {
        handle(new Sender(sender.getUUID(), sender, json ->
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(sender,
                        new McbotPayloads.S2c(json))), env);
    }

    void handle(Sender sender, Envelope env) {
        if (!rate.tryAcquire(sender.owner())) {
            replyTool(sender, env.num("seq", -1), false, "DENIED:消息过于频繁，稍后再试。", null);
            return;
        }

        if (java.util.Set.of("task_begin", "task_end", "authorize", "cancel").contains(env.kind())) {
            Body current = companionOfSender(sender);
            if (current != null && !matchesBody(current, env)) return;
            if ("task_begin".equals(env.kind()) && current != null && !access.alive(current)) return;
        }
        switch (env.kind()) {
            case "summon" -> {
                revokeTask(sender);
                String result = summon.summon(sender.player(), env.str("name"));
                broadcast(sender, "summon_result", result);
            }
            case "dismiss" -> {
                revokeTask(sender);
                String result = summon.dismiss(sender.player(), env.str("name"));
                broadcast(sender, "dismiss_result", result);
            }
            case "companion_status" -> {
                Body companion = companionOfSender(sender);
                broadcast(sender, "companion_state", companion == null
                        ? "当前世界尚未召唤伙伴。" : "当前世界伙伴：" + companion.name());
            }
            case "tool_call" -> handleToolCall(sender, env);
            case "task_begin" -> {
                Body cp = companionOfSender(sender);
                Long taskId = taskId(env);
                var mode = env.body().get("read_only");
                var autonomous = env.body().get("autonomous");
                if (cp == null || taskId == null || taskId <= 0 || mode == null
                        || !mode.isJsonPrimitive() || !mode.getAsJsonPrimitive().isBoolean()) return;
                if (autonomous != null && (!autonomous.isJsonPrimitive()
                        || !autonomous.getAsJsonPrimitive().isBoolean())) return;
                scheduler.cancel(cp.id(), "新任务替换原任务。");
                if (cp.identity() instanceof CompanionPlayer player) player.stopBodyBehaviors();
                var nextContext = new ActionPermissions.Context(sender.owner(), cp.id(),
                        cp.dimension(), taskId, mode.getAsBoolean(),
                        autonomous != null && autonomous.getAsBoolean());
                permissions.begin(nextContext, cp.identity());
                if (cp.identity() instanceof CompanionPlayer player) player.newBodyTask();
                if (env.body().has("recovery_spatial")) {
                    try {
                        if (!(cp.identity() instanceof CompanionPlayer player)) throw new IllegalArgumentException();
                        com.neko.mcbot.agentcore.loop.GoalCheckpoint.verifyIdentity(
                                env.body().getAsJsonObject("recovery_identity"), recoveryIdentity(player));
                        SpatialCheckpoint.restore(player, nextContext, env.body().getAsJsonObject("recovery_spatial"));
                    } catch (RuntimeException unavailable) {
                        permissions.end(sender.owner(), taskId);
                        if (cp.identity() instanceof CompanionPlayer player) player.stopBodyBehaviors();
                    }
                }
            }
            case "task_end" -> {
                Long taskId = taskId(env);
                if (taskId == null) return;
                Body cp = companionOfSender(sender);
                Object original = permissions.body(sender.owner());
                if (permissions.end(sender.owner(), taskId)) {
                    if (cp != null && cp.identity() == original) {
                        scheduler.cancel(cp.id(), "所属任务已结束。");
                        if (original instanceof CompanionPlayer player) player.stopBodyBehaviors();
                    }
                }
            }
            case "authorize" -> {
                Body cp = companionOfSender(sender);
                Long taskId = taskId(env);
                ActionPermissions.Context context = cp == null || taskId == null ? null
                        : permissions.current(sender.owner(), cp.id(), cp.dimension(), taskId, cp.identity());
                var tokenValue = env.body().get("authorization_id");
                String token = tokenValue != null && tokenValue.isJsonPrimitive()
                        && tokenValue.getAsJsonPrimitive().isString() && tokenValue.getAsString().length() <= 64
                        ? tokenValue.getAsString() : "";
                boolean granted = permissions.approve(context, token);
                replyTool(sender, env.num("seq", -1), granted, granted
                        ? "主人已批准该任务的具体路线清单；新增或变化的改动仍需重新确认。"
                        : "DENIED:授权已过期、不属于当前任务或任务为只读，请重新查看清单。", null);
            }
            case "cancel" -> {
                Body cp = companionOfSender(sender);
                if (cp == null) {
                    broadcast(sender, "cancel_ack", "你名下没有同伴，无从叫停。");
                } else {
                    boolean stopped = scheduler.cancel(cp.id(), null);
                    if (cp.identity() instanceof CompanionPlayer player) {
                        stopped |= player.defense().active() || player.environmentActive();
                        player.cancelBodyBehaviors();
                    }
                    broadcast(sender, "cancel_ack", stopped
                            ? "已叫停 " + cp.name() + " 手头的活。"
                            : "它现在手头没有进行中的任务。");
                }
                permissions.end(sender.owner(), 0);
            }
            case "answer" -> McbotMod.LOG.info("(M3 暂存)主人对 {} 的回答: {}",
                    env.str("question_id"), env.str("text"));
            default -> McbotMod.LOG.warn("未知信封 kind={} (from {})", env.kind(), sender.owner());
        }
    }

    private void handleToolCall(Sender sender, Envelope env) {
        int seq = env.num("seq", -1);
        String toolName = env.str("tool");

        // 闸③a：白名单
        ServerTool tool = registry.get(toolName);
        if (tool == null) {
            replyTool(sender, seq, false, "DENIED:未知工具 " + toolName, null);
            return;
        }
        // 闸③b：args 必须是 JSON 对象
        if (env.body() == null || !env.body().has("args") || !env.body().get("args").isJsonObject()) {
            replyTool(sender, seq, false, "DENIED:args 必须是 JSON 对象", null);
            return;
        }
        JsonObject args = env.body().getAsJsonObject("args");

        // 闸③c：owner 强校验——只能驱动自己的同伴
        Body companion = companionOfSender(sender);
        if (companion == null) {
            replyTool(sender, seq, false,
                    "DENIED:你没有在册的同伴，先召唤一个（/mcbot summon <名字>）。", null);
            return;
        }
        if (!com.neko.mcbot.agentcore.loop.TaskPolicy.observation(toolName, args)
                && (!matchesBody(companion, env) || !access.alive(companion))) {
            replyTool(sender, seq, false, "BODY_DIED:原身体不可用或已替换，旧动作未派发；先核对新身体。", null);
            return;
        }
        Long taskId = taskId(env);
        String dimension = companion.dimension();
        ActionPermissions.Context context = taskId == null ? null : permissions.current(
                sender.owner(), companion.id(), dimension, taskId, companion.identity());

        // 受理即回执（R2-S4）：**客户端显式要求**才走 ACCEPT。
        // 缺省 false 是刻意的向后兼容——老客户端不认识 job_ack，若服务端擅自改形态，
        // 它那条 tool_call 会一直等到 90 秒超时。所以新增形态永远由发送方点名。
        boolean wantAccept = env.body().has("accept") && env.body().get("accept").isJsonPrimitive()
                && env.body().get("accept").getAsJsonPrimitive().isBoolean() && env.body().get("accept").getAsBoolean()
                && tool.acceptanceMode() == ServerTool.Acceptance.ACCEPT;

        long t0 = System.nanoTime();
        var changes = access.changes(companion);
        var window = new com.neko.mcbot.body.BodyChangeLog.Window[1];
        java.util.function.Supplier<CompletableFuture<ServerTool.Result>> run = () -> {
            if (changes != null && !com.neko.mcbot.agentcore.loop.TaskPolicy.observation(toolName, args))
                window[0] = changes.open();
            return access.run(tool, companion, args, permissions, context);
        };
        CompletableFuture<ServerTool.Result> fut;
        try {
            JsonObject resourceArgs = args.deepCopy();
            if ("collect".equals(toolName) && !args.has("x") && !args.has("y") && !args.has("z")) {
                resourceArgs.addProperty("x", companion.x());
                resourceArgs.addProperty("y", companion.y());
                resourceArgs.addProperty("z", companion.z());
            }
            fut = ServerActionGate.execute(context, companion.id(), dimension, toolName, resourceArgs,
                    scheduler, () -> {
                        var control = access.control(companion);
                        if (com.neko.mcbot.agentcore.loop.TaskPolicy.observation(toolName, args))
                            return run.get();
                        var unsafe = access.environmentFailure(companion);
                        if (unsafe != null) return CompletableFuture.completedFuture(unsafe);
                        if (control == null) return run.get();
                        return scheduler.controlled(companion.id(), control,
                                () -> context != null && context.equals(permissions.current(
                                sender.owner(), companion.id(), companion.dimension(), context.taskId(), companion.identity())),
                                run);
                    });
        } catch (Throwable t) {
            McbotMod.LOG.error("工具 {} 派发异常", toolName, t);
            logToolTiming(toolName, t0, -1);
            var failed = observed(window[0], new ServerTool.Result(false,
                    "INTERNAL:工具内部错误: " + t.getClass().getSimpleName()
                            + "；可能已有部分效果，先核对现场，不能重投。", null));
            replyTool(sender, seq, failed.ok(), failed.feedback(), failed.data());
            return;
        }
        if (fut == null) {
            fut = CompletableFuture.completedFuture(
                    new ServerTool.Result(false, "INTERNAL:工具没有返回 future", null));
        }
        if (window[0] != null) {
            // Project only at the wire boundary. Scheduler cancellation still owns the original future.
            fut = fut.handle((res, err) -> {
                if (err != null) McbotMod.LOG.error("工具 {} 异步失败", toolName, err);
                return observed(window[0], err != null || res == null
                        ? new ServerTool.Result(false, "INTERNAL:工具异常结束，可能已有部分效果；先核对现场，不能重投。", null)
                        : res);
            });
        }

        // 已经出结果了（DENIED/BUSY/TARGET_LOST 这些快路径，或 wait(0) 这类瞬时活）：
        // 直接当同步回执发回去。**"受理"只该给真的还要跑一会儿的活**——先 ack 再立刻报
        // 失败等于白多一跳，而且模型会以为"被打回了"和"跑完了"是两件事。
        if (!wantAccept || fut.isDone()) {
            fut.whenComplete((res, err) -> {
                if (err != null) {
                    // 工具层以异常完成：按原语义交回一条 INTERNAL，别让 owner 白等 90s。
                    McbotMod.LOG.error("工具 {} 异步失败", toolName, err);
                    replyTool(sender, seq, false, "INTERNAL:工具执行失败: "
                            + err.getClass().getSimpleName(), null);
                    logToolTiming(toolName, t0, -1);
                    return;
                }
                logToolTiming(toolName, t0, res == null ? -1 : res.feedback().length());
                if (res == null) replyTool(sender, seq, false, "INTERNAL:工具没有返回结果。", null);
                else replyTool(sender, seq, res.ok(), res.feedback(), res.data());
            });
            return;
        }

        // ACCEPT：先回"我受理了"，再等 future 落地补一条 job_event。
        // **单终局契约**：这个 seq 从此只走 job 通道，绝不会再有 tool_result——
        // 客户端收到 job_ack 就把 seq 那条待办转成 job 待办，两条都发会让它记两次账。
        String jobId = "j" + jobSeq.incrementAndGet();
        int cap = tool.capTicks(args);
        replyAck(sender, seq, jobId, toolName, acceptText(tool, args, jobId, cap), cap);
        McbotMod.LOG.info("[brain] job {} 受理 {} seq={} 帽={}tick", jobId, toolName, seq, cap);
        fut.whenComplete((res, err) -> {
            String phase;
            String text;
            JsonObject data = null;
            if (err != null) {
                McbotMod.LOG.error("工具 {} 受理后异步失败 job={}", toolName, jobId, err);
                phase = "failed";
                text = "INTERNAL:工具执行失败: " + err.getClass().getSimpleName();
            } else if (res == null) {
                phase = "failed";
                text = "INTERNAL:工具没有返回结果";
            } else {
                phase = phaseOf(res.ok(), res.feedback());
                text = res.feedback();
                data = res.data();
            }
            logToolTiming(toolName, t0, text == null ? -1 : text.length());
            McbotMod.LOG.info("[brain] job {} 结束 {} phase={}", jobId, toolName, phase);
            replyJobEvent(sender, seq, jobId, toolName, phase, text, data);
        });
    }

    private final java.util.concurrent.atomic.AtomicLong jobSeq = new java.util.concurrent.atomic.AtomicLong();

    private JsonObject recoveryIdentity(CompanionPlayer player) {
        var data = new JsonObject();
        var records = McbotMod.buildRecords(player.level().getServer());
        if (records == null || !records.healthy()) throw new IllegalArgumentException();
        data.addProperty("world_id", records.worldId().toString());
        data.addProperty("owner_uuid", player.ownerUuid().toString());
        data.addProperty("companion_uuid", player.getUUID().toString());
        data.addProperty("body_continuity_id", player.bodyContinuityId().toString());
        data.addProperty("body_id", player.observationBodyId().toString());
        data.addProperty("dimension", player.level().dimension().identifier().toString());
        data.addProperty("alive", player.isAlive() && !player.lifecycle().retired());
        return data;
    }

    private static Long taskId(Envelope env) {
        var value = env.body().get("task_id");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        try { return value.getAsBigDecimal().longValueExact(); }
        catch (RuntimeException invalid) { return null; }
    }

    private void revokeTask(Sender owner) {
        Object original = permissions.body(owner.owner());
        permissions.end(owner.owner(), 0);
        if (original instanceof CompanionPlayer player) {
            scheduler.cancel(player.getUUID(), "同伴生命周期操作撤销旧任务。");
            player.cancelBodyBehaviors();
        } else {
            Body cp = companionOfSender(owner);
            if (cp != null && cp.identity() instanceof CompanionPlayer player) player.cancelBodyBehaviors();
            if (cp != null && cp.identity() == original)
                scheduler.cancel(cp.id(), "同伴生命周期操作撤销旧任务。");
        }
    }

    /**
     * 受理回执的教学文案（**模板只此一处**）。
     *
     * <p>要教模型三件事：①这条还没有结果，别把它当结果；②别干等——它既不能改主意也不能
     * 催，轮询只是烧一次 prefill+decode；③做完会**主动**报出编号，所以"我该不该再问一次"
     * 这个问题根本不用问。这三句每个工具一字不差，模型才学一遍就够。
     *
     * <p>文本前缀是契约（{@code ACCEPTED:}），机器可读信号在回执的 outcome 字段里——
     * 用文本做控制流，任何一个工具的回执恰好这么开头就会误判。
     */
    public static String acceptText(ServerTool tool, JsonObject args, String jobId, int capTicks) {
        return com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome.ACCEPTED_PREFIX
                + "我已开始「" + tool.acceptSubject(args) + "」编号 " + jobId
                + (capTicks == com.neko.mcbot.agentcore.loop.PendingJobs.UNLIMITED_CAP_TICKS
                ? "，不设总时长上限，可由主人取消。" : "，最多约 " + Math.max(1, capTicks / 20) + " 秒。")
                + "这条还没有结果，别猜、别重发或轮询；后续工具等这件事结束，"
                + "做完我会主动报 " + jobId + "。";
    }

    /**
     * 终局相位。删掉前缀那一层包装，让模型看到的是**干净的结果或教学**，
     * 而相位是给客户端/面板做状态用的（{@code cancelled}/{@code superseded} 要能区分，
     * 因为前者是主人叫停、后者是被新指令顶掉，处理方式不同）。
     */
    public static String phaseOf(boolean ok, String feedback) {
        if (feedback != null) {
            if (feedback.startsWith("CANCELLED:")) {
                return "cancelled";
            }
            if (feedback.startsWith("SUPERSEDED:")) {
                return "superseded";
            }
        }
        return ok ? "done" : "failed";
    }

    /**
     * 每工具耗时归因（效率评估 §5.5）。
     *
     * <p>在这条日志之前，"模型慢"和"工具慢"是混在同一个总时长里的——`[m4]` 验收虽然打印过
     * "break_block 完成"，但那是自由文本、事后无法统计。有了这一行，
     * 真机曲线就能把每一秒归到具体工具上（例：`move_to` 慢到底慢在搜索还是走路，
     * 再看 `[path]` 的 expanded/elapsed 细分）。
     *
     * @param chars 回执字符数（-1 = 未拿到回执）；回执越长，模型那侧 prefill 越贵，
     *              所以它和时间一起记，才能解释"工具很快但这轮还是很慢"
     */
    private static void logToolTiming(String toolName, long t0, int chars) {
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        McbotMod.LOG.info("[brain] tool {} {}ms chars={}", toolName, ms, chars);
    }

    private Body companionOfSender(Sender sender) {
        return access.companion(sender.owner());
    }

    private boolean matchesBody(Body body, Envelope env) {
        String expected = access.bodyId(body);
        if (expected.isEmpty()) return true;
        var value = env.body().get("body_id");
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                && expected.equals(value.getAsString());
    }

    private void replyTool(Sender owner, int seq, boolean ok, String feedback, JsonObject data) {
        JsonObject body = new JsonObject();
        body.addProperty("seq", seq);
        body.addProperty("ok", ok);
        body.addProperty("feedback", feedback);
        if (data != null) {
            body.add("data", data);
        }
        send(owner, "tool_result", body);
    }

    private static ServerTool.Result observed(com.neko.mcbot.body.BodyChangeLog.Window window,
                                              ServerTool.Result result) {
        if (window == null) return result;
        try {
            return window.finish(result);
        } catch (RuntimeException failure) {
            McbotMod.LOG.warn("身体差量采样不可用", failure);
            var data = result.data() == null ? new JsonObject() : result.data().deepCopy();
            var delta = new JsonObject();
            delta.addProperty("observation_known", false);
            data.add("body_delta", delta);
            return new ServerTool.Result(result.ok(), result.feedback()
                    + "\n身体变化未能完整观察，不能据此断言没有副作用。", data);
        }
    }

    /** 受理回执：seq 这条待办就此定性（转成 job 通道），所以它自己就是一个"终局"。 */
    private void replyAck(Sender owner, int seq, String jobId, String tool, String text, int capTicks) {
        JsonObject body = new JsonObject();
        body.addProperty("seq", seq);
        body.addProperty("job_id", jobId);
        body.addProperty("tool", tool);
        body.addProperty("text", text);
        // cap 由服务端报出、客户端据此算等待上限：客户端的超时必须**严格大于**它，
        // 否则"客户端先跑了"会被误判成"服务端超时"（效率评估 §5.5 那条真缺陷的根因）。
        body.addProperty("cap_ticks", capTicks);
        send(owner, "job_ack", body);
    }

    /** 长活的后续：progress / done / failed / cancelled / superseded。 */
    private void replyJobEvent(Sender owner, int seq, String jobId, String tool,
                               String phase, String text, JsonObject data) {
        JsonObject body = new JsonObject();
        body.addProperty("seq", seq);
        body.addProperty("job_id", jobId);
        body.addProperty("tool", tool);
        body.addProperty("phase", phase);
        body.addProperty("text", text);
        if (data != null) {
            body.add("data", data);
        }
        send(owner, "job_event", body);
    }

    private void broadcast(Sender owner, String kind, Object payload) {
        JsonObject body = new JsonObject();
        if (payload instanceof Integer i) {
            body.addProperty("v", i);
        } else {
            body.addProperty("text", String.valueOf(payload));
        }
        if ("summon_result".equals(kind) || "dismiss_result".equals(kind)
                || "companion_state".equals(kind)) {
            Body companion = companionOfSender(owner);
            body.addProperty("companion", companion == null ? "" : companion.name());
            body.addProperty("companion_uuid", companion == null ? "" : companion.id().toString());
            if (companion != null && !access.bodyId(companion).isEmpty()) {
                body.addProperty("body_id", access.bodyId(companion));
                body.addProperty("body_state", access.alive(companion) ? "alive" : "dead");
                if (companion.identity() instanceof CompanionPlayer cp) {
                    body.add("body_lifecycle", cp.lifecycle().observation());
                    try {
                        var identity = recoveryIdentity(cp);
                        for (String key : java.util.List.of("world_id", "owner_uuid", "body_continuity_id", "dimension"))
                            body.add(key, identity.get(key));
                    } catch (RuntimeException unavailable) { }
                }
            }
        }
        send(owner, kind, body);
    }

    private void send(Sender owner, String kind, JsonObject body) {
        String json = new Envelope(kind, body).encode();
        // 闸①（出站）：按 UTF-8 字节量，不是字符数。旧写法用 json.length() 比较 32*1024，
        // 而本项目的回执几乎全是中文（一字三字节）：那等于把上限抬到了 ~96KB，
        // 正好越过原版入站 32767 字符/98301 字节的红线。
        int bytes = WireSize.utf8Bytes(json);
        if (bytes > WireSize.MAX_BODY_BYTES && ("tool_result".equals(kind) || "job_event".equals(kind))
                && body.has("data") && body.get("data").isJsonObject()
                && body.getAsJsonObject("data").has("path_timing")) {
            // The complete route timing is already logged; it must not displace real action evidence.
            body = body.deepCopy();
            body.getAsJsonObject("data").remove("path_timing");
            json = new Envelope(kind, body).encode();
            bytes = WireSize.utf8Bytes(json);
        }
        if (bytes > WireSize.MAX_BODY_BYTES) {
            McbotMod.LOG.warn("S2C 信封超限({}B > {}B)，改发瘦身回执 kind={}",
                    bytes, WireSize.MAX_BODY_BYTES, kind);
            json = shrink(kind, body, bytes);
        }
        McbotMod.LOG.info("[m3] S2C -> {} {} ({}B)",
                owner.player() == null ? owner.owner() : owner.player().getGameProfile().name(), kind,
                WireSize.utf8Bytes(json));
        owner.output().accept(json);
    }

    /**
     * 超限回执的堆：不是“截断到 32KB”，而是换一条合法的小信封。
     *
     * <p>为什么不截断：JSON 中途铲断只会让对端 {@code Envelope.decode} 解不动而静默丢弃，
     * 于是那个 {@code seq} 永远等不到回执，大脑要白等 90 秒才吐一条 TIMEOUT 教学——
     * 尺寸闸反而造成了一次难查的“卡住”。换成合法信封，并且**保留 seq**，
     * 才能立刻把“回执太大、换个小范请求”这件事教给模型。
     */
    private static String shrink(String kind, JsonObject body, int originalBytes) {
        JsonObject small = new JsonObject();
        if (body != null && body.has("seq")) {
            small.addProperty("seq", body.get("seq").getAsInt());
        }
        if (body != null && body.has("task_id")) {
            small.addProperty("task_id", body.get("task_id").getAsInt());
        }
        // R2-S4：job 通道的两条信封都要带上关联键，否则瘦身之后客户端认不出是哪条活——
        // 那会变成"PARK 永远等不到解锁"的假死（比超限本身更难查）。
        if (body != null && body.has("job_id")) {
            small.addProperty("job_id", body.get("job_id").getAsString());
        }
        if (body != null && body.has("phase")) {
            small.addProperty("phase", body.get("phase").getAsString());
        }
        if (body != null && body.has("cap_ticks")) {
            small.addProperty("cap_ticks", body.get("cap_ticks").getAsInt());
        }
        small.addProperty("ok", false);
        if ("job_ack".equals(kind)) {
            // 瘦身版受理回执：仍然必须以 ACCEPTED: 开头，否则模型会把它当失败去重试。
            small.addProperty("text", com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome
                    .ACCEPTED_PREFIX + "我受理了这件事（详细文案过大已省略），做完我会主动报 "
                    + (body != null && body.has("job_id") ? body.get("job_id").getAsString() : "") + "。");
            return new Envelope(kind, small).encode();
        }
        var kept = new JsonObject();
        kept.addProperty("receipt_truncated", true);
        if (body != null && body.has("data") && body.get("data").isJsonObject()) {
            var original = body.getAsJsonObject("data");
            var delta = original.get("body_delta");
            if (delta != null && delta.isJsonObject()
                    && WireSize.utf8Bytes(delta.toString()) <= com.neko.mcbot.body.BodyChangeLog.MAX_BYTES)
                kept.add("body_delta", delta.deepCopy());
            if (original.has("drop_accounting_known")) {
                kept.addProperty("drop_accounting_known", false);
                kept.addProperty("drop_spawns_complete", false);
                var origin = original.get("drop_accounting");
                if (origin != null && origin.isJsonObject()) {
                    var summary = origin.getAsJsonObject().deepCopy();
                    summary.remove("spawns"); summary.remove("events"); summary.remove("live_drops");
                    summary.addProperty("details_omitted", true);
                    if (WireSize.utf8Bytes(summary.toString()) <= 2048) {
                        kept.add("drop_accounting", summary);
                        kept.addProperty("drop_accounting_known", original.get("drop_accounting_known").getAsBoolean());
                    }
                }
            }
            for (String key : java.util.List.of("removed", "changed", "cleared_blocks", "removed_blocks",
                    "placed_blocks", "collected_count", "crafted_count", "loaded_input_count",
                    "loaded_fuel_count", "taken_count", "moved_items", "strikes",
                    "observed_health_loss", "observed_absorption_loss", "observation_known", "recording_known")) {
                var value = original.get(key);
                if (value != null && value.isJsonPrimitive()
                        && (value.getAsJsonPrimitive().isBoolean() || value.getAsJsonPrimitive().isNumber()))
                    kept.add(key, value.deepCopy());
            }
        }
        small.add("data", kept);
        String originalPhase = body != null && body.has("phase") ? body.get("phase").getAsString() : "";
        String prefix = "cancelled".equals(originalPhase) ? "CANCELLED:"
                : "superseded".equals(originalPhase) ? "SUPERSEDED:" : "INTERNAL:";
        if (body != null && body.has("feedback")) {
            String feedback = body.get("feedback").getAsString();
            if (feedback.startsWith("CANCELLED:")) prefix = "CANCELLED:";
            else if (feedback.startsWith("SUPERSEDED:")) prefix = "SUPERSEDED:";
        }
        String text = prefix + "回执过大（" + originalBytes
                + "B，上限 " + WireSize.MAX_BODY_BYTES + "B），详细数据已丢弃。"
                + "已发生效果保留，可能还有未列出的副作用；先查状态和现场，不要重投修改动作。";
        if (kept.has("body_delta")) text += com.neko.mcbot.body.BodyChangeLog.sentence(
                kept.getAsJsonObject("body_delta"));
        small.addProperty("feedback", text);
        if ("job_event".equals(kind)) {
            small.addProperty("phase", java.util.List.of("cancelled", "superseded").contains(originalPhase)
                    ? originalPhase : "failed");
            small.addProperty("text", text);
        }
        return new Envelope(kind, small).encode();
    }
}
