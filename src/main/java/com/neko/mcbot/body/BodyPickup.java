package com.neko.mcbot.body;

import com.neko.mcbot.server.GroundItems;
import net.minecraft.world.phys.AABB;

import java.util.List;

/** Item contact belongs to the body tick, independent of model requests and task slots. */
final class BodyPickup {
    interface Item {
        void touch();
    }

    interface Access {
        boolean active();
        boolean allowed();
        AABB bounds();
        boolean loaded(AABB region);
        List<? extends Item> items(AABB region);
    }

    private BodyPickup() {}

    static void tick(Access body) {
        if (!body.active() || !body.allowed()) return;
        AABB region = GroundItems.touchBox(body.bounds());
        if (!body.loaded(region)) return;
        for (Item item : body.items(region)) item.touch();
    }
}
