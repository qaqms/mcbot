package com.neko.mcbot.agent;

import com.google.gson.JsonObject;
import com.neko.mcbot.agentcore.llm.LlmClient;
import com.neko.mcbot.agentcore.loop.AgentLoop;
import com.neko.mcbot.agentcore.loop.ToolExecutor;
import com.neko.mcbot.agentcore.prompt.PromptBuilder;
import com.neko.mcbot.agentcore.prompt.SkillLoader;
import com.neko.mcbot.bridge.BridgeEvents;
import com.neko.mcbot.cfg.ClientConfig;
import com.neko.mcbot.common.Envelope;
import com.neko.mcbot.common.McbotPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 大脑宿主：AgentLoop + "工具调用→C2S payload→等 S2C tool_result"执行器。
 * 只跑客户端侧（LLM 异步、配置带 key）；世界操作永远发生在服务器。
 * transcript 是面板的只读回看缓冲；同时是桥接（M6）的事件生产者。
 */
public final class AgentRunner implements ToolExecutor {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("mcbot/agent");
    private static final long TOOL_TIMEOUT_MS = 90_000;
    // 反问等待从 5min 降到 2min：配合游戏内 `@bot 答 <文本>` 入口，没人理就快醒。
    private static final long QUESTION_TIMEOUT_MS = 120_000;
    private static final int TRANSCRIPT_CAP = 80;

    private ClientConfig cfg;
    private final AtomicLong seqGen = new AtomicLong();
    private final AtomicLong taskSeq = new AtomicLong();
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    /** mcbot_ask 排队等作答的桥（onReply 按序喂给最早的问题）。 */
    private final ArrayDeque<CompletableFuture<String>> askWaiters = new ArrayDeque<>();
    private final ArrayDeque<String> transcript = new ArrayDeque<>();
    private volatile long currentTask;
    private volatile String companionName = "";
    private AgentLoop loop;

    private record Pending(CompletableFuture<ToolOutcome> future, long sentAtMs, long timeoutMs) {
    }

    public AgentRunner(ClientConfig cfg) {
        this.cfg = cfg;
    }

    /** 进世界后调用一次；重复调用安全。 */
    public void start() {
        if (!cfg.brainEnabled || loop != null) {
            return;
        }
        var engine = new LlmClient(
                new com.neko.mcbot.agentcore.provider.OpenAiCompatProvider(
                        "mcbot", cfg.baseUrl, cfg.apiKey, cfg.model),
                java.time.Duration.ofSeconds(180));
        loop = new AgentLoop(engine, ClientToolDefs.SPECS, this, AgentLoop.Config.defaults(),
                new AgentLoop.Listener() {
                    @Override
                    public void onReply(String text) {
                        say("§b[同伴] §r" + text);
                        synchronized (askWaiters) {
                            if (!askWaiters.isEmpty()) {
                                askWaiters.pollFirst().complete(text);
                            }
                        }
                        JsonObject d = new JsonObject();
                        d.addProperty("text", text);
                        emit("done", d);
                    }

                    @Override
                    public void onToolInvoked(String name, String argsJson, boolean ok, String feedback) {
                        LOG.info("tool {} -> {} {}", name, ok ? "✔" : "✘", feedback);
                        JsonObject d = new JsonObject();
                        d.addProperty("text", name + (ok ? " ✔ " : " ✘ ") + feedback);
                        d.addProperty("tool", name);
                        d.addProperty("ok", ok);
                        emit("progress", d);
                    }

                    @Override
                    public void onNotice(String text) {
                        LOG.info("loop notice: {}", text);
                        JsonObject d = new JsonObject();
                        d.addProperty("text", "（护栏）" + text);
                        emit("progress", d);
                    }

                    @Override
                    public void onUsage(long prompt, long completion, long cached) {
                        // M4.5 可观测：每步真实上下文体量。cached<0=后端没报；
                        // prompt 持续涨而 cached 常年 0/缺失 → 前缀缓存没吃到，该查压缩/剪枝。
                        LOG.info("[brain] step tokens prompt={} completion={} cached={}",
                                prompt, completion, cached);
                    }
                },
                () -> PromptBuilder.build(cfg.persona, SkillLoader.load(
                        FabricLoader.getInstance().getGameDir()
                                .resolve("mcbot").resolve("skills"))),
                // 压缩闸门：6000 真 token（API 数优先，本地 CJK 感知估算兜底）。
                // 参考实测：固定开销≈350，每步只追加；这个数给中转站通道留了延迟余地。
                6_000);
        LOG.info("大脑已上线：{} / {}", cfg.baseUrl, cfg.model);
    }

    /** 面板"保存并应用"：落盘配置并重建大脑（对话历史清零属预期；悬着的问题就地终止）。 */
    public void reconfigure(ClientConfig newCfg) {
        ClientConfig.save(newCfg);
        cfg = newCfg;
        loop = null;
        for (var q : questionRecords.values()) {
            q.future.complete(new ToolOutcome(false, "INTERNAL:大脑热重启，这个问题作废。"));
        }
        questionRecords.clear();
        synchronized (askWaiters) {
            for (var f : askWaiters) {
                f.completeExceptionally(new IllegalStateException("大脑热重启"));
            }
            askWaiters.clear();
        }
        start();
        if (newCfg.brainEnabled) {
            say("§a[mcbot] 配置已保存，大脑已重启：" + newCfg.model + "§r");
        } else {
            say("§c[mcbot] 配置不完整（需要 base_url/model/api_key），大脑未启动。§r");
        }
    }

    public ClientConfig config() {
        return cfg;
    }

    public List<String> transcriptSnapshot() {
        synchronized (transcript) {
            return new ArrayList<>(transcript);
        }
    }

    public void handleS2c(Envelope env) {
        switch (env.kind()) {
            case "tool_result" -> {
                long seq = env.num("seq", -1);
                Pending p = pending.remove(seq);
                if (p != null) {
                    p.future().complete(new ToolOutcome(env.bool("ok"), env.str("feedback")));
                }
            }
            case "event" -> {
                say("§7[同伴] " + env.str("text") + "§r");
                emitState(env.str("text"));
            }
            default -> {
                String text = env.body().has("text") ? env.str("text") : env.kind();
                say("§7[mcbot] " + text + "§r");
                var m = java.util.regex.Pattern.compile("同伴 (\\w+) 出现了").matcher(text);
                if (m.find()) {
                    companionName = m.group(1);
                } else if (text.contains("离开了")) {
                    companionName = "";
                }
                emitState(text);
            }
        }
    }

    /** 面板召唤/遣散按钮：与 /mcbot 命令同权限（服务器按发送者校验 owner）。 */
    public void sendLifecycle(String kind, String name) {
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        ClientPlayNetworking.send(new McbotPayloads.C2s(new Envelope(kind, body).encode()));
    }

    /** 主线程周期调用：工具/问题超时兜底。 */
    public void tick() {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(e -> {
            if (now - e.getValue().sentAtMs() > e.getValue().timeoutMs()) {
                e.getValue().future().complete(new ToolOutcome(false,
                        "TIMEOUT:这次操作 " + (e.getValue().timeoutMs() / 1000)
                                + " 秒没有结果，先别重复这个操作，向主人说明情况。"));
                return true;
            }
            return false;
        });
        sweepQuestionTimeouts();
    }

    /** 同伴正在等主人回答的最新反问（游戏内 `@bot 答 …` 入口用）。 */
    private volatile Long latestQuestion;

    private final class QuestionRecord {
        final CompletableFuture<ToolOutcome> future;
        final long at;

        QuestionRecord(CompletableFuture<ToolOutcome> f) {
            this.future = f;
            this.at = System.currentTimeMillis();
        }
    }

    private static final java.util.regex.Pattern CANCEL_WORDS = java.util.regex.Pattern
            .compile("(停|停下|停手|取消|别干了|别做了|cancel|stop)", java.util.regex.Pattern.CASE_INSENSITIVE);

    // 游戏内回答同伴反问："答 <文本>" 或 "answer <文本>"
    private static final java.util.regex.Pattern ANSWER_DIRECTIVE = java.util.regex.Pattern
            .compile("^(答|answer)[：: ]+(.+)$", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

    /** 主人指令统一入口（聊天/桥共用）。返回分配的 task_id。 */
    public long submitTask(String text) {
        long id = taskSeq.incrementAndGet();
        currentTask = id;
        record("§9我> §r" + text);
        if (loop == null) {
            say("§c[mcbot] 大脑未配置：打开面板（默认 G）填 base_url/model/api_key 后保存。§r");
            emitState("大脑未配置，指令未执行");
            return id;
        }
        loop.submit(text);
        return id;
    }

    public void onOwnerDirective(String text) {
        if (CANCEL_WORDS.matcher(text.trim()).matches()) {
            record("§9我> §r" + text);
            requestCancel();
            return;
        }
        var ans = ANSWER_DIRECTIVE.matcher(text.trim());
        if (ans.matches()) {
            Long qid = latestQuestion;
            if (qid != null && answerQuestion("q" + qid, ans.group(2).trim())) {
                record("§9我> §r" + text);
                return;
            }
            // 没有在等的问题：当普通指令走，但提醒一句免得主人以为回答了空气
            say("§7[mcbot] 同伴现在没有在等回答的问题，这句当新指令处理了。§r");
        }
        submitTask(text);
    }

    /** 叫停：服务端中止进行中的任务（回执会作为 CANCELLED 流回大脑），本地链在下一个边界停。 */
    public void requestCancel() {
        if (ClientPlayNetworking.canSend(McbotPayloads.C2s.TYPE)) {
            ClientPlayNetworking.send(
                    new McbotPayloads.C2s(new Envelope("cancel", new JsonObject()).encode()));
        }
        if (loop != null) {
            loop.cancelDirective();
        }
        emitState("主人叫停了当前任务");
    }

    /** 桥接 mcbot_ask：排队等大脑的下一次作答。 */
    public CompletableFuture<String> askNext(String text) {
        CompletableFuture<String> f = new CompletableFuture<>();
        synchronized (askWaiters) {
            askWaiters.addLast(f);
        }
        submitTask(text);
        return f;
    }

    /** 回答同伴的 ask_owner 反问。qid 形如 "q12"。 */
    public boolean answerQuestion(String qid, String text) {
        if (qid == null || !qid.startsWith("q")) {
            return false;
        }
        try {
            long seq = Long.parseLong(qid.substring(1));
            QuestionRecord rec = questionRecords.remove(seq);
            if (rec == null) {
                return false;
            }
            if (latestQuestion != null && latestQuestion == seq) {
                latestQuestion = null;
            }
            rec.future.complete(new ToolOutcome(true, "主人说：" + text));
            JsonObject d = new JsonObject();
            d.addProperty("text", "主人已回答 " + qid);
            emit("state", d);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private final Map<Long, QuestionRecord> questionRecords = new ConcurrentHashMap<>();

    /** 桥状态探针（GET /v1/status）。字段全部 volatile/并发安全，可跨线程读。 */
    public String statusJson() {
        JsonObject o = new JsonObject();
        o.addProperty("in_game", Minecraft.getInstance().level != null);
        o.addProperty("brain_enabled", cfg != null && cfg.brainEnabled);
        o.addProperty("model", cfg == null ? "" : cfg.model);
        o.addProperty("companion", companionName);
        o.addProperty("current_task", currentTask);
        o.addProperty("pending_tools", pending.size());
        o.addProperty("pending_questions", questionRecords.size());
        return o.toString();
    }

    @Override
    public CompletableFuture<ToolOutcome> execute(String name, String argsJson) {
        if ("ask_owner".equals(name)) {
            return askOwner(argsJson);
        }
        if (!ClientPlayNetworking.canSend(McbotPayloads.C2s.TYPE)) {
            return CompletableFuture.completedFuture(
                    new ToolOutcome(false, "DENIED:尚未连上装有 mcbot 的服务器。"));
        }
        long seq = seqGen.incrementAndGet();
        JsonObject body = new JsonObject();
        body.addProperty("seq", (int) seq);
        body.addProperty("tool", name);
        try {
            body.add("args", com.google.gson.JsonParser.parseString(
                    argsJson == null || argsJson.isBlank() ? "{}" : argsJson));
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(
                    new ToolOutcome(false, "INTERNAL:模型给出的参数不是合法 JSON。"));
        }
        CompletableFuture<ToolOutcome> f = new CompletableFuture<>();
        pending.put(seq, new Pending(f, System.currentTimeMillis(), TOOL_TIMEOUT_MS));
        ClientPlayNetworking.send(new McbotPayloads.C2s(new Envelope("tool_call", body).encode()));
        return f;
    }

    /** 本地工具：问题进事件流（question 帧），答案从 answerQuestion 回来；5 分钟无回复超时。 */
    private CompletableFuture<ToolOutcome> askOwner(String argsJson) {
        long seq = seqGen.incrementAndGet();
        CompletableFuture<ToolOutcome> f = new CompletableFuture<>();
        questionRecords.put(seq, new QuestionRecord(f));
        String text;
        try {
            var el = com.google.gson.JsonParser.parseString(
                    argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            text = el.isJsonObject() && el.getAsJsonObject().has("text")
                    ? el.getAsJsonObject().get("text").getAsString() : String.valueOf(argsJson);
        } catch (RuntimeException e) {
            text = String.valueOf(argsJson);
        }
        say("§d[同伴想问] §r" + text + " §7（回答：@bot 答 <文本>）§r");
        latestQuestion = seq;
        JsonObject d = new JsonObject();
        d.addProperty("question_id", "q" + seq);
        d.addProperty("text", text);
        emit("question", d);
        return f;
    }

    /** 问题超时巡检（客户端主线程 tick 里调）。 */
    private void sweepQuestionTimeouts() {
        long now = System.currentTimeMillis();
        questionRecords.entrySet().removeIf(e -> {
            if (now - e.getValue().at > QUESTION_TIMEOUT_MS) {
                if (latestQuestion != null && latestQuestion == e.getKey()) {
                    latestQuestion = null; // 超时作废，别留悬指针
                }
                e.getValue().future.complete(new ToolOutcome(false,
                        "TIMEOUT:主人 2 分钟没回你的问题。按最稳妥的理解自行定夺，或向主人说明你在等什么。"));
                return true;
            }
            return false;
        });
    }

    private void emitState(String text) {
        JsonObject d = new JsonObject();
        d.addProperty("text", text);
        emit("state", d);
    }

    private void emit(String kind, JsonObject data) {
        data.addProperty("ev", kind);
        data.addProperty("task_id", currentTask);
        BridgeEvents.publish(kind, data);
    }

    private void say(String text) {
        record(text);
        // 模型偶尔把换行写成字面量 \n，聊天显示前归一化
        String clean = stripCodes(text).replace("\\r", "").replace("\\n", "\n");
        // loop 回调运行在 HttpClient 线程上；GUI 一律弹回渲染线程（否则 Rendersystem 炸）
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.gui != null) {
                mc.gui.getChat().addMessage(Component.literal(clean));
            }
        });
    }

    private void record(String text) {
        synchronized (transcript) {
            transcript.addLast(text);
            while (transcript.size() > TRANSCRIPT_CAP) {
                transcript.removeFirst();
            }
        }
    }

    private static String stripCodes(String s) {
        return s.replaceAll("§.", "");
    }
}
