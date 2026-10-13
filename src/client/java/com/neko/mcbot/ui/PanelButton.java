package com.neko.mcbot.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Retains vanilla input/narration while replacing its heavy button sprite. */
final class PanelButton extends Button {
    enum Tone { QUIET, PRIMARY, SELECTED, DANGER }
    private final Tone tone;
    private final boolean dark;

    PanelButton(String text, PanelLayout.Rect rect, Tone tone, boolean dark, Runnable action) {
        super(rect.x(), rect.y(), rect.width(), rect.height(), Component.literal(text),
                b -> action.run(), DEFAULT_NARRATION);
        this.tone = tone;
        this.dark = dark;
    }

    @Override
    protected void renderContents(GuiGraphics g, int mouseX, int mouseY, float delta) {
        int fill = tone == Tone.PRIMARY ? (dark ? 0xFFDCE6DF : 0xFF343C39)
                : tone == Tone.SELECTED ? (dark ? 0xFF343C37 : 0xFFE9EEEB)
                : isHoveredOrFocused() ? (dark ? 0xFF343C37 : 0xFFEEF1EF)
                : (dark ? 0xFF202523 : 0xFFFFFFFF);
        int text = !active ? (dark ? 0xFF667169 : 0xFFA6AEAA)
                : tone == Tone.DANGER ? (dark ? 0xFFE0A0A0 : 0xFFAF5555)
                : tone == Tone.PRIMARY ? (dark ? 0xFF253329 : 0xFFFFFFFF)
                : (dark ? 0xFFE1E7E3 : 0xFF303835);
        g.fill(getX(), getY(), getRight(), getBottom(), fill);
        if (isFocused()) g.renderOutline(getX(), getY(), getWidth(), getHeight(), dark ? 0xFF80998B : 0xFF89A896);
        var font = Minecraft.getInstance().font;
        String label = font.plainSubstrByWidth(getMessage().getString(), Math.max(1, getWidth() - 8));
        g.drawString(font, label, getX() + (getWidth() - font.width(label)) / 2,
                getY() + (getHeight() - 8) / 2, text, false);
    }
}
