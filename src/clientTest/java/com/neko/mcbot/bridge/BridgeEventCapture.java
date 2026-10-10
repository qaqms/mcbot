package com.neko.mcbot.bridge;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Captures the same event bus used by the HTTP bridge without opening a listener. */
public final class BridgeEventCapture implements AutoCloseable {
    private final List<JsonObject> events = new ArrayList<>();
    private final BridgeEvents.Sink sink = (type, data) -> events.add(data.deepCopy());

    public BridgeEventCapture() {
        BridgeEvents.attach(sink);
    }

    public List<JsonObject> events() {
        return List.copyOf(events);
    }

    @Override
    public void close() {
        BridgeEvents.detach(sink);
    }
}
