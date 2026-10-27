package com.neko.mcbot.ui;

import com.neko.mcbot.agent.TaskBubbleState;
import com.neko.mcbot.agentcore.loop.ItemGoal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** Measure the complete message before revealing letters, so typing never resizes the bubble. */
final class TaskBubbleLayout {
    static final int MIN_WIDTH = 112, MAX_WIDTH = 232, PADDING = 12, ROW_HEIGHT = 28, TAIL = 4;
    enum Ink { PRIMARY, SECONDARY, ACCENT }
    record Line(String text, int x, int y, Ink ink) {}
    record Layout(int width, int height, List<Line> lines, boolean spinning, int spinnerX, int caretX) {
        Line message() { return lines.getFirst(); }
        Layout reveal(String text, ToIntFunction<String> measure) {
            var visible = new ArrayList<>(lines);
            visible.set(0, new Line(text, message().x(), message().y(), message().ink()));
            return new Layout(width, height, List.copyOf(visible), spinning, spinnerX,
                    message().x() + measure.applyAsInt(text) + 2);
        }
    }
    private TaskBubbleLayout() {}

    static Layout create(TaskBubbleState.Snapshot state, ToIntFunction<String> measure) {
        boolean spinning = state.tone() == TaskBubbleState.Tone.WORKING && state.modelWaiting();
        String meta = state.tone() == TaskBubbleState.Tone.QUESTION ? time(state.remainingMs())
                : spinning ? "" : quantity(state.progress());
        String detail = switch (state.tone()) {
            case QUESTION, FAILED -> state.detail();
            default -> "";
        };
        int accessory = spinning ? 16 : meta.isEmpty() ? 0 : measure.applyAsInt(meta) + 12;
        int messageLimit = Math.max(0, MAX_WIDTH - PADDING * 2 - accessory);
        String message = fit(state.action(), messageLimit, measure);
        String preview = fit(detail, MAX_WIDTH - PADDING * 2, measure);
        int content = measure.applyAsInt(message) + accessory;
        int width = Math.clamp(Math.max(content, measure.applyAsInt(preview)) + PADDING * 2,
                MIN_WIDTH, MAX_WIDTH);
        int height = ROW_HEIGHT + (preview.isEmpty() ? 0 : 14);
        var lines = new ArrayList<Line>();
        lines.add(new Line(message, PADDING, 10, Ink.PRIMARY));
        if (!meta.isEmpty())
            lines.add(new Line(meta, width - PADDING - measure.applyAsInt(meta), 10, Ink.ACCENT));
        if (!preview.isEmpty())
            lines.add(new Line(preview, PADDING, 25, Ink.SECONDARY));
        return new Layout(width, height, List.copyOf(lines), spinning,
                PADDING + measure.applyAsInt(message) + 9, PADDING + measure.applyAsInt(message) + 2);
    }

    private static String quantity(ItemGoal.Progress progress) {
        if (progress == null) return "";
        String scope = progress.scope() == ItemGoal.Scope.SUBTASK ? "本段" : "目标";
        String kind = progress.alternatives() ? "来源合计" : progress.additional() ? "净增" : "总量";
        return scope + kind + " " + progress.current() + "/" + progress.target();
    }
    static String fit(String text, int width, ToIntFunction<String> measure) {
        if (measure.applyAsInt(text) <= width) return text;
        String suffix = "...";
        if (measure.applyAsInt(suffix) > width) return "";
        int end = text.length();
        while (end > 0 && measure.applyAsInt(text.substring(0, end) + suffix) > width)
            end = text.offsetByCodePoints(end, -1);
        return text.substring(0, end) + suffix;
    }
    private static String time(long ms) {
        long seconds = Math.max(0, ms / 1000 + (ms % 1000 > 0 ? 1 : 0));
        return (seconds / 60) + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
    }
}
