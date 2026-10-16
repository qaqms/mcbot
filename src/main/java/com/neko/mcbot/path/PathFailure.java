package com.neko.mcbot.path;

/** One observed rejection; it does not establish that all routes fail for this reason. */
public record PathFailure(int step, String reason, long from, long to, Long cell) {
    public String summary() {
        return "step=" + step + " reason=" + reason + " from=" + coordinates(from)
                + " to=" + coordinates(to) + (cell == null ? "" : " cell=" + coordinates(cell));
    }

    private static String coordinates(long cell) {
        return DigAStar.unpackX(cell) + "," + DigAStar.unpackY(cell) + "," + DigAStar.unpackZ(cell);
    }
}
