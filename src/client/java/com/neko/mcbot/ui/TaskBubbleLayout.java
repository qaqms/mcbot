package com.neko.mcbot.ui;

import com.neko.mcbot.agent.TaskBubbleState;
import com.neko.mcbot.agentcore.loop.ItemGoal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** Fixed world-pixel geometry, measured with the game's current font during extraction. */
final class TaskBubbleLayout {
    static final int WIDTH = 184, HEIGHT = 88, PADDING = 8;
    enum Ink { PRIMARY, SECONDARY, ACCENT }
    record Line(String text, int x, int y, Ink ink) {}
    record Layout(List<Line> lines, float progress, boolean spinning) {}
    private TaskBubbleLayout() {}

    static Layout create(TaskBubbleState.Snapshot state, String name, String item,
                         ToIntFunction<String> measure) {
        var lines = new ArrayList<Line>();
        String badge = switch (state.tone()) {
            case WORKING -> "进行中";
            case QUESTION -> time(state.remainingMs());
            case COMPLETED -> "已结束";
            case FAILED -> "未完成";
            case CANCELLED -> "已停止";
        };
        int badgeX = WIDTH - PADDING - measure.applyAsInt(badge);
        lines.add(new Line(fit(name, badgeX - PADDING - 10, measure), PADDING, 7, Ink.SECONDARY));
        lines.add(new Line(badge, badgeX, 7, Ink.ACCENT));
        boolean spinning = state.tone() == TaskBubbleState.Tone.WORKING && state.modelWaiting();
        int actionX = PADDING + (spinning ? 14 : 0);
        lines.add(new Line(fit(state.action(), WIDTH - PADDING - actionX, measure), actionX, 22, Ink.PRIMARY));
        var details = wrap(state.detail(), WIDTH - 2 * PADDING, measure);
        for (int i = 0; i < details.size(); i++)
            lines.add(new Line(details.get(i), PADDING, 36 + i * 10, Ink.SECONDARY));
        ItemGoal.Progress progress = state.progress();
        if (progress != null) {
            String count = progress.current() + "/" + progress.target();
            String prefix = progress.scope() == ItemGoal.Scope.SUBTASK ? "本段" : "目标";
            String kind = progress.additional() ? "净增" : "总量";
            String label = prefix + " " + (progress.alternatives() ? "配方来源合计" : item);
            int countWidth = measure.applyAsInt(kind + " " + count);
            lines.add(new Line(fit(label, WIDTH - 3 * PADDING - countWidth, measure),
                    PADDING, 59, Ink.SECONDARY));
            lines.add(new Line(kind + " " + count, WIDTH - PADDING - countWidth, 59, Ink.ACCENT));
        }
        lines.add(new Line(fit(state.title(), WIDTH - 2 * PADDING, measure), PADDING, 75, Ink.SECONDARY));
        return new Layout(List.copyOf(lines), progress == null ? -1
                : Math.clamp((float) progress.current() / progress.target(), 0, 1), spinning);
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
    private static List<String> wrap(String text, int width, ToIntFunction<String> measure) {
        if (text.isEmpty()) return List.of();
        int end = 0;
        while (end < text.length()) {
            int next = text.offsetByCodePoints(end, 1);
            if (measure.applyAsInt(text.substring(0, next)) > width) break;
            end = next;
        }
        if (end == text.length()) return List.of(text);
        return List.of(text.substring(0, end), fit(text.substring(end), width, measure));
    }
    private static String time(long ms) {
        long seconds = Math.max(0, (ms + 999) / 1000);
        return (seconds / 60) + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
    }
}
