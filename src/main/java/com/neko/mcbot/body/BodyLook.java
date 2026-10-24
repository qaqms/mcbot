package com.neko.mcbot.body;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Exact work aim, retaining continuous yaw across the -180/180 boundary. */
public final class BodyLook {
    private BodyLook() { }

    public static void face(Entity body, Vec3 point) {
        Vec3 delta = point.subtract(body.getEyePosition());
        if (delta.lengthSqr() < 1e-10) return;
        double horizontal = Math.hypot(delta.x, delta.z);
        if (horizontal > 1e-5) {
            float desired = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90;
            body.setYRot(body.getYRot() + Mth.wrapDegrees(desired - body.getYRot()));
        }
        body.setXRot((float) -Math.toDegrees(Math.atan2(delta.y, horizontal)));
        body.setYHeadRot(body.getYRot());
        body.setYBodyRot(body.getYRot());
    }
}
