package com.neko.mcbot.path;

/** Chooses walking input from measured motion; never assigns a body position or velocity. */
public final class PathSteering {
    public record Body(double x, double y, double z, double vx, double vz, boolean grounded) {}
    public record Command(double x, double z, boolean jump, boolean arrived, boolean stuck) {}
    public record Facing(double x, double z) {}

    private final double x, y, z;
    private final boolean jumping;
    private double best = Double.POSITIVE_INFINITY;
    private int staleTicks;
    private int ticks;
    private boolean descending;
    private Facing entry;

    public PathSteering(int x, int y, int z) {
        this(x, y, z, true);
    }

    public PathSteering(int x, double y, int z, boolean jumping) {
        this.x = x + .5;
        this.y = y;
        this.z = z + .5;
        this.jumping = jumping;
    }

    public Command tick(Body body) {
        return tick(body, false);
    }

    public Command tick(Body body, boolean through) {
        double dx = x - body.x(), dz = z - body.z();
        double distance = Math.hypot(dx, dz);
        double height = y - body.y();
        if (entry == null && distance > .3) entry = new Facing(dx / distance, dz / distance);
        descending |= height < -.08;
        if (settled(body) || through && body.grounded()
                && distance <= (descending ? .45 : .3) && Math.abs(height) <= .08)
            return new Command(0, 0, false, true, false);

        double remaining = distance + Math.abs(height);
        if (remaining < best - .03) {
            best = remaining;
            staleTicks = 0;
        } else staleTicks++;
        boolean stuck = ++ticks > 120 || staleTicks > 60;
        if (stuck) return new Command(0, 0, false, false, true);

        // Descents need air braking to meet the landing rather than overshoot and turn back.
        boolean cruise = through && height >= -.08;
        double ix = cruise ? dx / Math.max(distance, 1e-6) : dx * 3 - body.vx() * 5;
        double iz = cruise ? dz / Math.max(distance, 1e-6) : dz * 3 - body.vz() * 5;
        // Keep a little forward air input over a validated landing instead of turning back.
        if (through && entry != null && !body.grounded() && height < -.08 && distance <= .45) {
            ix = entry.x() * .2;
            iz = entry.z() * .2;
        }
        double scale = Math.max(1, Math.hypot(ix, iz));
        boolean jump = jumping && body.grounded() && height > .3 && distance <= 1.5;
        return new Command(ix / scale, iz / scale, jump, false, false);
    }

    public boolean settled(Body body) {
        return body.grounded() && Math.hypot(x - body.x(), z - body.z()) <= .16
                && Math.abs(y - body.y()) <= .08 && Math.hypot(body.vx(), body.vz()) <= .04;
    }

    /** Braking and tiny landing corrections must not reverse the work/movement heading. */
    public Facing facing(Body body) {
        double dx = x - body.x(), dz = z - body.z();
        double distance = Math.hypot(dx, dz);
        return distance <= (descending ? .45 : .3) ? null : new Facing(dx / distance, dz / distance);
    }
}
