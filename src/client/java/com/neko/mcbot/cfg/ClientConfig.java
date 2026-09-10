package com.neko.mcbot.cfg;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 客户端大脑配置：mcbot/client.json，环境变量 MCBOT_* 覆盖（环境变量优先）。
 * { "base_url": "https://api.deepseek.com", "model": "deepseek-chat",
 *   "api_key": "sk-...", "persona": "可选人设文本", "accept_mode": true }
 */
public final class ClientConfig {

    public final String baseUrl;
    public final String model;
    public final String apiKey;
    public final String persona;
    public final boolean brainEnabled;

    /**
     * R2-S4 受理即回执的回滚开关（默认开）。
     *
     * <p>关掉它 = 整条链退回今日语义：客户端不再在 tool_call 里点 {@code accept}，
     * 服务端对 {@code move_to}/{@code break_block} 就仍走"一问一答"的同步回执，
     * 大脑也不会进 PARK。**这是发布后唯一能一键止血的开关**（改 client.json 即可，
     * 不需要换服务端），所以它必须走配置文件而不是写死常量。
     */
    public final boolean acceptMode;

    public ClientConfig(String baseUrl, String model, String apiKey, String persona) {
        this(baseUrl, model, apiKey, persona, true);
    }

    public ClientConfig(String baseUrl, String model, String apiKey, String persona,
                        boolean acceptMode) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.persona = persona == null ? "" : persona;
        this.brainEnabled = notBlank(baseUrl) && notBlank(model) && notBlank(apiKey);
        this.acceptMode = acceptMode;
    }

    public static Path configFile() {
        return FabricLoader.getInstance().getGameDir().resolve("mcbot").resolve("client.json");
    }

    public static ClientConfig load() {
        JsonObject json = new JsonObject();
        Path file = configFile();
        if (Files.exists(file)) {
            try {
                json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            } catch (Exception e) {
                LoggerFactory.getLogger("mcbot").error("client.json 解析失败，忽略: {}", file, e);
            }
        }
        return new ClientConfig(
                envOr(json, "MCBOT_BASE_URL", "base_url"),
                envOr(json, "MCBOT_MODEL", "model"),
                envOr(json, "MCBOT_API_KEY", "api_key"),
                json.has("persona") ? json.get("persona").getAsString() : null,
                // 缺省 true：新装的客户端默认吃受理即回执；老配置没有这个键也不会被误关
                !json.has("accept_mode") || json.get("accept_mode").getAsBoolean());
    }

    public static void save(ClientConfig cfg) {
        JsonObject json = new JsonObject();
        json.addProperty("base_url", cfg.baseUrl);
        json.addProperty("model", cfg.model);
        json.addProperty("api_key", cfg.apiKey);
        json.addProperty("persona", cfg.persona);
        json.addProperty("accept_mode", cfg.acceptMode);
        try {
            Path file = configFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file,
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(json),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            LoggerFactory.getLogger("mcbot").error("client.json 写入失败", e);
        }
    }

    private static String envOr(JsonObject json, String env, String field) {
        String e = System.getenv(env);
        if (notBlank(e)) {
            return e;
        }
        return json.has(field) ? json.get(field).getAsString() : "";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
