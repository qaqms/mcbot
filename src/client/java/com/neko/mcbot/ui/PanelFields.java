package com.neko.mcbot.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

final class PanelFields {
    private PanelFields() {}

    static EditBox create(Font font, String label, String value, int maxLength, boolean secret) {
        EditBox field = secret
                ? new SecretField(font, Component.literal(label))
                : new EditBox(font, 0, 0, 100, PanelLayout.CONTROL_HEIGHT, Component.literal(label));
        // setValue truncates immediately at the vanilla default (32); raising the limit later cannot recover it.
        field.setMaxLength(Math.max(maxLength, value == null ? 0 : value.length()));
        field.setValue(value == null ? "" : value);
        return field;
    }

    private static final class SecretField extends EditBox {
        SecretField(Font font, Component label) {
            super(font, 0, 0, 100, PanelLayout.CONTROL_HEIGHT, label);
            addFormatter((text, position) ->
                    FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        }

        @Override
        protected MutableComponent createNarrationMessage() {
            return Component.translatable("gui.narrate.editBox", getMessage(), "********");
        }
    }
}
