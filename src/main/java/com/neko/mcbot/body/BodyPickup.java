package com.neko.mcbot.body;

import com.neko.mcbot.server.GroundItems;
import net.minecraft.world.phys.AABB;

import java.util.List;

/** Passive pickup belongs to the body tick, independent of model requests and task slots. */
final class BodyPickup {
    interface Contact {
        void touch();
    }

    interface Access {
        boolean active();
        boolean allowed();
        int experienceDelay();
        void experienceDelay(int ticks);
        AABB bounds();
        boolean loaded(AABB region);
        List<? extends Contact> items(AABB region);
        Contact experienceOrb(AABB region);
    }

    private BodyPickup() {}

    static void tick(Access body) {
        if (!body.active()) return;
        // Player.tick normally advances this clock; the fake body's physics does not call it.
        int delay = body.experienceDelay();
        if (delay > 0) body.experienceDelay(delay - 1);
        if (!body.allowed()) return;
        AABB region = GroundItems.touchBox(body.bounds());
        if (!body.loaded(region)) return;
        for (Contact item : body.items(region)) item.touch();
        if (body.experienceDelay() != 0 || !body.active() || !body.allowed()) return;
        Contact orb = body.experienceOrb(region);
        if (orb != null) orb.touch();
    }
}
