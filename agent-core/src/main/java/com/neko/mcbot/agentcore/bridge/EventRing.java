package com.neko.mcbot.agentcore.bridge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 桥接事件环形缓冲：自增 id + 只保最近 capacity 条。
 * SSE 断线重连按 Last-Event-ID 用 since() 补发，丢多不丢少（宁可重发不可漏发）。
 */
public final class EventRing {

    public record Event(long id, String type, String dataJson) {
    }

    private final int capacity;
    private final ArrayDeque<Event> buf = new ArrayDeque<>();
    private long nextId = 1;

    public EventRing(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public synchronized Event add(String type, String dataJson) {
        Event e = new Event(nextId++, type, dataJson);
        buf.addLast(e);
        while (buf.size() > capacity) {
            buf.removeFirst();
        }
        return e;
    }

    public synchronized long lastId() {
        return nextId - 1;
    }

    /** id 大于 after 的全部事件（含已被挤出的区间无法补发，返回列表从可用处开始）。 */
    public synchronized List<Event> since(long after) {
        List<Event> out = new ArrayList<>();
        for (Event e : buf) {
            if (e.id() > after) {
                out.add(e);
            }
        }
        return out;
    }
}
