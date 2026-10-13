package com.neko.mcbot.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;

final class PanelToggle extends Button {
    private final BooleanSupplier selected;
    PanelToggle(String label, PanelLayout.Rect bounds, BooleanSupplier selected, Runnable action) {
        super(bounds.x(), bounds.y(), bounds.width(), bounds.height(), Component.literal(label),
                b -> action.run(), DEFAULT_NARRATION);
        this.selected = selected;
    }
    @Override protected void renderContents(GuiGraphics g, int x, int y, float delta) {
        boolean on = selected.getAsBoolean();
        int left = getX() + (getWidth() - 26) / 2, top = getY() + (getHeight() - 12) / 2;
        g.fill(left, top, left + 26, top + 12, !active ? 0xFFB9C0BC : on ? 0xFF629179 : 0xFFCDD5D0);
        int thumb = left + (on ? 15 : 3);
        g.fill(thumb, top + 3, thumb + 8, top + 9, 0xFFFFFFFF);
        if (isFocused()) g.renderOutline(left - 2, top - 2, 30, 16, 0xFF89A896);
    }
    @Override protected net.minecraft.network.chat.MutableComponent createNarrationMessage() {
        return Component.literal(getMessage().getString() + (selected.getAsBoolean() ? "，已开启" : "，已关闭"));
    }
}
