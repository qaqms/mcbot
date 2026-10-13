package com.neko.mcbot.ui;

import com.neko.mcbot.McbotClient;
import com.neko.mcbot.agent.AgentRunner;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.LongConsumer;

final class PanelHistoryScreen extends Screen {
    private final Screen parent;
    private final AgentRunner owner;
    private final boolean dark;
    private final LongConsumer select;
    private PanelLayout.Rect frame;
    private int offset;

    PanelHistoryScreen(Screen parent, AgentRunner owner, boolean dark, LongConsumer select) {
        super(Component.literal("当前会话记录"));
        this.parent = parent; this.owner = owner; this.dark = dark; this.select = select;
    }
    @Override protected void init() {
        frame = PanelLayout.windowBounds(width, height, 440, 320);
        int w = frame.width() - 24, x = frame.x() + 12;
        int visible = Math.max(1, (frame.height() - 90) / 27);
        var tasks = owner.panelHistory().tasks();
        offset = Math.max(0, Math.min(offset, Math.max(0, tasks.size() - visible)));
        for (int i = offset; i < Math.min(tasks.size(), offset + visible); i++) {
            var task = tasks.get(i);
            String state = switch (task.status()) {
                case "running" -> "执行中"; case "queued" -> "排队中"; case "completed" -> "已结束";
                case "cancelled" -> "已取消"; case "superseded" -> "已替换"; default -> "失败";
            };
            var button = addRenderableWidget(new PanelButton(state + " · " + task.title().replace('\n', ' '),
                    new PanelLayout.Rect(x, frame.y() + 42 + (i - offset) * 27, w, 22), PanelButton.Tone.QUIET, dark,
                    () -> { if (McbotClient.runner() != owner) return; minecraft.setScreen(parent); select.accept(task.id()); }));
            button.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(task.title())));
        }
        addRenderableWidget(new PanelButton("返回", new PanelLayout.Rect(x, frame.bottom() - 36, w, 20),
                PanelButton.Tone.QUIET, dark, this::onClose));
    }
    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {}

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        g.fill(frame.x() - 2, frame.y() + 3, frame.right() + 3, frame.bottom() + 3, 0x30000000);
        g.fill(frame.x(), frame.y(), frame.right(), frame.bottom(), dark ? 0xFF202322 : 0xFFFFFFFF);
        g.renderOutline(frame.x(), frame.y(), frame.width(), frame.height(), dark ? 0xFF353C37 : 0xFFE9EDED);
        g.drawString(font, title, frame.x() + 12, frame.y() + 18,
                dark ? 0xFFE3E7E4 : 0xFF272C2D, false);
        super.render(g, mouseX, mouseY, delta);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        offset = Math.max(0, offset - (int) vertical); rebuildWidgets(); return true;
    }
    @Override public void tick() { if (McbotClient.runner() != owner) onClose(); }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
