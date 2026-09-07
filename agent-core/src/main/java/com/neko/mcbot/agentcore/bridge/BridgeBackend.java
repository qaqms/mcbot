package com.neko.mcbot.agentcore.bridge;

import java.util.concurrent.CompletableFuture;

/**
 * 桥接的服务端实现面（由 MC 客户端侧注入）。全部"任务级"语义——
 * 原子游戏操作不经过这层，猫娘/外部大脑是老板不是操作员（设计 §9）。
 */
public interface BridgeBackend {

    /** 投递一条指令给大脑，返回分配的 task_id（事件流按它归组）。 */
    long submitTask(String text);

    /** 问一句话，等大脑的下一次作答。超时由 BridgeService 统一控制。 */
    CompletableFuture<String> ask(String text);

    /** 回答同伴的反问（ask_owner 产生的 question_id）。无此问题时返回 false。 */
    boolean answer(String questionId, String text);

    /** 同伴/大脑状态，已序列化好的 JSON 对象字符串。 */
    String statusJson();

    /** 叫停大脑当前这条链（v1 全局一条链，task_id 仅日志用）。 */
    boolean cancel(long taskId);
}
