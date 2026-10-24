package com.neko.mcbot.body;

import net.minecraft.world.phys.Vec3;

/** A task renews its input each tick; missing renewal releases the controls. */
public final class MovementInput {
    public record Frame(double x, double z, boolean jump, boolean sprint) {
        public static final Frame IDLE = new Frame(0, 0, false, false);

        public Frame(double x, double z, boolean jump) { this(x, z, jump, false); }

        public Vec3 relativeTo(float yaw) {
            double angle = Math.toRadians(yaw);
            return new Vec3(x * Math.cos(angle) + z * Math.sin(angle), 0,
                    -x * Math.sin(angle) + z * Math.cos(angle));
        }
    }

    private Frame pending = Frame.IDLE;

    public void renew(double x, double z, boolean jump) {
        renew(x, z, jump, false);
    }

    public void renew(double x, double z, boolean jump, boolean sprint) {
        if (!Double.isFinite(x) || !Double.isFinite(z)) throw new IllegalArgumentException("Invalid input");
        double scale = Math.max(1, Math.hypot(x, z));
        pending = new Frame(x / scale, z / scale, jump, sprint);
    }

    public Frame take() {
        Frame frame = pending;
        clear();
        return frame;
    }

    public void clear() { pending = Frame.IDLE; }
}
