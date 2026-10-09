package com.neko.mcbot.ui;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PanelFieldsTest {
    @Test
    void vanillaLimitReproducesTheReloadTruncation() {
        EditBox field = new EditBox(null, 0, 0, 200, 20, Component.literal("model"));
        String model = "custom-model-with-a-long-expiration-suffix";
        field.setValue(model);
        field.setMaxLength(256);
        assertEquals(model.substring(0, 32), field.getValue());
    }

    @Test
    void longModelSurvivesInitializationAndResize() {
        String value = "custom-model-with-a-long-expiration-suffix";
        EditBox field = PanelFields.create(null, "model", value, 1024, false);
        field.setRectangle(140, 20, 8, 12);
        field.setRectangle(320, 20, 16, 60);
        assertEquals(value, field.getValue());
    }

    @Test
    void longCredentialIsNotTruncatedOnReload() {
        String synthetic = "synthetic-credential-" + "x".repeat(180);
        EditBox field = PanelFields.create(null, "credential", synthetic, 4096, true);
        assertEquals(synthetic, field.getValue());
        field.setValue(synthetic + "-edited");
        assertEquals(synthetic + "-edited", field.getValue());
    }

    @Test
    void existingLongValuesAreNeverSilentlyShortened() {
        String value = "x".repeat(3000);
        EditBox field = PanelFields.create(null, "endpoint", value, 2048, false);
        assertEquals(value, field.getValue());
    }

    @Test
    void taskInputDoesNotUseTheVanilla32CharacterLimit() {
        EditBox field = PanelFields.create(null, "task", "", 4096, false);
        String value = "A complete test instruction with several coordinates and conditions.";
        field.setValue(value);
        assertEquals(value, field.getValue());
    }

    @Test
    void keyNarrationNeverReadsTheCredentialAloud() {
        EditBox field = PanelFields.create(null, "credential", "synthetic-private-value", 4096, true);
        var narration = assertDoesNotThrow(() -> {
            var method = field.getClass().getDeclaredMethod("createNarrationMessage");
            method.setAccessible(true);
            return ((Component) method.invoke(field)).getString();
        });
        assertFalse(narration.contains("synthetic-private-value"));
    }

    @Test
    void formatterMasksTheDisplayWithoutChangingTheStoredValue() {
        String value = "synthetic-private-value";
        EditBox field = PanelFields.create(null, "credential", value, 4096, true);
        var rendered = assertDoesNotThrow(() -> {
            var method = EditBox.class.getDeclaredMethod("applyFormat", String.class, int.class);
            method.setAccessible(true);
            var sequence = (FormattedCharSequence) method.invoke(field, value, 0);
            StringBuilder result = new StringBuilder();
            sequence.accept((index, style, codePoint) -> {
                result.appendCodePoint(codePoint);
                return true;
            });
            return result.toString();
        });
        assertEquals("*".repeat(value.length()), rendered);
        assertEquals(value, field.getValue());
    }
}
