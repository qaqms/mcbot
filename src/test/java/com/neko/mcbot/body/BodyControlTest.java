package com.neko.mcbot.body;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BodyControlTest {
    @Test void dormancyParksOriginalTokenWithoutYieldingItsReservation() {
        var releases = new ArrayList<BodyControl.Owner>();
        var control = new BodyControl(releases::add);
        Object token = new Object(), other = new Object();
        assertTrue(control.claim(token));
        control.dormant(token, true);
        assertEquals(BodyControl.Owner.IDLE, control.owner());
        assertFalse(control.owns(token));
        assertFalse(control.claim(other));
        control.dormant(other, false);
        assertEquals(BodyControl.Owner.IDLE, control.owner());
        control.reflex(true);
        control.dormant(token, false);
        assertEquals(BodyControl.Owner.REFLEX, control.owner());
        control.reflex(false);
        assertTrue(control.owns(token));
        control.dormant(token, true);
        control.release(token);
        control.dormant(token, false);
        assertEquals(BodyControl.Owner.IDLE, control.owner());
        assertTrue(control.claim(other));
        assertEquals(2, releases.stream().filter(o -> o == BodyControl.Owner.WORK).count());
    }

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

    @Test void cleanupFailureWhileClaimingCannotPermanentlyLeaseTheBody() {
        var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        var control = new BodyControl(previous -> {
            if (fail.getAndSet(false)) throw new IllegalStateException("native release hook");
        });
        assertThrows(IllegalStateException.class, () -> control.claim(new Object()));
        assertEquals(BodyControl.Owner.IDLE, control.owner());
        Object next = new Object();
        assertTrue(control.claim(next));
        assertTrue(control.owns(next));
    }
}
