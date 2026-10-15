package com.neko.mcbot.ui;

import com.google.gson.JsonObject;
import com.neko.mcbot.McbotClient;
import com.neko.mcbot.agent.AgentRunner;
import com.neko.mcbot.agent.PanelHistory;
import com.neko.mcbot.agentcore.llm.LlmClient;
import com.neko.mcbot.agentcore.llm.LlmFailure;
import com.neko.mcbot.agentcore.llm.ModelConnectionTest;
import com.neko.mcbot.agentcore.provider.OpenAiCompatProvider;
import com.neko.mcbot.bridge.BridgeHttp;
import com.neko.mcbot.cfg.ClientConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Quiet conversation-first G panel. Game state always comes from the current runner. */
public final class McbotPanelScreen extends Screen {
    private enum View { CHAT, COMPANION, SETTINGS }
    private enum Setting { MODEL, CONTEXT, GENERAL }
    private record Control(AbstractWidget widget, PanelLayout.Rect rect) {}
    private record Text(String text, int x, int y, int width, int color) {}
    private record Bubble(PanelLayout.Rect rect) {}
    private record Slot(PanelLayout.Rect rect, JsonObject data, boolean selected, String label) {}

    private View view = View.CHAT;
    private Setting setting = Setting.MODEL;
    private PanelLayout layout;
    private PanelLayout.Rect viewport;
    private AgentRunner boundRunner;
    private final List<Control> scrolling = new ArrayList<>();
    private final List<Text> texts = new ArrayList<>();
    private final List<Bubble> bubbles = new ArrayList<>();
    private final List<Slot> slots = new ArrayList<>();
    private final Set<Long> expandedTools = new HashSet<>();
    private EditBox baseUrl, model, apiKey, summonName, windowTokens, threshold, recentBudget;
    private MultiLineEditBox persona, chatInput;
    private ClientConfig.Preferences preferences;
    private boolean advanced, testing, compacting, draggingScroll, draftBound;
    private boolean fieldDark;
    private String draftQuestionId, signature = "", message = "", connectionResult = "", itemInfo = "";
    private String readCompanionName = "";
    private long messageUntil, selectedTask;
    private int formScroll, transcriptScroll, contentHeight, scrollGrab;
    private CompletableFuture<Boolean> connectionTest;
    private PanelButton send;

    public McbotPanelScreen() { super(Component.literal("mcbot · 同伴面板")); }
    private AgentRunner runner() { return McbotClient.runner(); }
    private boolean dark() { return preferences != null && preferences.dark(); }
    private int ink() { return dark() ? 0xFFE3E7E4 : 0xFF272C2D; }
    private int muted() { return dark() ? 0xFF9BA59E : 0xFF7A8385; }
    private int line() { return dark() ? 0xFF353C37 : 0xFFE9EDED; }
    private int soft() { return dark() ? 0xFF2A2E2C : 0xFFF4F5F5; }
    private String companion() {
        return runner() == null || runner().companionName().isEmpty() ? "同伴" : runner().companionName();
    }
    private boolean historical() { return selectedTask != 0; }
    private AgentRunner.QuestionSnapshot question() { return runner() == null ? null : runner().questionSnapshot(); }

    @Override protected void init() {
        layout = PanelLayout.at(width, height);
        viewport = layout.body();
        boundRunner = runner();
        ClientConfig cfg = boundRunner == null ? new ClientConfig("", "", "", "") : boundRunner.config();
        if (preferences == null) preferences = cfg.preferences;
        scrolling.clear(); texts.clear(); bubbles.clear(); slots.clear();
        if (baseUrl == null) {
            baseUrl = PanelFields.create(font, "API 地址", cfg.baseUrl, 2048, false);
            model = PanelFields.create(font, "模型名称", cfg.model, 1024, false);
            apiKey = PanelFields.create(font, "API 密钥", cfg.apiKey, 4096, true);
            summonName = PanelFields.create(font, "同伴名称", cfg == null || companion().equals("同伴") ? "aimi" : companion(), 16, false);
            windowTokens = numeric("模型窗口", cfg.preferences.contextWindow());
            threshold = numeric("整理阈值", cfg.preferences.compactThreshold());
            recentBudget = numeric("近期保留量", cfg.preferences.recentBudget());
        }
        for (var field : List.of(baseUrl, model, apiKey, summonName, windowTokens, threshold, recentBudget)) {
            field.setTextColor(ink());
            field.setTextColorUneditable(muted());
        }
        int personaWidth = Math.min(400, viewport.width() - 8), personaHeight = Math.min(54, viewport.height());
        if (persona == null || persona.getWidth() != personaWidth || persona.getHeight() != Math.max(12, personaHeight)
                || fieldDark != dark()) {
            persona = multiline("人设", persona == null ? cfg.persona : persona.getValue(), personaWidth, personaHeight, 4096);
        }
        int chatWidth = Math.max(20, layout.input().width() - 42);
        if (chatInput == null || chatInput.getWidth() != chatWidth || fieldDark != dark()) {
            chatInput = multiline("输入消息…", chatInput == null ? "" : chatInput.getValue(), chatWidth, 28, 4096);
        }
        fieldDark = dark();
        chatInput.setValueListener(value -> {
            if (value.isEmpty()) { draftBound = false; draftQuestionId = null; }
            else if (!draftBound) {
                var q = question();
                draftQuestionId = q == null ? null : q.id();
                draftBound = true;
            }
        });
        String[] navigation = {"对话", "同伴", "设置"};
        for (int i = 0; i < 3; i++) {
            View target = View.values()[i];
            button(navigation[i], layout.nav(i), view == target ? PanelButton.Tone.SELECTED : PanelButton.Tone.QUIET,
                    () -> changeView(target));
        }
        var header = layout.header();
        button("×", new PanelLayout.Rect(header.right() - 20, header.y() + 3, 20, 20),
                PanelButton.Tone.QUIET, this::onClose)
                .setTooltip(Tooltip.create(Component.literal("关闭面板")));
        button("停止", new PanelLayout.Rect(header.right() - 58, header.y() + 3, 32, 20),
                PanelButton.Tone.QUIET, () -> { if (runner() != null) runner().requestCancel(); })
                .setTooltip(Tooltip.create(Component.literal("停止当前任务及排队任务")));
        if (header.width() >= 240) {
            button("上下文", new PanelLayout.Rect(header.right() - 118, header.y() + 3, 54, 20),
                    PanelButton.Tone.QUIET, () -> { setting = Setting.CONTEXT; changeView(View.SETTINGS); });
        }
        initHistory();
        switch (view) {
            case CHAT -> initChat();
            case COMPANION -> initCompanion();
            case SETTINGS -> initSettings();
        }
        updateScrollControls();
        signature = stateSignature();
    }

    private EditBox numeric(String label, int value) {
        EditBox field = PanelFields.create(font, label, Integer.toString(value), 8, false);
        field.setFilter(v -> v.matches("[0-9]*"));
        return field;
    }
    private MultiLineEditBox multiline(String label, String value, int width, int height, int limit) {
        var field = MultiLineEditBox.builder().setPlaceholder(Component.literal(label))
                .setTextColor(ink()).setCursorColor(ink()).setTextShadow(false)
                .setShowBackground(false).setShowDecorations(false)
                .build(font, width, Math.max(12, height), Component.literal(label));
        field.setCharacterLimit(Math.max(limit, value.length()));
        field.setValue(value);
        return field;
    }
    private PanelButton button(String label, PanelLayout.Rect rect, PanelButton.Tone tone, Runnable action) {
        return addRenderableWidget(new PanelButton(label, rect, tone, dark(), action));
    }
    private void changeView(View next) {
        boolean enteringCompanion = next == View.COMPANION && view != next;
        view = next; formScroll = 0; clearFocus(); rebuildWidgets();
        if (enteringCompanion && runner() != null) {
            readCompanionName = runner().companionName();
            runner().refreshCompanion();
        }
    }
    private void initHistory() {
        if (runner() == null) return;
        var tasks = runner().panelHistory().tasks();
        var rail = layout.rail();
        if (layout.sidebar() && rail.height() >= 230) {
            int count = Math.min(tasks.size(), Math.max(0, (rail.height() - 210) / 23));
            for (int i = 0; i < count; i++) {
                var task = tasks.get(i);
                String mark = switch (task.status()) {
                    case "completed" -> "✓ "; case "failed" -> "! "; case "cancelled", "superseded" -> "× ";
                    default -> "· ";
                };
                String title = font.plainSubstrByWidth(mark + task.title().replace("\n", " "), layout.rail().width() - 22);
                button(title, new PanelLayout.Rect(rail.x() + 8, rail.y() + 181 + i * 23, rail.width() - 16, 20),
                        selectedTask == task.id() ? PanelButton.Tone.SELECTED : PanelButton.Tone.QUIET,
                        () -> selectHistory(task.id())).setTooltip(Tooltip.create(Component.literal(task.title())));
            }
        }
        if (!tasks.isEmpty() && (!layout.sidebar() || tasks.size() > Math.max(0, (rail.height() - 210) / 23))) {
            var rect = layout.sidebar() ? new PanelLayout.Rect(rail.x() + 8, rail.bottom() - 26, rail.width() - 16, 20)
                    : new PanelLayout.Rect(layout.actions().x(), layout.actions().y(), 42, 20);
            if (view == View.CHAT) button("记录", rect, PanelButton.Tone.QUIET,
                    () -> minecraft.setScreen(new PanelHistoryScreen(this, runner(), dark(), this::selectHistory)));
        }
    }
    private void selectHistory(long id) {
        selectedTask = runner() != null && id == runner().currentTaskId() ? 0 : id;
        view = View.CHAT; transcriptScroll = Integer.MAX_VALUE; rebuildWidgets();
    }

    private void initChat() {
        if (historical()) {
            button("回到当前对话", layout.input(), PanelButton.Tone.QUIET,
                    () -> { selectedTask = 0; transcriptScroll = 0; rebuildWidgets(); });
        } else {
            place(chatInput, new PanelLayout.Rect(layout.input().x() + 4, layout.input().y(),
                    layout.input().width() - 42, 28));
            addRenderableWidget(chatInput);
            send = button("↑", new PanelLayout.Rect(layout.input().right() - 30, layout.input().y() + 4, 26, 26),
                    PanelButton.Tone.PRIMARY, this::sendInput);
            send.setTooltip(Tooltip.create(Component.literal("发送")));
            var q = question();
            if (q != null && q.authorization()) {
                button("拒绝", new PanelLayout.Rect(layout.actions().right() - 116, layout.actions().y(), 42, 20),
                        PanelButton.Tone.QUIET, () -> answer(q.id(), "不批准"));
                button("查看并确认", new PanelLayout.Rect(layout.actions().right() - 68, layout.actions().y(), 68, 20),
                        PanelButton.Tone.SELECTED, () -> confirmScope(q));
            } else if (q != null) {
                button("查看问题", new PanelLayout.Rect(layout.actions().right() - 62, layout.actions().y(), 62, 20),
                        PanelButton.Tone.QUIET, () -> dialog("同伴的问题", q.text(), "返回", () -> {}, () -> true, false));
            } else {
                button("状态", new PanelLayout.Rect(layout.actions().right() - 90, layout.actions().y(), 42, 20),
                        PanelButton.Tone.QUIET, () -> { if (runner() != null) runner().inspect("status"); });
                button("扫描", new PanelLayout.Rect(layout.actions().right() - 42, layout.actions().y(), 42, 20),
                        PanelButton.Tone.QUIET, () -> { if (runner() != null) runner().inspect("scan_area"); });
            }
        }
        List<PanelHistory.Entry> entries = List.of();
        if (runner() != null) {
            long id = historical() ? selectedTask : runner().currentTaskId() != 0
                    ? runner().currentTaskId() : runner().panelHistory().latestTask();
            entries = runner().panelHistory().entries(id);
        }
        int y = 12;
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            if (entry.kind() == PanelHistory.Kind.TOOL) {
                int end = i + 1;
                while (end < entries.size() && entries.get(end).kind() == PanelHistory.Kind.TOOL) end++;
                boolean open = preferences.foldTools() ? expandedTools.contains(entry.sequence())
                        : !expandedTools.contains(entry.sequence());
                var rect = new PanelLayout.Rect(viewport.x() + 4, viewport.y() + y, viewport.width() - 12, 20);
                PanelButton fold = button("工具过程 · " + (end - i) + " 项  " + (open ? "收起" : "展开"),
                        rect, PanelButton.Tone.QUIET, () -> {
                            if (!expandedTools.add(entry.sequence())) expandedTools.remove(entry.sequence());
                            rebuildWidgets();
                        });
                scrolling.add(new Control(fold, rect));
                y += 25;
                if (open) for (int j = i; j < end; j++) y = text(entries.get(j).text(), 10, y, viewport.width() - 24, muted()) + 6;
                i = end - 1;
                y += 8;
                continue;
            }
            boolean user = entry.kind() == PanelHistory.Kind.USER;
            int textWidth = Math.max(32, (int) (viewport.width() * (user ? .75 : .93)) - 16);
            int x = user ? viewport.width() - textWidth - 12 : 5;
            if (!user) {
                String speaker = entry.kind() == PanelHistory.Kind.NOTICE ? "任务状态"
                        : entry.kind() == PanelHistory.Kind.QUESTION ? companion() + " · 想问你" : companion();
                y = text(speaker, x, y, textWidth, muted()) + 6;
            }
            int start = y;
            y = text(entry.text(), x + (user ? 7 : 0), y + (user ? 6 : 0), textWidth - (user ? 14 : 0), ink());
            if (user) {
                bubbles.add(new Bubble(new PanelLayout.Rect(viewport.x() + x, viewport.y() + start - 1,
                        textWidth, y - start + 7)));
                y += 8;
            } else if (entry.kind() == PanelHistory.Kind.REPLY) {
                var rect = new PanelLayout.Rect(viewport.x() + x, viewport.y() + y + 3, 32, 16);
                var copy = button("复制", rect, PanelButton.Tone.QUIET,
                        () -> { minecraft.keyboardHandler.setClipboard(entry.text()); toast("回复已复制"); });
                scrolling.add(new Control(copy, rect)); y += 20;
            }
            y += 15;
        }
        contentHeight = y;
        if (entries.isEmpty()) text("暂无对话", 6, 18, viewport.width() - 12, muted());
    }

    private void sendInput() {
        String value = chatInput.getValue().strip();
        AgentRunner r = runner();
        if (r == null || r != boundRunner || value.isEmpty()) return;
        var q = question();
        if (draftQuestionId != null) {
            if (q == null || !draftQuestionId.equals(q.id())) { toast("原问题已失效，答复未发送"); return; }
            if (q.authorization() && com.neko.mcbot.agentcore.loop.TaskPolicy.affirmative(value)) confirmScope(q);
            else answer(draftQuestionId, value);
            return;
        }
        if (q != null && !draftBound) { answer(q.id(), value); return; }
        if (r.currentTaskId() != 0 || r.queuedTasks() > 0) {
            dialog("改派当前任务？", "当前任务及排队任务将停止，再发送这条新指令：\n\n" + value,
                    "停止并改派", () -> { r.requestCancel(); r.onOwnerDirective(value); clearInput(); },
                    () -> runner() == r, false);
        } else { r.onOwnerDirective(value); clearInput(); }
    }
    private void clearInput() { chatInput.setValue(""); draftBound = false; draftQuestionId = null; transcriptScroll = 0; }
    private void answer(String id, String value) {
        if (runner() == null || !runner().answerFromPanel(id, value)) toast("问题已过期或已回答，答复未发送");
        else { clearInput(); rebuildWidgets(); }
    }
    private void confirmScope(AgentRunner.QuestionSnapshot q) {
        AgentRunner r = runner();
        dialog("确认这次授权范围", q.text(), "确认授权", () -> answer(q.id(), "确认"),
                () -> runner() == r && r.questionSnapshot() != null && q.id().equals(r.questionSnapshot().id()),
                true);
    }

    private void initSettings() {
        boolean topTabs = viewport.height() >= 100;
        var tabs = topTabs ? new PanelLayout.Rect(viewport.x(), viewport.y(), Math.min(260, viewport.width()), 20)
                : layout.actions();
        if (topTabs) viewport = new PanelLayout.Rect(viewport.x(), viewport.y() + 28, viewport.width(), viewport.height() - 28);
        String[] labels = {"模型", "上下文", "通用"};
        for (int i = 0; i < 3; i++) {
            Setting next = Setting.values()[i];
            button(labels[i], tabs.column(i, 3), setting == next ? PanelButton.Tone.SELECTED : PanelButton.Tone.QUIET,
                    () -> { setting = next; formScroll = 0; clearFocus(); rebuildWidgets(); });
        }
        switch (setting) {
            case MODEL -> initModel();
            case CONTEXT -> initContext();
            case GENERAL -> initGeneral();
        }
    }

    private void initModel() {
        addField("API 地址", baseUrl, 0);
        addField("模型名称", model, 42);
        addField("API 密钥", apiKey, 84);
        addField("人设", persona, 126);
        contentHeight = 142 + persona.getHeight();
        if (runner() != null && !runner().config().environmentOverrides.isEmpty()) {
            contentHeight = text("由环境变量覆盖：" + String.join("、", runner().config().environmentOverrides)
                    + "。这些连接字段需在启动环境中修改。", 4, contentHeight + 14,
                    viewport.width() - 12, 0xFFAD8743);
        }
        if (!connectionResult.isEmpty()) contentHeight = text(connectionResult, 4, contentHeight + 14, viewport.width() - 12, muted());
        var actions = layout.input();
        button("保存设置", actions.column(0, 2), PanelButton.Tone.PRIMARY, this::saveModel);
        button(testing ? "检查中…" : "检查连接", actions.column(1, 2), PanelButton.Tone.QUIET, this::testConnection).active = !testing;
        if (runner() != null) {
            Set<String> overrides = runner().config().environmentOverrides;
            baseUrl.setEditable(!overrides.contains("base_url"));
            model.setEditable(!overrides.contains("model"));
            apiKey.setEditable(!overrides.contains("api_key"));
        }
    }
    private void initContext() {
        var context = runner() == null ? null : runner().contextSnapshot();
        int used = context == null ? 0 : context.used();
        text(String.format(java.util.Locale.ROOT, "%,d / %,d tokens", used, preferences.contextWindow()),
                4, 5, viewport.width() - 12, ink());
        text(context != null && context.reported() ? "最近请求 · 服务端报告" : "当前历史 · 本地估算",
                4, 24, viewport.width() - 12, muted());
        addField("设置的模型窗口", windowTokens, 50);
        addToggle("自动整理上下文", 94, preferences::autoCompact,
                () -> updatePreferences(preferences.contextWindow(), preferences.compactThreshold(), preferences.recentBudget(),
                        !preferences.autoCompact(), preferences.foldTools(), preferences.dark(), preferences.showHud()));
        text("任务结束后，将较早内容整理为摘要", 4, 114, viewport.width() - 12, muted());
        addField("整理触发阈值", threshold, 130);
        threshold.setEditable(preferences.autoCompact());
        formButton(advanced ? "高级设置 · 收起" : "高级设置 · 展开", 176, 110, () -> { advanced = !advanced; rebuildWidgets(); });
        int y = 204;
        if (advanced) {
            addField("近期原文保留量", recentBudget, y);
            y += 50;
            y = text("整理仅改模型上下文，当前会话的对话记录仍可回看。", 4, y, viewport.width() - 12, muted()) + 14;
        }
        var compact = formButton(compacting ? "整理中…" : "立即整理", y, 98, () -> {
            AgentRunner r = runner();
            if (r == null || r.contextSnapshot().busy()) { toast("请先结束当前任务"); return; }
            compacting = true; rebuildWidgets();
            r.compactContext().whenComplete((changed, failure) -> minecraft.execute(() -> {
                compacting = false;
                if (runner() != r) return;
                toast(failure == null && Boolean.TRUE.equals(changed) ? "上下文已整理"
                        : "未生成新摘要，原上下文保留");
                if (minecraft.screen == this) rebuildWidgets();
            }));
        });
        compact.active = !compacting && runner() != null && !runner().contextSnapshot().busy();
        formButton("清空上下文", y + 28, 98, () -> {
            AgentRunner r = runner();
            if (r == null) return;
            dialog("清空模型上下文？", "当前任务与待答问题将取消。模型从空白上下文开始，当前会话的对话记录保留。",
                    "确认清空", () -> { r.clearContext(); toast("模型上下文已清空"); },
                    () -> runner() == r, false);
        });
        contentHeight = y + 60;
        button("保存设置", layout.input().column(0, 2), PanelButton.Tone.PRIMARY, this::saveContext);
        button("查看摘要", layout.input().column(1, 2), PanelButton.Tone.QUIET, () -> {
            String summary = runner() == null ? "" : runner().contextSnapshot().summary();
            dialog("当前上下文摘要", summary.isBlank() ? "暂无摘要" : summary, "返回", () -> {}, () -> true, false);
        });
    }
    private void initGeneral() {
        text("外观", 4, 0, viewport.width() - 12, ink());
        formButton("浅色", 17, 68, () -> setDark(false));
        var darkButton = button("深色", new PanelLayout.Rect(viewport.x() + 80, viewport.y() + 17, 68, 20),
                PanelButton.Tone.QUIET, () -> setDark(true));
        scrolling.add(new Control(darkButton, new PanelLayout.Rect(viewport.x() + 80, viewport.y() + 17, 68, 20)));
        addToggle("折叠工具过程", 50, preferences::foldTools,
                () -> updatePreferences(preferences.contextWindow(), preferences.compactThreshold(), preferences.recentBudget(),
                        preferences.autoCompact(), !preferences.foldTools(), preferences.dark(), preferences.showHud()));
        addToggle("Bot头顶任务气泡", 87, preferences::showHud,
                () -> updatePreferences(preferences.contextWindow(), preferences.compactThreshold(), preferences.recentBudget(),
                        preferences.autoCompact(), preferences.foldTools(), preferences.dark(), !preferences.showHud()));
        text("执行权限", 4, 137, viewport.width() - 12, ink());
        addToggle("自主执行（免确认）", 154, preferences::autonomousActions,
                () -> preferences = preferences.withAutonomousActions(!preferences.autonomousActions()))
                .setTooltip(Tooltip.create(Component.literal(
                        "免去动作批准。保存模式变更将停止当前及排队任务；明确只读的任务仍只读。")));
        int y = text("本地任务桥", 4, 204, viewport.width() - 12, ink()) + 9;
        y = text(BridgeHttp.tokenOrNull() == null ? "未启动" : BridgeHttp.endpoint(), 4, y, viewport.width() - 12, muted()) + 12;
        var copy = formButton("复制连接令牌", y, 104, () -> {
            String token = BridgeHttp.tokenOrNull();
            if (token != null) { minecraft.keyboardHandler.setClipboard(token); toast("连接令牌已复制"); }
        });
        copy.active = BridgeHttp.tokenOrNull() != null;
        contentHeight = y + 30;
        button("保存设置", layout.input(), PanelButton.Tone.PRIMARY, this::savePreferences);
    }
    private void addField(String label, AbstractWidget field, int y) {
        text(label, 4, y, viewport.width() - 12, muted());
        int w = field instanceof MultiLineEditBox ? Math.min(400, viewport.width() - 8)
                : field == windowTokens || field == threshold || field == recentBudget ? Math.min(140, viewport.width() - 8)
                : Math.min(400, viewport.width() - 8);
        var rect = new PanelLayout.Rect(viewport.x() + 4, viewport.y() + y + 16, w, field.getHeight());
        addRenderableWidget(field); scrolling.add(new Control(field, rect));
    }
    private PanelToggle addToggle(String label, int y, java.util.function.BooleanSupplier value, Runnable action) {
        text(label, 4, y + 4, viewport.width() - 48, ink());
        var rect = new PanelLayout.Rect(viewport.right() - 38, viewport.y() + y, 32, 20);
        var toggle = addRenderableWidget(new PanelToggle(label, rect, value, () -> { action.run(); rebuildWidgets(); }));
        scrolling.add(new Control(toggle, rect));
        return toggle;
    }
    private PanelButton formButton(String text, int y, int width, Runnable action) {
        var rect = new PanelLayout.Rect(viewport.x() + 4, viewport.y() + y, Math.min(width, viewport.width() - 8), 20);
        var button = button(text, rect, PanelButton.Tone.QUIET, action);
        scrolling.add(new Control(button, rect)); return button;
    }
    private ClientConfig draftConfig() {
        var current = runner() == null ? new ClientConfig("", "", "", "") : runner().config();
        return current.withConnection(baseUrl.getValue().strip(), model.getValue().strip(),
                apiKey.getValue().strip(), persona.getValue().strip()).withPreferences(preferences);
    }
    private void saveModel() {
        AgentRunner r = runner();
        if (r == null) { toast("尚未连接世界"); return; }
        var draft = draftConfig();
        try { if (draft.brainEnabled) new OpenAiCompatProvider("mcbot", draft.baseUrl, draft.apiKey, draft.model); }
        catch (IllegalArgumentException invalid) { toast("API 地址无效，配置未保存"); return; }
        if (r.config().connectionChanged(draft)) {
            dialog("应用新的模型连接？", "当前任务和待答问题将取消，模型上下文重置。当前会话的对话记录保留。",
                    "保存并重建", () -> apply(draft), () -> runner() == r, false);
        } else apply(draft);
    }
    private void saveContext() {
        try {
            updatePreferences(Integer.parseInt(windowTokens.getValue()), Integer.parseInt(threshold.getValue()),
                    Integer.parseInt(recentBudget.getValue()), preferences.autoCompact(), preferences.foldTools(),
                    preferences.dark(), preferences.showHud());
            savePreferences();
        } catch (IllegalArgumentException invalid) {
            toast("请满足：近期保留量 < 整理阈值 < 模型窗口");
        }
    }
    private void savePreferences() {
        if (runner() == null) { toast("尚未连接世界"); return; }
        apply(runner().config().withPreferences(preferences));
    }
    private void apply(ClientConfig cfg) {
        if (runner() == null) return;
        try { runner().reconfigure(cfg); connectionResult = ""; toast("设置已保存"); rebuildWidgets(); }
        catch (java.io.UncheckedIOException failure) { toast("保存失败，请检查本地文件权限"); }
    }
    private void updatePreferences(int window, int threshold, int recent, boolean automatic, boolean folded, boolean dark, boolean hud) {
        preferences = new ClientConfig.Preferences(window, threshold, recent, automatic, folded, dark, hud,
                preferences.autonomousActions());
    }
    private void setDark(boolean dark) {
        updatePreferences(preferences.contextWindow(), preferences.compactThreshold(), preferences.recentBudget(),
                preferences.autoCompact(), preferences.foldTools(), dark, preferences.showHud());
        rebuildWidgets();
    }
    private void testConnection() {
        if (testing) return;
        var cfg = draftConfig();
        if (!cfg.brainEnabled) { toast("需要 API 地址、模型名称和完整密钥"); return; }
        try {
            var engine = new LlmClient(new OpenAiCompatProvider("mcbot-test", cfg.baseUrl, cfg.apiKey, cfg.model),
                    Duration.ofSeconds(30),
                    d -> com.neko.mcbot.McbotMod.LOG.info("[model-test] llm request {}", d.summary()),
                    d -> com.neko.mcbot.McbotMod.LOG.info("[model-test] llm response {}", d.summary()));
            testing = true; connectionResult = "正在检查流式接口与工具往返";
            AgentRunner owner = runner();
            connectionTest = ModelConnectionTest.run(engine);
            connectionTest.whenComplete((compatible, failure) -> minecraft.execute(() -> {
                testing = false;
                if (runner() != owner) return;
                connectionResult = cfg.connectionChanged(draftConfig()) ? "配置已修改，请重新检查"
                        : failure != null ? LlmFailure.userMessage(failure)
                        : Boolean.TRUE.equals(compatible) ? "连接成功，工具往返通过"
                        : "连接成功，工具往返未通过";
                if (minecraft.screen == this) rebuildWidgets();
            }));
            rebuildWidgets();
        } catch (IllegalArgumentException invalid) { toast("API 地址无效"); }
    }

    private void initCompanion() {
        AgentRunner r = runner();
        JsonObject body = r == null ? new JsonObject() : r.companionSnapshot();
        int y = text(companion(), 4, 3, viewport.width() - 12, ink()) + 7;
        y = text(r == null ? "未连接世界" : r.companionObservation(), 4, y, viewport.width() - 12, muted()) + 14;
        if (r != null && !r.lifecycleResult().isEmpty()) {
            y = text(r.lifecycleResult(), 4, y, viewport.width() - 12, muted()) + 12;
        }
        if (body.has("hp")) {
            y = text("生命 " + string(body, "hp", "—") + "   饱食度 " + string(body, "food", "—"),
                    4, y, viewport.width() - 12, ink()) + 8;
            y = text(string(body, "dimension", "") + " · " + string(body, "x", "") + ", "
                    + string(body, "y", "") + ", " + string(body, "z", ""), 4, y, viewport.width() - 12, muted()) + 8;
            y = text("观测游戏刻 " + string(body, "game_tick", "") + (body.has("on_fire") && body.get("on_fire").getAsBoolean() ? " · 着火" : ""),
                    4, y, viewport.width() - 12, muted()) + 14;
            JsonObject inventory = body.has("inventory") && body.get("inventory").isJsonObject()
                    ? body.getAsJsonObject("inventory") : new JsonObject();
            if (!inventory.has("slots") || !inventory.get("slots").isJsonArray()) {
                y = text("背包明细暂不可用", 4, y, viewport.width() - 12, muted()) + 14;
            } else {
            y = text("背包 · " + string(body, "slots_used", "?") + " / 36", 4, y, viewport.width() - 12, ink()) + 12;
            int size = Math.min(28, (viewport.width() - 12) / 9), cell = Math.max(18, size);
            int selected = inventory.has("selected_slot") ? inventory.get("selected_slot").getAsInt() : -1;
            JsonObject[] stacks = new JsonObject[36];
            if (inventory.has("slots")) for (var value : inventory.getAsJsonArray("slots")) {
                var stack = value.getAsJsonObject(); int index = stack.get("slot").getAsInt();
                if (index >= 0 && index < 36) stacks[index] = stack;
            }
            for (int row = 0; row < 4; row++) {
                if (row == 3) y += 6;
                for (int column = 0; column < 9; column++) {
                    int index = row == 3 ? column : 9 + row * 9 + column;
                    var stack = stacks[index] == null ? new JsonObject() : stacks[index];
                    var rect = new PanelLayout.Rect(viewport.x() + 4 + column * cell, viewport.y() + y, cell - 2, cell - 2);
                    slots.add(new Slot(rect, stack, selected == index, "槽 " + index));
                }
                y += cell;
            }
            y += 8;
            y = text(itemInfo.isEmpty() ? "主手 · " + string(body, "held", "空") : itemInfo,
                    4, y, viewport.width() - 12, muted()) + 15;
            if (inventory.has("equipment") && inventory.get("equipment").isJsonArray()) {
                y = text("装备", 4, y, viewport.width() - 12, ink()) + 10;
                for (var value : inventory.getAsJsonArray("equipment")) {
                    var stack = value.getAsJsonObject();
                    y = text(equipmentName(string(stack, "slot", "")) + " · " + describe(stack),
                            4, y, viewport.width() - 12, muted()) + 7;
                }
            }
            }
        }
        addField("同伴名称", summonName, y + 4);
        contentHeight = y + 48;
        button("刷新", layout.actions().column(0, 2), PanelButton.Tone.QUIET, () -> { if (runner() != null) runner().refreshCompanion(); });
        button("查看任务", layout.actions().column(1, 2), PanelButton.Tone.QUIET, () -> { selectedTask = 0; changeView(View.CHAT); });
        button("召唤", layout.input().column(0, 2), PanelButton.Tone.PRIMARY, () -> {
            if (runner() != null) runner().sendLifecycle("summon", summonName.getValue().strip());
        });
        button("遣散", layout.input().column(1, 2), PanelButton.Tone.DANGER, () -> {
            AgentRunner owner = runner();
            if (owner == null || owner.companionName().isEmpty()) { toast("当前没有同伴"); return; }
            String name = owner.companionName();
            dialog("遣散 " + name + "？", "当前身体任务将停止，同伴离开世界，身体存档与背包保留。",
                    "确认遣散", () -> owner.sendLifecycle("dismiss", name),
                    () -> runner() == owner && name.equals(owner.companionName()), false);
        });
    }
    private static String string(JsonObject object, String key, String fallback) {
        var value = object.get(key); return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }
    private static String equipmentName(String id) {
        return switch (id) {
            case "mainhand" -> "主手"; case "offhand" -> "副手"; case "head" -> "头部";
            case "chest" -> "胸部"; case "legs" -> "腿部"; case "feet" -> "脚部"; case "body" -> "身体";
            default -> id;
        };
    }
    private String describe(JsonObject data) {
        String item = string(data, "item", "");
        if (item.isEmpty() || !data.has("count") || data.get("count").getAsInt() == 0) return "空";
        Identifier id = Identifier.tryParse(item);
        String name = id != null && BuiltInRegistries.ITEM.containsKey(id)
                ? BuiltInRegistries.ITEM.getValue(id).getName().getString() : item;
        String text = name + " × " + string(data, "count", "");
        if (data.has("max_damage")) text += " · 耐久 " + (data.get("max_damage").getAsInt() - data.get("damage").getAsInt())
                + "/" + data.get("max_damage").getAsInt();
        return text;
    }

    private int text(String value, int x, int y, int width, int color) {
        texts.add(new Text(value, viewport.x() + x, viewport.y() + y, Math.max(1, width), color));
        return y + font.split(Component.literal(value), Math.max(1, width)).size() * 12;
    }
    private int maximum() { return Math.max(0, contentHeight - viewport.height()); }
    private int scrollOffset() { return view == View.CHAT ? Math.max(0, maximum() - transcriptScroll) : formScroll; }
    private static void place(AbstractWidget widget, PanelLayout.Rect rect) {
        widget.setRectangle(rect.width(), rect.height(), rect.x(), rect.y());
    }
    private void updateScrollControls() {
        formScroll = Math.max(0, Math.min(formScroll, maximum()));
        transcriptScroll = Math.max(0, Math.min(transcriptScroll, maximum()));
        int offset = scrollOffset();
        for (var control : scrolling) {
            var original = control.rect();
            var rect = new PanelLayout.Rect(original.x(), original.y() - offset, original.width(), original.height());
            place(control.widget(), rect);
            control.widget().visible = viewport.contains(rect);
            if (!control.widget().visible && control.widget().isFocused()) clearFocus();
        }
    }
    private String stateSignature() {
        AgentRunner r = runner();
        if (r == null) return "offline";
        if (view == View.SETTINGS) {
            var context = r.contextSnapshot();
            return setting + "|" + (setting == Setting.CONTEXT ? context.used() + "|" + context.reported() + "|" + context.busy() : "");
        }
        if (view == View.COMPANION) return r.companionName() + "|" + r.bodyObservedAt() + "|"
                + r.companionObservation() + "|" + r.lifecycleResult();
        var q = r.questionSnapshot();
        return r.companionName() + "|" + r.panelHistory().revision() + "|" + r.currentTaskId()
                + "|" + (q == null ? "" : q.id()) + "|" + r.contextSnapshot().busy();
    }
    @Override public void tick() {
        if (runner() != boundRunner) { onClose(); return; }
        if (view == View.COMPANION && runner() != null && !readCompanionName.equals(runner().companionName())) {
            readCompanionName = runner().companionName();
            if (!readCompanionName.isEmpty()) runner().refreshCompanion();
        }
        String current = stateSignature();
        if (!current.equals(signature)) {
            var focused = getFocused();
            rebuildWidgets();
            if (focused instanceof AbstractWidget widget && widget.visible && children().contains(widget)) setInitialFocus(widget);
        }
        if (send != null && view == View.CHAT) send.active = runner() != null;
    }
    private void dialog(String title, String text, String confirm, Runnable action,
                        java.util.function.BooleanSupplier valid, boolean readAll) {
        minecraft.setScreen(new PanelDialog(this, title, text, confirm, action, valid, readAll, dark()));
    }
    private void toast(String text) { message = text; messageUntil = System.currentTimeMillis() + 4500; }

    // Screen's default background applies a full-screen menu blur before render().
    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {}

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        var frame = layout.frame();
        g.fill(frame.x() - 2, frame.y() + 3, frame.right() + 3, frame.bottom() + 3, 0x30000000);
        g.fill(frame.x(), frame.y(), frame.right(), frame.bottom(), dark() ? 0xFF202322 : 0xFFFFFFFF);
        if (layout.sidebar()) {
            var rail = layout.rail();
            g.fill(rail.x(), rail.y(), rail.right(), rail.bottom(), dark() ? 0xFF1B1E1D : 0xFFF7F8F8);
            g.vLine(rail.right(), rail.y(), rail.bottom() - 1, line());
            g.drawString(font, "mcbot", rail.x() + 12, rail.y() + 18, ink(), false);
            avatar(g, rail.x() + 12, rail.y() + 40, 22);
            g.drawString(font, font.plainSubstrByWidth(companion(), rail.width() - 43),
                    rail.x() + 39, rail.y() + 44, ink(), false);
            if (rail.height() >= 230) g.drawString(font, "最近任务", rail.x() + 12, rail.y() + 165, muted(), false);
        }
        var header = layout.header();
        avatar(g, header.x(), header.y() + 2, 22);
        int headerControls = header.width() >= 240 ? 154 : 94;
        g.drawString(font, font.plainSubstrByWidth(companion(), Math.max(16, header.width() - headerControls)),
                header.x() + 28, header.y(), ink(), false);
        String state = runner() == null ? "未连接世界" : runner().taskState();
        g.drawString(font, font.plainSubstrByWidth(state, Math.max(35, header.width() - 102)),
                header.x() + 28, header.y() + 15, question() == null ? muted() : 0xFFAC8749, false);
        g.hLine(header.x() - 10, frame.right() - 1, frame.y() + 36, line());
        g.renderOutline(frame.x(), frame.y(), frame.width(), frame.height(), line());
        updateScrollControls();
        int offset = scrollOffset();
        g.enableScissor(viewport.x(), viewport.y(), viewport.right(), viewport.bottom());
        for (var bubble : bubbles) {
            var b = bubble.rect(); g.fill(b.x(), b.y() - offset, b.right(), b.bottom() - offset, soft());
        }
        for (var text : texts) {
            List<FormattedCharSequence> lines = font.split(Component.literal(text.text()), text.width());
            for (int i = 0; i < lines.size(); i++) {
                int y = text.y() - offset + i * 12;
                if (y + 10 > viewport.y() && y < viewport.bottom()) g.drawString(font, lines.get(i), text.x(), y, text.color(), false);
            }
        }
        if (view == View.COMPANION) renderSlots(g, mouseX, mouseY, offset);
        if (view == View.SETTINGS && setting == Setting.CONTEXT) {
            var c = runner() == null ? null : runner().contextSnapshot();
            int w = viewport.width() - 12, y = viewport.y() + 40 - offset;
            g.fill(viewport.x() + 4, y, viewport.x() + 4 + w, y + 3, soft());
            int filled = c == null ? 0 : (int) Math.min(w, (long) w * c.used() / Math.max(1, preferences.contextWindow()));
            g.fill(viewport.x() + 4, y, viewport.x() + 4 + filled, y + 3, 0xFF719E85);
        }
        for (var control : scrolling) {
            var w = control.widget();
            if (w.visible && (w instanceof EditBox || w instanceof MultiLineEditBox)) {
                g.fill(w.getX(), w.getY() - 3, w.getRight(), w.getBottom(), soft());
                if (w.isFocused()) g.renderOutline(w.getX(), w.getY() - 3, w.getWidth(), w.getHeight() + 3, 0xFF91AC9C);
            }
        }
        g.disableScissor();
        if (view == View.CHAT && !historical()) {
            var q = question();
            int occupied = q == null ? 98 : q.authorization() ? 120 : 70;
            String info = q == null ? runner() == null ? "" : runner().queuedTasks() > 0 ? "排队 " + runner().queuedTasks() : ""
                    : "等待答复 " + formatTime(q.remainingMs());
            int left = !layout.sidebar() && runner() != null && !runner().panelHistory().tasks().isEmpty() ? 48 : 0;
            g.drawString(font, font.plainSubstrByWidth(info, Math.max(1, layout.actions().width() - occupied - left)),
                    layout.actions().x() + left, layout.actions().y() + 5, q == null ? muted() : 0xFFAC8749, false);
            var input = layout.input();
            g.fill(input.x(), input.y() - 2, input.right(), input.bottom(), soft());
            g.renderOutline(input.x(), input.y() - 2, input.width(), input.height() + 2, line());
        }
        super.render(g, mouseX, mouseY, delta);
        if (maximum() > 0) {
            var thumb = PanelLayout.scrollThumb(viewport, scrollOffset(), maximum());
            g.fill(thumb.x(), thumb.y(), thumb.right(), thumb.bottom(), 0xFF91AA9C);
        }
        String footer = messageUntil > System.currentTimeMillis() ? message : historical() ? "当前会话记录"
                : view == View.SETTINGS ? "" : runner() == null ? "未连接世界"
                : runner().brainOnline() ? runner().config().model + " · " + runner().contextSnapshot().steps() + "/40 步" : "模型未配置";
        g.drawString(font, font.plainSubstrByWidth(footer, layout.status().width()), layout.status().x(), layout.status().y(), muted(), false);
    }
    private void avatar(GuiGraphics g, int x, int y, int size) {
        var connection = minecraft.getConnection();
        var player = connection == null ? null : connection.getPlayerInfo(companion());
        if (player != null) PlayerFaceRenderer.draw(g, player.getSkin(), x, y, size);
        else {
            g.fill(x, y, x + size, y + size, dark() ? 0xFF34473C : 0xFFE5ECE7);
            g.drawCenteredString(font, companion().substring(0, 1), x + size / 2, y + (size - 8) / 2, ink());
        }
    }
    private void renderSlots(GuiGraphics g, int mouseX, int mouseY, int offset) {
        for (var slot : slots) {
            var r = slot.rect();
            int y = r.y() - offset;
            if (y + r.height() <= viewport.y() || y >= viewport.bottom()) continue;
            g.fill(r.x(), y, r.right(), y + r.height(), soft());
            g.renderOutline(r.x(), y, r.width(), r.height(), slot.selected() ? 0xFF91AC9C : line());
            String item = string(slot.data(), "item", "");
            Identifier id = Identifier.tryParse(item);
            if (id != null && BuiltInRegistries.ITEM.containsKey(id) && slot.data().has("count")
                    && slot.data().get("count").getAsInt() > 0) {
                ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(id), slot.data().get("count").getAsInt());
                int iconX = r.x() + (r.width() - 16) / 2, iconY = y + (r.height() - 16) / 2;
                g.renderFakeItem(stack, iconX, iconY);
                if (stack.getCount() > 1) g.drawString(font, Integer.toString(stack.getCount()), r.right() - font.width(Integer.toString(stack.getCount())) - 1,
                        y + r.height() - 9, ink(), false);
            }
            if (new PanelLayout.Rect(r.x(), y, r.width(), r.height()).contains(mouseX, mouseY)
                    && viewport.contains(mouseX, mouseY)) {
                g.setTooltipForNextFrame(font, Component.literal(slot.label() + " · " + describe(slot.data())), mouseX, mouseY);
            }
        }
    }
    public static String formatTime(long milliseconds) {
        long seconds = (Math.max(0, milliseconds) + 999) / 1000;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (view == View.CHAT && !historical() && chatInput.isFocused()
                && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)
                && (event.modifiers() & GLFW.GLFW_MOD_SHIFT) == 0) {
            sendInput(); return true;
        }
        return super.keyPressed(event);
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (viewport.contains(event.x(), event.y()) && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (event.x() >= viewport.right() - 7 && maximum() > 0) {
                var thumb = PanelLayout.scrollThumb(viewport, scrollOffset(), maximum());
                scrollGrab = event.y() >= thumb.y() && event.y() < thumb.bottom() ? (int) event.y() - thumb.y() : thumb.height() / 2;
                draggingScroll = true; clearFocus(); dragScroll(event.y()); return true;
            }
            if (view == View.COMPANION) for (var slot : slots) {
                var r = slot.rect();
                if (new PanelLayout.Rect(r.x(), r.y() - scrollOffset(), r.width(), r.height()).contains(event.x(), event.y())) {
                    itemInfo = slot.label() + " · " + describe(slot.data()); rebuildWidgets(); return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }
    private void dragScroll(double y) {
        var thumb = PanelLayout.scrollThumb(viewport, scrollOffset(), maximum());
        int travel = viewport.height() - thumb.height();
        int offset = travel <= 0 ? 0 : (int) ((y - scrollGrab - viewport.y()) * maximum() / travel);
        offset = Math.max(0, Math.min(maximum(), offset));
        if (view == View.CHAT) transcriptScroll = maximum() - offset; else formScroll = offset;
        updateScrollControls();
    }
    @Override public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (draggingScroll) { dragScroll(event.y()); return true; }
        return super.mouseDragged(event, deltaX, deltaY);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingScroll) { draggingScroll = false; return true; }
        return super.mouseReleased(event);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        for (var control : scrolling) if (control.widget() instanceof MultiLineEditBox && control.widget().visible
                && control.widget().isMouseOver(x, y) && control.widget().mouseScrolled(x, y, horizontal, vertical)) return true;
        if (viewport.contains(x, y)) {
            if (view == View.CHAT) transcriptScroll = Math.max(0, Math.min(maximum(), transcriptScroll + (int) (vertical * 24)));
            else formScroll = Math.max(0, Math.min(maximum(), formScroll - (int) (vertical * 24)));
            updateScrollControls(); return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    @Override public void removed() {
        if (testing && connectionTest != null) connectionTest.cancel(true);
    }
    @Override public boolean isPauseScreen() { return false; }
}
