package com.neko.mcbot.body;

import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BodyContinuityTest {
    private UUID load(String value) {
        var output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        if (value != null) output.putString("mcbot_body_continuity", value);
        return CompanionPlayer.readContinuity(TagValueInput.create(
                ProblemReporter.DISCARDING, net.minecraft.core.RegistryAccess.EMPTY, output.buildResult()));
    }
    @Test void canonicalNativeSavedIdentitySurvivesReloadWithoutSharingTheTransientBodyToken() {
        var identity = UUID.randomUUID();
        assertEquals(identity, load(identity.toString()));
        assertEquals(identity, load(identity.toString()));
    }
    @Test void missingMalformedAndNoncanonicalIdentitiesCannotInheritTheOldLineage() {
        assertNotEquals(load(null), load(null));
        assertNotEquals(load("invalid"), load("invalid"));
        var identity = UUID.fromString("abcdef00-1234-5678-90ab-000000000001");
        assertNotEquals(identity, load(identity.toString().toUpperCase(java.util.Locale.ROOT)));
    }
}
