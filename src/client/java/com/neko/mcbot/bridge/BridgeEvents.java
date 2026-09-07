package com.neko.mcbot.bridge;

import com.google.gson.JsonObject;

/**
 * 事件生产者总线：大脑/网络层往这里 publish，桥接起来后转发给环形缓冲与 SSE 订阅者。
 * 静态挂载点（与 AgentRunner 生命周期解耦）——桥没起时 publish 是空操作。
 */
public final class BridgeEvents {

    public interface Sink {
        void event(String type, JsonObject data);
    }

    private static volatile Sink sink;

    private BridgeEvents() {
    }

    static void attach(Sink s) {
        sink = s;
    }

    static void detach(Sink s) {
        if (sink == s) {
            sink = null;
        }
    }

    public static void publish(String type, JsonObject data) {
        Sink s = sink;
        if (s != null) {
            try {
                s.event(type, data);
            } catch (RuntimeException ignored) {
                // 事件生产永不反噬业务
            }
        }
    }
}
