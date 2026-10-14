package com.neko.mcbot.path;

/** Chooses walking input from measured motion; never assigns a body position or velocity. */
public final class PathSteering {
    public record Body(double x, double y, double z, double vx, double vz, boolean grounded) {}
    public record Command(double x, double z, boolean jump, boolean arrived, boolean stuck) {}

    private final double x, y, z;
    private double best = Double.POSITIVE_INFINITY;
    private int staleTicks;
    private int ticks;

    public PathSteering(int x, int y, int z) {
        this.x = x + .5;
        this.y = y;
        this.z = z + .5;
    }

    public Command tick(Body body) {
        return tick(body, false);
    }

    public Command tick(Body body, boolean through) {
        double dx = x - body.x(), dz = z - body.z();
        double distance = Math.hypot(dx, dz);
        double height = y - body.y();
        if (settled(body) || through && body.grounded() && distance <= .3 && Math.abs(height) <= .08)
            return new Command(0, 0, false, true, false);

        double remaining = distance + Math.abs(height);
        if (remaining < best - .03) {
            best = remaining;
            staleTicks = 0;
        } else staleTicks++;
        boolean stuck = ++ticks > 120 || staleTicks > 60;
        if (stuck) return new Command(0, 0, false, false, true);

        // Brake with input, retaining native friction, air control and external impulses.
        double ix = through ? dx / Math.max(distance, 1e-6) : dx * 3 - body.vx() * 5;
        double iz = through ? dz / Math.max(distance, 1e-6) : dz * 3 - body.vz() * 5;
        double scale = Math.max(1, Math.hypot(ix, iz));
        boolean jump = body.grounded() && height > .3 && distance <= 1.5;
        return new Command(ix / scale, iz / scale, jump, false, false);
    }

    public boolean settled(Body body) {
        return body.grounded() && Math.hypot(x - body.x(), z - body.z()) <= .16
                && Math.abs(y - body.y()) <= .08 && Math.hypot(body.vx(), body.vz()) <= .04;
    }
}
