package com.neko.mcbot.ui;

import com.neko.mcbot.agent.TaskBubbleState;

/** Presentation time only: no task, question, or permission deadline is changed here. */
final class TaskBubbleMotion {
    static final long LETTER_MS = 140, FADE_MS = 300, SPIN_MS = 2400, CAT_MS = 5600;
    record Sample(String text, boolean typing, boolean caret, float opacity, float rotation, int catFrame) {}
    private long taskId, started;
    private TaskBubbleState.Tone tone;
    private String text = "", question = "";

    Sample sample(TaskBubbleState.Snapshot state, String message, long now) {
        String currentQuestion = state.tone() == TaskBubbleState.Tone.QUESTION ? state.detail() : "";
        if (taskId != state.taskId() || tone != state.tone() || !text.equals(message)
                || !question.equals(currentQuestion)) {
            taskId = state.taskId();
            tone = state.tone();
            text = message;
            question = currentQuestion;
            started = now;
        }
        long elapsed = Math.max(0, now - started);
        int count = text.codePointCount(0, text.length());
        int visible = (int) Math.min(count, 1 + elapsed / LETTER_MS);
        String shown = text.substring(0, text.offsetByCodePoints(0, visible));
        boolean typing = visible < count;
        float fade = Math.min(1, (elapsed + 60) / (float) FADE_MS);
        float opacity = 1 - (1 - fade) * (1 - fade);
        return new Sample(shown, typing, typing && elapsed / 600 % 2 == 0, opacity,
                rotation(elapsed), catFrame(elapsed));
    }

    static float rotation(long ms) {
        return Math.floorMod(ms, SPIN_MS) / (float) SPIN_MS * (float) (Math.PI * 2);
    }

    static int catFrame(long ms) {
        long phase = Math.floorMod(ms, CAT_MS);
        if (phase < 1000) return 0;
        if (phase < 1600) return 1;
        if (phase < 2100) return 2;
        if (phase < 2500) return 3;
        if (phase < 2900) return 2;
        if (phase < 3300) return 3;
        if (phase < 4100) return 1;
        if (phase < 4700) return 4;
        return 0;
    }

    void clear() {
        taskId = 0;
        tone = null;
        text = "";
        question = "";
    }
}
