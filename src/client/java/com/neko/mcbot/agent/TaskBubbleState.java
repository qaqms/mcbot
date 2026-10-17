package com.neko.mcbot.agent;

import com.neko.mcbot.agentcore.loop.AgentLoop;
import com.neko.mcbot.agentcore.loop.ItemGoal;

import java.util.TreeMap;

/** Display lifetime and in-flight primitives; never schedules work or interprets model prose. */
public final class TaskBubbleState {
    public enum Tone { WORKING, QUESTION, COMPLETED, FAILED, CANCELLED }
    public enum Activity {
        PROCESSING("正在处理任务"), MODEL("等待下一步"), COMPACTING("正在整理上下文"),
        OBSERVING("正在核对状态"), SEARCHING("正在查找资源"), MOVING("正在移动"),
        MINING("正在挖掘"), PLACING("正在放置"), CRAFTING("正在制作"),
        SMELTING("正在烧制"), EQUIPPING("正在整理装备"), WAITING("正在等待"),
        COLLECTING("正在拾取"), TRANSFERRING("正在搬运物品"), ATTACKING("正在战斗"),
        RECIPES("正在查看配方"), SURVEY("正在地下勘探"), RETURNING("正在沿路标返回");

        private final String label;
        Activity(String label) { this.label = label; }
        public String label() { return label; }
        static Activity tool(String name, boolean query) {
            return switch (name) {
                case "status", "inventory", "inspect_block" -> OBSERVING;
                case "scan_area", "find_resource" -> SEARCHING;
                case "move_to" -> MOVING;
                case "break_block" -> MINING;
                case "place_block" -> query ? OBSERVING : PLACING;
                case "build_nearby" -> PLACING;
                case "craft" -> query ? RECIPES : CRAFTING;
                case "smelt" -> query ? RECIPES : SMELTING;
                case "equip" -> EQUIPPING;
                case "wait" -> WAITING;
                case "collect" -> COLLECTING;
                case "transfer" -> TRANSFERRING;
                case "attack" -> ATTACKING;
                default -> PROCESSING;
            };
        }
    }

    public record Snapshot(long taskId, String title, Tone tone, String action, String detail,
                           ItemGoal.Progress progress, long remainingMs) {}
    private final TreeMap<Long, Activity> inFlight = new TreeMap<>();
    private long taskId;
    private String title = "";
    private Snapshot terminal;
    private long expiresAt;

    void begin(long id, String text) {
        taskId = id;
        title = clean(text, 256);
        terminal = null;
        inFlight.clear();
    }
    void sent(long id, long seq, Activity activity) {
        if (id != 0 && id == taskId && terminal == null) inFlight.put(seq, activity);
    }
    void settled(long id, long seq) { if (id == taskId) inFlight.remove(seq); }
    Activity primitive() { return inFlight.isEmpty() ? null : inFlight.lastEntry().getValue(); }
    void finish(long id, AgentLoop.TaskStatus status, String text, ItemGoal.Progress progress, long now) {
        if (taskId != id || terminal != null) return;
        Tone tone = switch (status) {
            case COMPLETED -> Tone.COMPLETED;
            case FAILED -> Tone.FAILED;
            default -> Tone.CANCELLED;
        };
        String action = switch (tone) {
            case COMPLETED -> progress == null ? "任务已结束" : "物品目标已核对";
            case FAILED -> "任务未完成";
            default -> status == AgentLoop.TaskStatus.SUPERSEDED ? "任务已替换" : "任务已取消";
        };
        expiresAt = now + switch (tone) { case COMPLETED -> 6000; case FAILED -> 10000; default -> 4000; };
        terminal = new Snapshot(id, title, tone, action,
                tone == Tone.COMPLETED ? "" : clean(text, 180), progress, 0);
        inFlight.clear();
    }
    Snapshot snapshot(long active, Activity activity, String detail, ItemGoal.Progress progress,
                      AgentRunner.QuestionSnapshot question, long now) {
        if (terminal != null) return now < expiresAt ? terminal : null;
        if (taskId == 0 || taskId != active) return null;
        if (question != null && question.taskId() == taskId)
            return new Snapshot(taskId, title, Tone.QUESTION,
                    question.authorization() ? "这一步需要确认" : "想问你一下",
                    clean(question.text(), 180), progress, question.remainingMs());
        return new Snapshot(taskId, title, Tone.WORKING, activity.label(), detail, progress, 0);
    }
    void clear() {
        taskId = 0;
        title = "";
        terminal = null;
        inFlight.clear();
    }
    static String clean(String raw, int limit) {
        if (raw == null) return "";
        String text = raw.replaceAll("§.", "").replaceAll("[\\p{Cc}\\p{Cf}]+", " ").strip();
        int count = text.codePointCount(0, text.length());
        return count <= limit ? text : text.substring(0, text.offsetByCodePoints(0, limit)) + "...";
    }
}
