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
        default MovementInput.Frame recoveryInput() { return null; }
        default boolean inWater() { return false; }
        default void swimUp() {}
        default void refreshEyes() {}
        default Object worldIdentity() { return this; }
        default void unavailable() {}
        default void afterMovement() {}
    }

    private CompanionPhysics() {}

    static void tick(Access body) {
        MovementInput.Frame input = body.takeInput();
        if (!body.active()) { body.unavailable(); return; }
        // Native movement may inspect the support block and adjacent collision/fluid cells.
        if (!body.loaded(body.bounds().expandTowards(body.velocity()).inflate(1))) {
            body.unavailable();
            return;
        }
        Vec3 before = body.position();
        Object world = body.worldIdentity();
        body.updateFluids();
        // Native environmental effects can kill the body or enter a portal before movement.
        if (!body.active() || body.worldIdentity() != world
                || !body.loaded(body.bounds().expandTowards(body.velocity()).inflate(1))) {
            body.unavailable();
            return;
        }
        MovementInput.Frame recovery = body.recoveryInput();
        if (recovery != null) input = recovery;
        if (input.jump()) {
            if (body.inWater()) body.swimUp();
            else if (body.grounded()) body.jump();
        }
        body.travel(input.relativeTo(body.yaw()));
        body.applyBlockEffects();
        body.refreshEyes();
        Vec3 movement = body.position().subtract(before);
        // Server players normally receive this settlement from movement packets.
        body.checkFall(movement);
        body.recordMovement(movement);
        body.afterMovement();
    }
}
