package com.neko.mcbot.body;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Passive body motion runs even when no tool owns the companion. */
final class CompanionPhysics {
    interface Access {
        boolean active();
        AABB bounds();
        Vec3 velocity();
        boolean loaded(AABB region);
        Vec3 position();
        void updateFluids();
        void travel(Vec3 input);
        void applyBlockEffects();
        void checkFall(Vec3 movement);
        void recordMovement(Vec3 movement);
    }

    private CompanionPhysics() {}

    static void tick(Access body) {
        if (!body.active()) return;
        // Native movement may inspect the support block and adjacent collision/fluid cells.
        if (!body.loaded(body.bounds().expandTowards(body.velocity()).inflate(1))) return;
        Vec3 before = body.position();
        body.updateFluids();
        body.travel(Vec3.ZERO);
        body.applyBlockEffects();
        Vec3 movement = body.position().subtract(before);
        // Server players normally receive this settlement from movement packets.
        body.checkFall(movement);
        body.recordMovement(movement);
    }
}
