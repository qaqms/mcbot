package com.neko.mcbot.body;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BodyControlTest {
    @Test void reflexWinsAndReturningNeverRevivesReleasedWork() {
        var releases = new ArrayList<BodyControl.Owner>();
        var control = new BodyControl(releases::add);
        Object work = new Object(), replacement = new Object();
        assertTrue(control.claim(work));
        assertFalse(control.claim(replacement));
        assertTrue(control.owns(work));
        control.reflex(true);
        control.reflex(true);
        assertFalse(control.owns(work));
        assertFalse(control.claim(replacement));
        control.release(work);
        control.reflex(false);
        assertEquals(BodyControl.Owner.IDLE, control.owner());
        assertEquals(List.of(BodyControl.Owner.IDLE, BodyControl.Owner.WORK, BodyControl.Owner.REFLEX), releases);
        assertTrue(control.claim(replacement));
        control.release(work);
        assertTrue(control.owns(replacement), "late cleanup cannot release the replacement");
    }

    @Test void preemptionKeepsSameWorkIdentityUntilExplicitRelease() {
        var control = new BodyControl(previous -> {});
        Object work = new Object();
        assertTrue(control.claim(work));
        for (int i = 0; i < 3; i++) {
            control.reflex(true);
            control.reflex(false);
            assertTrue(control.owns(work));
        }
        control.release(work);
        assertEquals(BodyControl.Owner.IDLE, control.owner());
    }
}
