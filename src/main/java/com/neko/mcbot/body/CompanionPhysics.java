package com.neko.mcbot.body;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Native body motion runs even when no tool owns the companion. */
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
        default MovementInput.Frame takeInput() { return MovementInput.Frame.IDLE; }
        default float yaw() { return 0; }
        default boolean grounded() { return false; }
        default void jump() {}
    }

    private CompanionPhysics() {}

    static void tick(Access body) {
        MovementInput.Frame input = body.takeInput();
        if (!body.active()) return;
        // Native movement may inspect the support block and adjacent collision/fluid cells.
        if (!body.loaded(body.bounds().expandTowards(body.velocity()).inflate(1))) return;
        Vec3 before = body.position();
        body.updateFluids();
        if (input.jump() && body.grounded()) body.jump();
        body.travel(input.relativeTo(body.yaw()));
        body.applyBlockEffects();
        Vec3 movement = body.position().subtract(before);
        // Server players normally receive this settlement from movement packets.
        body.checkFall(movement);
        body.recordMovement(movement);
    }
}
