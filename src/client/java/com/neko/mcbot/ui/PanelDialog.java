package com.neko.mcbot.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.BooleanSupplier;

/** Scrollable full-scope confirmation; approval stays unavailable until the end is visible. */
final class PanelDialog extends Screen {
    private final Screen parent;
    private final String text;
    private final String confirm;
    private final Runnable action;
    private final BooleanSupplier valid;
    private final boolean readAll;
    private final boolean dark;
    private PanelLayout.Rect frame;
    private PanelLayout.Rect viewport;
    private List<FormattedCharSequence> lines;
    private PanelButton accept;
    private int offset;
    private int maximum;

    PanelDialog(Screen parent, String title, String text, String confirm, Runnable action,
                BooleanSupplier valid, boolean readAll, boolean dark) {
        super(Component.literal(title));
        this.parent = parent;
        this.text = text;
        this.confirm = confirm;
        this.action = action;
        this.valid = valid;
        this.readAll = readAll;
        this.dark = dark;
    }

    @Override protected void init() {
        frame = PanelLayout.windowBounds(width, height, 440, 320);
        viewport = new PanelLayout.Rect(frame.x() + 12, frame.y() + 44,
                frame.width() - 24, frame.height() - 102);
        lines = font.split(Component.literal(text), viewport.width() - 8);
        maximum = Math.max(0, lines.size() * 12 - viewport.height());
        offset = Math.min(offset, maximum);
        var buttons = new PanelLayout.Rect(viewport.x(), frame.bottom() - 42, viewport.width(), 20);
        addRenderableWidget(new PanelButton("返回", buttons.column(0, 2),
                PanelButton.Tone.QUIET, dark, this::onClose));
        accept = addRenderableWidget(new PanelButton(confirm, buttons.column(1, 2),
                PanelButton.Tone.PRIMARY, dark, () -> {
                    if (!valid.getAsBoolean() || readAll && offset < maximum) return;
                    minecraft.setScreen(parent);
                    action.run();
                }));
        tick();
    }

    @Override public void tick() {
        if (accept != null) accept.active = valid.getAsBoolean() && (!readAll || offset >= maximum);
    }
    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {}

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        g.fill(frame.x() - 2, frame.y() + 3, frame.right() + 3, frame.bottom() + 3, 0x30000000);
        g.fill(frame.x(), frame.y(), frame.right(), frame.bottom(), dark ? 0xFF202322 : 0xFFFFFFFF);
        g.renderOutline(frame.x(), frame.y(), frame.width(), frame.height(), dark ? 0xFF353C37 : 0xFFE9EDED);
        g.drawString(font, font.plainSubstrByWidth(title.getString(), viewport.width()),
                viewport.x(), frame.y() + 22, dark ? 0xFFE3E7E4 : 0xFF272C2D, false);
        g.enableScissor(viewport.x(), viewport.y(), viewport.right(), viewport.bottom());
        for (int i = Math.max(0, offset / 12); i < lines.size(); i++) {
            int y = viewport.y() + i * 12 - offset;
            if (y >= viewport.bottom()) break;
            g.drawString(font, lines.get(i), viewport.x(), y, dark ? 0xFFC5CEC8 : 0xFF59645E, false);
        }
        g.disableScissor();
        if (maximum > 0) {
            var thumb = PanelLayout.scrollThumb(viewport, offset, maximum);
            g.fill(thumb.x(), thumb.y(), thumb.right(), thumb.bottom(), 0xFF91AA9C);
        }
        String state = !valid.getAsBoolean() ? "操作已失效，请返回查看最新状态"
                : readAll && offset < maximum ? "请向下查看完整范围" : "";
        g.drawString(font, font.plainSubstrByWidth(state, viewport.width()),
                viewport.x(), frame.bottom() - 58, dark ? 0xFFA4ADA7 : 0xFF828E86, false);
        super.render(g, mouseX, mouseY, delta);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        offset = Math.max(0, Math.min(maximum, offset - (int) (vertical * 24)));
        tick();
        return true;
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_END || event.key() == GLFW.GLFW_KEY_PAGE_DOWN) {
            offset = event.key() == GLFW.GLFW_KEY_END ? maximum : Math.min(maximum, offset + viewport.height());
            tick();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_HOME || event.key() == GLFW.GLFW_KEY_PAGE_UP) {
            offset = event.key() == GLFW.GLFW_KEY_HOME ? 0 : Math.max(0, offset - viewport.height());
            tick();
            return true;
        }
        return super.keyPressed(event);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
