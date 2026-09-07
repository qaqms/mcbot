package com.neko.mcbot.agentcore.harness;

import com.neko.mcbot.agentcore.llm.ChatEngine;
import com.neko.mcbot.agentcore.llm.LlmClient;
import com.neko.mcbot.agentcore.llm.ToolSpec;
import com.neko.mcbot.agentcore.loop.AgentLoop;
import com.neko.mcbot.agentcore.loop.ToolExecutor;
import com.neko.mcbot.agentcore.prompt.PromptBuilder;
import com.neko.mcbot.agentcore.provider.OpenAiCompatProvider;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * M2 验收 harness：零 MC 依赖的命令行回路（本地工具 now / add）。
 * 用法（环境变量给端点与 key）：
 *   MCBOT_BASE_URL=https://api.deepseek.com MCBOT_API_KEY=sk-xxx MCBOT_MODEL=deepseek-chat \
 *     ./gradlew :agent-core:run --console=plain -q
 */
public final class Cli {

    public static void main(String[] args) throws Exception {
        String baseUrl = require("MCBOT_BASE_URL");
        String apiKey = require("MCBOT_API_KEY");
        String model = require("MCBOT_MODEL");

        ChatEngine engine = new LlmClient(
                new OpenAiCompatProvider("cli", baseUrl, apiKey, model), Duration.ofSeconds(180));

        List<ToolSpec> tools = List.of(
                new ToolSpec("now", "获取当前系统时间", "{\"type\":\"object\",\"properties\":{}}"),
                new ToolSpec("add", "两个整数求和", """
                        {"type":"object","properties":{"a":{"type":"integer"},"b":{"type":"integer"}},"required":["a","b"]}"""));

        ToolExecutor exec = (name, argsJson) -> {
            try {
                if (name.equals("now")) {
                    return CompletableFuture.completedFuture(new ToolExecutor.ToolOutcome(true,
                            java.time.LocalDateTime.now().toString()));
                }
                if (name.equals("add")) {
                    var o = com.google.gson.JsonParser.parseString(argsJson).getAsJsonObject();
                    long sum = o.get("a").getAsLong() + o.get("b").getAsLong();
                    return CompletableFuture.completedFuture(new ToolExecutor.ToolOutcome(true,
                            "结果 = " + sum));
                }
                return CompletableFuture.completedFuture(new ToolExecutor.ToolOutcome(false,
                        "未知工具 " + name));
            } catch (Exception e) {
                return CompletableFuture.completedFuture(new ToolExecutor.ToolOutcome(false,
                        "工具参数解析失败: " + e.getMessage()));
            }
        };

        var done = new CountDownLatch(1);
        AgentLoop loop = new AgentLoop(engine, tools, exec, AgentLoop.Config.defaults(),
                new AgentLoop.Listener() {
                    @Override
                    public void onToolInvoked(String name, String argsJson, boolean ok, String feedback) {
                        System.out.println("  [tool] " + name + " " + argsJson
                                + " -> " + (ok ? "✔ " : "✘ ") + feedback);
                    }

                    @Override
                    public void onReply(String text) {
                        System.out.println("bot> " + text);
                        done.countDown();
                    }

                    @Override
                    public void onNotice(String text) {
                        System.out.println("  [notice] " + text);
                    }
                },
                () -> PromptBuilder.build("你在命令行里给工程师当助手，善用工具再回答。", List.of()),
                12000);

        System.out.println("mcbot agent-core CLI (exit 退出)");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            if (line.equalsIgnoreCase("exit")) {
                break;
            }
            loop.submit(line);
            done.await(3, TimeUnit.MINUTES);
            System.out.print("you> ");
        }
        System.exit(0);
    }

    private static String require(String env) {
        String v = System.getenv(env);
        if (v == null || v.isBlank()) {
            System.err.println("缺少环境变量 " + env);
            System.exit(2);
        }
        return v;
    }
}
