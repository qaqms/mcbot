package com.neko.mcbot.ui;

final class TaskBubbleVisibility {
    static final double MAX_DISTANCE = 24;
    private TaskBubbleVisibility() {}
    static float opacity(double distance, boolean inFront, boolean inFrustum, boolean unobstructed) {
        if (!Double.isFinite(distance) || distance < 0 || distance >= MAX_DISTANCE
                || !inFront || !inFrustum || !unobstructed) return 0;
        return (float) Math.min(1, (MAX_DISTANCE - distance) / 4);
    }
    static float scale(double distance) {
        return (float) (.012 * Math.clamp(distance / 4, 0, 1));
    }
}
