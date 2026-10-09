package com.neko.mcbot.ui;

import com.neko.mcbot.McbotClient;
import com.neko.mcbot.agent.AgentRunner;
import com.neko.mcbot.cfg.ClientConfig;
import com.neko.mcbot.agentcore.llm.LlmClient;
import com.neko.mcbot.agentcore.llm.LlmFailure;
import com.neko.mcbot.agentcore.llm.ModelConnectionTest;
import com.neko.mcbot.agentcore.provider.OpenAiCompatProvider;
import com.neko.mcbot.bridge.BridgeHttp;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * G 面板：任务、模型和同伴分区；配置草稿跨切页/窗口缩放保留。
 */
public final class McbotPanelScreen extends Screen {

    private enum Tab { TASK, MODEL, COMPANION }
    private Tab tab = Tab.TASK;
    private PanelLayout layout;
    private int formScroll;
    private int transcriptScroll;
    private String connectionResult = "";
    private boolean testing;
    private boolean draggingScroll;
    private int scrollGrab;
    private CompletableFuture<Boolean> connectionTest;
    private final List<AbstractWidget> formControls = new ArrayList<>();
    private EditBox baseUrl;
    private EditBox model;
    private EditBox apiKey;
    private EditBox persona;
    private EditBox summonName;
    private EditBox chatInput;

    public McbotPanelScreen() {
        super(Component.literal("mcbot · 同伴面板"));
    }

    private AgentRunner runner() {
        return McbotClient.runner();
    }

    @Override
    protected void init() {
        layout = PanelLayout.at(width, height);
        formControls.clear();
        ClientConfig cfg = runner() == null ? new ClientConfig("", "", "", "") : runner().config();
        if (baseUrl == null) {
            baseUrl = PanelFields.create(font, "API 地址", cfg.baseUrl, 2048, false);
            model = PanelFields.create(font, "模型名称", cfg.model, 1024, false);
            apiKey = PanelFields.create(font, "API 密钥", cfg.apiKey, 4096, true);
            persona = PanelFields.create(font, "人设", cfg.persona, 4096, false);
            String companion = runner() == null ? "" : runner().companionName();
            summonName = PanelFields.create(font, "同伴名称", companion.isEmpty() ? "steve" : companion, 16, false);
            chatInput = PanelFields.create(font, "任务指令", "", 4096, false);
        }
        Tab[] tabs = Tab.values();
        String[] names = {"任务", "模型", "同伴"};
        for (int i = 0; i < tabs.length; i++) {
            Tab target = tabs[i];
            Button button = button(names[i], layout.tabs().column(i, tabs.length), () -> {
                tab = target;
                formScroll = 0;
                clearFocus();
                rebuildWidgets();
            });
            button.active = tab != target;
        }
        switch (tab) {
            case TASK -> initTask();
            case MODEL -> initModel();
            case COMPANION -> initCompanion();
        }
        updateFormControls();
    }

    private void initTask() {
        button("查看状态", layout.actions().column(0, 3),
                () -> { if (runner() != null) runner().inspect("status"); });
        button("扫描附近", layout.actions().column(1, 3),
                () -> { if (runner() != null) runner().inspect("scan_area"); });
        button("停止任务", layout.actions().column(2, 3), () -> {
            if (runner() != null) runner().requestCancel();
        });
        PanelLayout.Rect input = layout.input();
        place(chatInput, new PanelLayout.Rect(input.x(), input.y(), input.width() - 54, input.height()));
        addRenderableWidget(chatInput);
        button("发送", new PanelLayout.Rect(input.right() - 48, input.y(), 48, input.height()),
                () -> sendChat(chatInput.getValue()));
    }

    private void initModel() {
        for (EditBox field : List.of(baseUrl, model, apiKey, persona)) {
            addRenderableWidget(field);
            formControls.add(field);
        }
        button("保存并应用", layout.actions().column(0, 2), this::applyConfig)
                .setTooltip(Tooltip.create(Component.literal("保存当前配置，取消旧任务并重建大脑。")));
        button(testing ? "测试中…" : "测试连接", layout.actions().column(1, 2), this::testConnection)
                .active = !testing;
        button("清除密钥", layout.input().column(0, 2), () -> apiKey.setValue(""));
        button("复制桥接令牌", layout.input().column(1, 2), () -> {
            String token = BridgeHttp.tokenOrNull();
            if (token != null) minecraft.keyboardHandler.setClipboard(token);
        }).active = BridgeHttp.tokenOrNull() != null;
    }

    private void initCompanion() {
        addRenderableWidget(summonName);
        formControls.add(summonName);
        button("召唤", layout.actions().column(0, 2), () -> lifecycle("summon"));
        button("遣散", layout.actions().column(1, 2), () -> lifecycle("dismiss"));
    }

    private Button button(String text, PanelLayout.Rect rect, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), b -> action.run())
                .bounds(rect.x(), rect.y(), rect.width(), rect.height()).build());
    }

    private static void place(AbstractWidget widget, PanelLayout.Rect rect) {
        widget.setRectangle(rect.width(), rect.height(), rect.x(), rect.y());
    }

    private void updateFormControls() {
        formScroll = Math.max(0, Math.min(formScroll, layout.maxFormScroll(formHeight())));
        for (int i = 0; i < formControls.size(); i++) {
            AbstractWidget widget = formControls.get(i);
            PanelLayout.Rect rect = layout.formField(i, formScroll);
            place(widget, rect);
            widget.visible = layout.body().contains(rect);
            widget.active = widget.visible;
            if (!widget.visible && widget.isFocused()) clearFocus();
        }
    }

    private int formHeight() {
        if (tab == Tab.MODEL) {
            return 4 * PanelLayout.FIELD_ROW + 8
                    + (connectionResult.isEmpty() ? 0 : font.wordWrapHeight(
                    Component.literal(connectionResult), layout.formField(0, 0).width()));
        }
        String result = runner() == null ? "" : runner().lifecycleResult();
        return PanelLayout.FIELD_ROW + 8 + (result.isEmpty() ? 0 : font.wordWrapHeight(
                Component.literal(result), layout.formField(0, 0).width()));
    }

    private ClientConfig draftConfig() {
        return new ClientConfig(baseUrl.getValue().trim(), model.getValue().trim(),
                apiKey.getValue().trim(), persona.getValue().trim(),
                runner() == null || runner().config().acceptMode);
    }

    private void testConnection() {
        if (testing) return;
        ClientConfig cfg = draftConfig();
        if (!cfg.brainEnabled) {
            showConnectionResult("配置不完整，需要 API 地址、模型名称和完整密钥。");
            return;
        }
        try {
            LlmClient engine = new LlmClient(
                    new OpenAiCompatProvider("mcbot-test", cfg.baseUrl, cfg.apiKey, cfg.model),
                    Duration.ofSeconds(30),
                    diagnostic -> com.neko.mcbot.McbotMod.LOG.info(
                            "[model-test] llm request {}", diagnostic.summary()),
                    diagnostic ->
                        com.neko.mcbot.McbotMod.LOG.info("[model-test] llm response {}", diagnostic.summary()));
            testing = true;
            showConnectionResult("正在测试流式接口与工具往返…");
            connectionTest = ModelConnectionTest.run(engine);
            connectionTest.whenComplete((compatible, failure) -> minecraft.execute(() -> {
                testing = false;
                ClientConfig current = draftConfig();
                boolean unchanged = cfg.baseUrl.equals(current.baseUrl)
                        && cfg.model.equals(current.model) && cfg.apiKey.equals(current.apiKey);
                showConnectionResult(!unchanged ? "配置已修改，请重新测试。"
                        : failure != null ? LlmFailure.userMessage(failure)
                        : Boolean.TRUE.equals(compatible) ? "连接成功，流式回答和工具往返通过。"
                        : "连接成功，但工具往返未通过，暂不能确认 agent 兼容性。");
                if (minecraft.screen == this) rebuildWidgets();
            }));
            rebuildWidgets();
        } catch (IllegalArgumentException invalid) {
            testing = false;
            showConnectionResult("API 地址无效，需要 http(s) 地址，不含账号、查询参数或片段。");
        }
    }

    private void showConnectionResult(String result) {
        connectionResult = result;
        if (tab == Tab.MODEL) {
            formScroll = layout.maxFormScroll(formHeight());
            updateFormControls();
        }
    }

    private void applyConfig() {
        AgentRunner r = runner();
        if (r == null) {
            return;
        }
        // accept_mode 面板上没有开关（它是发布后的止血阀门，只手改 client.json）；
        // 这里**原样带过去**，免得"保存并应用"把主人手关掉的开关又拧回开。
        try {
            ClientConfig cfg = draftConfig();
            if (cfg.brainEnabled) new OpenAiCompatProvider("mcbot", cfg.baseUrl, cfg.apiKey, cfg.model);
            r.reconfigure(cfg);
            connectionResult = "";
            updateFormControls();
        } catch (IllegalArgumentException invalid) {
            showConnectionResult("API 地址无效，配置未保存。");
        }
    }

    private void lifecycle(String kind) {
        AgentRunner r = runner();
        if (r != null) {
            r.sendLifecycle(kind, summonName.getValue().trim());
        }
    }

    private void sendChat(String text) {
        AgentRunner r = runner();
        if (r != null && !text.isBlank()) {
            r.onOwnerDirective(text);
            chatInput.setValue("");
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (tab == Tab.TASK && chatInput.isFocused()
                && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
            sendChat(chatInput.getValue());
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0xDD17191C);
        PanelLayout.Rect header = layout.header();
        g.drawString(font, title, header.x(), header.y(), 0xFFFFFFFF);
        PanelLayout.Rect body = layout.body();
        g.fill(body.x(), body.y(), body.right(), body.bottom(), 0xA0202427);
        g.enableScissor(body.x(), body.y(), body.right(), body.bottom());
        if (tab == Tab.TASK) renderTranscript(g);
        else {
            updateFormControls();
            renderForm(g);
        }
        g.disableScissor();
        // Only fully visible form widgets remain interactive; fixed actions never enter the scrolling region.
        super.render(g, mouseX, mouseY, delta);
        AgentRunner r = runner();
        String state = r == null ? "未连接世界" : r.config().brainEnabled ? "大脑已配置" : "大脑未配置";
        String bridge = BridgeHttp.tokenOrNull() == null ? "桥接未启动" : BridgeHttp.endpoint();
        PanelLayout.Rect status = layout.status();
        g.drawString(font, font.plainSubstrByWidth(state + " · " + bridge, status.width()),
                status.x(), status.y(), 0xFF9CCAB8);
    }

    private void renderForm(GuiGraphics g) {
        String[] labels = tab == Tab.MODEL
                ? new String[]{"API 地址", "模型名称", "API 密钥", "人设（可空）"}
                : new String[]{"同伴名称"};
        for (int i = 0; i < labels.length; i++) {
            PanelLayout.Rect field = layout.formField(i, formScroll);
            g.drawString(font, labels[i], field.x(), field.y() - 12, 0xFFE2E4E7);
        }
        if (tab == Tab.MODEL && !connectionResult.isEmpty()) {
            PanelLayout.Rect field = layout.formField(0, 0);
            g.drawWordWrap(font, Component.literal(connectionResult), field.x(),
                    layout.body().y() + 4 * PanelLayout.FIELD_ROW + 4 - formScroll,
                    field.width(), 0xFFE2E4E7);
        }
        if (tab == Tab.COMPANION && runner() != null && !runner().lifecycleResult().isEmpty()) {
            PanelLayout.Rect field = layout.formField(0, 0);
            g.drawWordWrap(font, Component.literal(runner().lifecycleResult()), field.x(),
                    layout.body().y() + PanelLayout.FIELD_ROW + 4 - formScroll,
                    field.width(), 0xFFE2E4E7);
        }
        scrollbar(g, formScroll, layout.maxFormScroll(formHeight()), layout.body());
    }

    private void renderTranscript(GuiGraphics g) {
        List<FormattedCharSequence> lines = wrappedTranscript();
        PanelLayout.Rect body = layout.body();
        int visible = Math.max(1, (body.height() - 8) / 11);
        int maximum = Math.max(0, lines.size() - visible);
        transcriptScroll = Math.min(transcriptScroll, maximum);
        int start = Math.max(0, lines.size() - visible - transcriptScroll);
        for (int i = start, y = body.y() + 4; i < Math.min(lines.size(), start + visible); i++, y += 11) {
            g.drawString(font, lines.get(i), body.x() + 4, y, 0xFFE2E4E7);
        }
        scrollbar(g, (maximum - transcriptScroll) * 11, maximum * 11, body);
    }

    private List<FormattedCharSequence> wrappedTranscript() {
        AgentRunner r = runner();
        List<String> transcript = r == null ? List.of("尚未连接世界") : r.transcriptSnapshot();
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (String line : transcript) {
            lines.addAll(font.split(Component.literal(line), Math.max(1, layout.body().width() - 16)));
        }
        return lines;
    }

    private static void scrollbar(GuiGraphics g, int value, int maximum, PanelLayout.Rect rect) {
        if (maximum == 0) return;
        PanelLayout.Rect thumb = PanelLayout.scrollThumb(rect, value, maximum);
        g.fill(rect.right() - 3, rect.y(), rect.right() - 1, rect.bottom(), 0xFF44484B);
        g.fill(thumb.x(), thumb.y(), thumb.right(), thumb.bottom(), 0xFF97B5A8);
    }

    private int maxTranscriptScroll() {
        int visible = Math.max(1, (layout.body().height() - 8) / 11);
        return Math.max(0, wrappedTranscript().size() - visible);
    }

    private int maxScrollPixels() {
        return tab == Tab.TASK ? maxTranscriptScroll() * 11 : layout.maxFormScroll(formHeight());
    }

    private int scrollPixels() {
        return tab == Tab.TASK ? (maxTranscriptScroll() - transcriptScroll) * 11 : formScroll;
    }

    private void dragScroll(double y) {
        PanelLayout.Rect body = layout.body();
        int maximum = maxScrollPixels();
        PanelLayout.Rect thumb = PanelLayout.scrollThumb(body, scrollPixels(), maximum);
        int travel = body.height() - thumb.height();
        int offset = travel <= 0 ? 0 : (int) ((y - scrollGrab - body.y()) * maximum / travel);
        offset = Math.max(0, Math.min(offset, maximum));
        if (tab == Tab.TASK) transcriptScroll = maxTranscriptScroll() - offset / 11;
        else {
            formScroll = offset;
            updateFormControls();
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        PanelLayout.Rect body = layout.body();
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && body.contains(event.x(), event.y())
                && event.x() >= body.right() - 8 && maxScrollPixels() > 0) {
            PanelLayout.Rect thumb = PanelLayout.scrollThumb(body, scrollPixels(), maxScrollPixels());
            scrollGrab = event.y() >= thumb.y() && event.y() < thumb.bottom()
                    ? (int) event.y() - thumb.y() : thumb.height() / 2;
            draggingScroll = true;
            clearFocus();
            dragScroll(event.y());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (draggingScroll) {
            dragScroll(event.y());
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingScroll) {
            draggingScroll = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (layout.body().contains(mouseX, mouseY)) {
            if (tab == Tab.TASK) {
                transcriptScroll = Math.max(0, Math.min(maxTranscriptScroll(),
                        transcriptScroll + (int) (vertical * 3)));
            } else {
                formScroll -= (int) (vertical * 20);
                updateFormControls();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
