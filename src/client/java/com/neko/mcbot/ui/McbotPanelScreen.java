package com.neko.mcbot.ui;

import com.neko.mcbot.McbotClient;
import com.neko.mcbot.agent.AgentRunner;
import com.neko.mcbot.cfg.ClientConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * mcbot 面板（默认按键 G）：模型配置 + 召唤/遣散 + 与同伴对话。
 * 大脑未配置时先填 base_url/model/api_key，"保存并应用"即刻生效。
 */
public final class McbotPanelScreen extends Screen {

    private static final int COL_W = 210;
    private static final int FIELD_H = 20;

    private EditBox baseUrl;
    private EditBox model;
    private EditBox apiKey;
    private EditBox persona;
    private EditBox summonName;
    private EditBox chatInput;
    private final java.util.List<EditBox> boxes = new java.util.ArrayList<>();

    public McbotPanelScreen() {
        super(Component.literal("mcbot · 同伴面板"));
    }

    private AgentRunner runner() {
        return McbotClient.runner();
    }

    @Override
    protected void init() {
        int x = this.width - COL_W - 12;
        int y = 16;
        ClientConfig cfg = runner() == null ? new ClientConfig("", "", "", "") : runner().config();

        this.addRenderableWidget(label("模型端点 base_url", x, y));
        baseUrl = addField(new EditBox(this.font, x, y + 11, COL_W, FIELD_H,
                Component.literal("base_url")), cfg.baseUrl);
        y += 38;

        this.addRenderableWidget(label("型号 model", x, y));
        model = addField(new EditBox(this.font, x, y + 11, COL_W, FIELD_H,
                Component.literal("model")), cfg.model);
        y += 38;

        this.addRenderableWidget(label("密钥 api_key", x, y));
        apiKey = addField(new EditBox(this.font, x, y + 11, COL_W, FIELD_H,
                Component.literal("api_key")), cfg.apiKey);
        y += 38;

        this.addRenderableWidget(label("人设 persona（可空）", x, y));
        persona = addField(new EditBox(this.font, x, y + 11, COL_W, FIELD_H,
                Component.literal("persona")), cfg.persona);
        y += 38;

        this.addRenderableWidget(Button.builder(Component.literal("保存并应用"), b -> applyConfig())
                .bounds(x, y, 100, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("清钥"), b -> apiKey.setValue(""))
                .bounds(x + 106, y, 50, 20).build());
        y += 30;

        this.addRenderableWidget(label("召唤/遣散（同伴名）", x, y));
        summonName = addField(new EditBox(this.font, x, y + 11, COL_W, FIELD_H,
                Component.literal("name")), "steve");
        y += 34;
        this.addRenderableWidget(Button.builder(Component.literal("召唤"), b -> lifecycle("summon"))
                .bounds(x, y, 68, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("遣散"), b -> lifecycle("dismiss"))
                .bounds(x + 71, y, 68, 20).build());
        y += 28;
        this.addRenderableWidget(Button.builder(Component.literal("查状态（问同伴）"),
                        b -> sendChat("用 status 工具查看你自己的状态并简报。"))
                .bounds(x, y, COL_W, 20).build());
        y += 24;
        this.addRenderableWidget(Button.builder(Component.literal("叫停进行中的任务"), b -> {
            AgentRunner r = runner();
            if (r != null) {
                r.requestCancel();
            }
        }).bounds(x, y, COL_W, 20).build());

        int chatW = this.width - 40 - 60;
        chatInput = new EditBox(this.font, 20, this.height - 30, chatW, 20,
                Component.literal("对同伴说"));
        chatInput.setHint(Component.literal("对它说点什么，回车发送…"));
        this.addWidget(chatInput);
        this.boxes.add(chatInput);
        this.addRenderableWidget(Button.builder(Component.literal("发送"),
                        b -> sendChat(chatInput.getValue()))
                .bounds(this.width - 60, this.height - 30, 48, 20).build());
    }

    private EditBox addField(EditBox box, String value) {
        box.setValue(value == null ? "" : value);
        box.setMaxLength(256);
        this.addWidget(box);
        this.boxes.add(box); // addWidget 只接事件；渲染在 render() 里手动补
        return box;
    }

    private Button label(String text, int x, int y) {
        return Button.builder(Component.literal(text), b -> {
        }).bounds(x, y, COL_W, 10).build();
    }

    private void applyConfig() {
        if (runner() == null) {
            return;
        }
        runner().reconfigure(new ClientConfig(
                baseUrl.getValue().trim(), model.getValue().trim(),
                apiKey.getValue().trim(), persona.getValue().trim()));
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
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            sendChat(chatInput.getValue());
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        for (EditBox box : this.boxes) {
            box.render(g, mouseX, mouseY, delta);
        }
        g.drawCenteredString(this.font, this.title, this.width / 2, 6, 0xFFFFFF);
        String tok = com.neko.mcbot.bridge.BridgeHttp.tokenOrNull();
        g.drawString(this.font, tok == null ? "桥接: 未启动（进世界后自动开）"
                        : "桥接: " + com.neko.mcbot.bridge.BridgeHttp.endpoint() + " · token " + tok,
                10, 4, 0x8FB8D8);

        AgentRunner r = runner();
        List<String> lines = r == null ? List.of("(尚未连服)") : r.transcriptSnapshot();
        int bottom = this.height - 40;
        int top = 16;
        int lineHeight = 11;
        int maxLines = Math.max(1, (bottom - top) / lineHeight);
        int start = Math.max(0, lines.size() - maxLines);
        for (int i = start, y = top; i < lines.size(); i++, y += lineHeight) {
            g.drawString(this.font, lines.get(i), 10, y, 0xE0E0E0);
        }
        g.drawString(this.font,
                r != null && r.config().brainEnabled
                        ? "大脑: " + r.config().model
                        : "大脑: 未配置",
                10, bottom + 4, 0x909090);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
