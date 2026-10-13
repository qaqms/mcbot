package com.neko.mcbot.ui;

import com.neko.mcbot.McbotClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public final class CompanionHud {
    private CompanionHud() {}
    public static void render(GuiGraphics graphics) {
        var mc = Minecraft.getInstance();
        var runner = McbotClient.runner();
        if (runner == null || mc.screen != null || mc.options.hideGui || !runner.config().preferences.showHud()) return;
        var question = runner.questionSnapshot();
        if (runner.currentTaskId() == 0 && question == null) return;
        String name = runner.companionName().isEmpty() ? "同伴" : runner.companionName();
        String text = name + " · " + (question == null ? runner.taskState()
                : "等待回答 " + McbotPanelScreen.formatTime(question.remainingMs()));
        text = mc.font.plainSubstrByWidth(text, Math.max(40, graphics.guiWidth() - 32));
        int w = mc.font.width(text), x = (graphics.guiWidth() - w) / 2, y = graphics.guiHeight() - 66;
        graphics.fill(x - 6, y - 4, x + w + 6, y + 12, 0xDD222A26);
        graphics.drawString(mc.font, text, x, y, question == null ? 0xFFE4EDE7 : 0xFFE6C78E, false);
    }
}
