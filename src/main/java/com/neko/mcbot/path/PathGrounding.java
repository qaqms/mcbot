package com.neko.mcbot.path;

/** Keeps an old path from restoring the height lost to passive falling. */
public final class PathGrounding {
    public enum Decision { WAIT, READY, REPLAN }

    private Double expectedFeetY;

    public Decision poll(boolean onGround, double feetY) {
        if (!onGround) return Decision.WAIT;
        boolean displaced = expectedFeetY != null && Math.abs(expectedFeetY - feetY) > .08;
        expectedFeetY = feetY;
        return displaced ? Decision.REPLAN : Decision.READY;
    }

    public void committed(double feetY) {
        expectedFeetY = feetY;
    }
}
