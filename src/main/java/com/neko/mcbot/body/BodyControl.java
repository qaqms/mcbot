package com.neko.mcbot.body;

import java.util.Objects;
import java.util.function.Consumer;

/** One transient driver. Passive physics and native touch pickup do not compete for input. */
public final class BodyControl {
    public enum Owner { IDLE, WORK, REFLEX }

    private final Consumer<Owner> release;
    private Object work;
    private boolean reflex;
    private Owner owner = Owner.IDLE;

    public BodyControl(Consumer<Owner> release) {
        this.release = Objects.requireNonNull(release);
    }

    public Owner owner() { return owner; }

    public boolean claim(Object token) {
        Objects.requireNonNull(token);
        if (reflex || work != null) return false;
        work = token;
        try {
            select();
        } catch (RuntimeException | Error failure) {
            work = null;
            owner = reflex ? Owner.REFLEX : Owner.IDLE;
            throw failure;
        }
        return true;
    }

    public void release(Object token) {
        if (work != token) return;
        work = null;
        select();
    }

    public void reflex(boolean active) {
        reflex = active;
        select();
    }

    public boolean owns(Object token) { return work == token && owner == Owner.WORK; }

    private void select() {
        Owner next = reflex ? Owner.REFLEX : work != null ? Owner.WORK : Owner.IDLE;
        if (next == owner) return;
        Owner previous = owner;
        // Commit ownership before cleanup: callbacks may cancel or complete the old work.
        owner = next;
        release.accept(previous);
    }
}
