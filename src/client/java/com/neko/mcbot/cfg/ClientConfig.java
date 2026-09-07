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
 *   "api_key": "sk-...", "persona": "可选人设文本" }
 */
public final class ClientConfig {

    public final String baseUrl;
    public final String model;
    public final String apiKey;
    public final String persona;
    public final boolean brainEnabled;

    public ClientConfig(String baseUrl, String model, String apiKey, String persona) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.persona = persona == null ? "" : persona;
        this.brainEnabled = notBlank(baseUrl) && notBlank(model) && notBlank(apiKey);
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
                json.has("persona") ? json.get("persona").getAsString() : null);
    }

    public static void save(ClientConfig cfg) {
        JsonObject json = new JsonObject();
        json.addProperty("base_url", cfg.baseUrl);
        json.addProperty("model", cfg.model);
        json.addProperty("api_key", cfg.apiKey);
        json.addProperty("persona", cfg.persona);
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
