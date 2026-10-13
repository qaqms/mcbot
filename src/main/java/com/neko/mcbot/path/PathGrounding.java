package com.neko.mcbot.path;

/** Keeps an old path from restoring the height lost to passive falling. */
public final class PathGrounding {
    public enum Decision { WAIT, READY, REPLAN }

    private Integer expectedFeetY;

    public Decision poll(boolean onGround, int feetY) {
        if (!onGround) return Decision.WAIT;
        boolean displaced = expectedFeetY != null && expectedFeetY != feetY;
        expectedFeetY = feetY;
        return displaced ? Decision.REPLAN : Decision.READY;
    }

    public void committed(int feetY) {
        expectedFeetY = feetY;
    }
}
