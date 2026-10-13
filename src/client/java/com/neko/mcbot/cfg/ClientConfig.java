package com.neko.mcbot.cfg;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

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
    public final Preferences preferences;
    public final Set<String> environmentOverrides;

    public record Preferences(int contextWindow, int compactThreshold, int recentBudget,
                              boolean autoCompact, boolean foldTools, boolean dark, boolean showHud) {
        public static Preferences defaults() {
            return new Preferences(64000, 6000, 1500, true, true, false, true);
        }

        public Preferences {
            if (contextWindow < 4096 || contextWindow > 2_000_000
                    || compactThreshold < 2048 || compactThreshold >= contextWindow
                    || recentBudget < 256 || recentBudget >= compactThreshold) {
                throw new IllegalArgumentException("近期保留量 < 整理阈值 < 模型窗口，窗口需在4096至2000000之间。");
            }
        }
    }

    public ClientConfig(String baseUrl, String model, String apiKey, String persona) {
        this(baseUrl, model, apiKey, persona, true);
    }

    public ClientConfig(String baseUrl, String model, String apiKey, String persona,
                        boolean acceptMode) {
        this(baseUrl, model, apiKey, persona, acceptMode, Preferences.defaults(), Set.of());
    }

    public ClientConfig(String baseUrl, String model, String apiKey, String persona,
                        boolean acceptMode, Preferences preferences, Set<String> overrides) {
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.model = model == null ? "" : model;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.persona = persona == null ? "" : persona;
        this.brainEnabled = notBlank(baseUrl) && notBlank(model) && notBlank(apiKey);
        this.acceptMode = acceptMode;
        this.preferences = java.util.Objects.requireNonNull(preferences);
        this.environmentOverrides = Set.copyOf(overrides);
    }

    public ClientConfig withConnection(String url, String model, String key, String persona) {
        return new ClientConfig(url, model, key, persona, acceptMode, preferences, environmentOverrides);
    }

    public ClientConfig withPreferences(Preferences preferences) {
        return new ClientConfig(baseUrl, model, apiKey, persona, acceptMode, preferences, environmentOverrides);
    }

    public boolean connectionChanged(ClientConfig other) {
        return !baseUrl.equals(other.baseUrl) || !model.equals(other.model) || !apiKey.equals(other.apiKey);
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
        return fromJson(json, System.getenv());
    }

    public static ClientConfig fromJson(JsonObject json, java.util.Map<String, String> environment) {
        var overrides = new LinkedHashSet<String>();
        String[] names = {"base_url", "model", "api_key"};
        String[] envNames = {"MCBOT_BASE_URL", "MCBOT_MODEL", "MCBOT_API_KEY"};
        String[] values = new String[3];
        for (int i = 0; i < names.length; i++) {
            String e = environment.get(envNames[i]);
            if (notBlank(e)) { values[i] = e; overrides.add(names[i]); }
            else values[i] = string(json, names[i], "");
        }
        Preferences d = Preferences.defaults(), p = d;
        try {
            p = new Preferences(integer(json, "context_window", d.contextWindow()),
                    integer(json, "compact_threshold", d.compactThreshold()),
                    integer(json, "recent_budget", d.recentBudget()),
                    bool(json, "auto_compact", d.autoCompact()), bool(json, "fold_tools", d.foldTools()),
                    bool(json, "dark", d.dark()), bool(json, "show_hud", d.showHud()));
        } catch (IllegalArgumentException invalid) {
            LoggerFactory.getLogger("mcbot").warn("client.json 上下文设置无效，使用默认值。");
        }
        return new ClientConfig(values[0], values[1], values[2], string(json, "persona", ""),
                bool(json, "accept_mode", true), p, overrides);
    }

    public static JsonObject toJson(ClientConfig cfg) {
        JsonObject json = new JsonObject();
        json.addProperty("base_url", cfg.baseUrl);
        json.addProperty("model", cfg.model);
        json.addProperty("api_key", cfg.apiKey);
        json.addProperty("persona", cfg.persona);
        json.addProperty("accept_mode", cfg.acceptMode);
        Preferences p = cfg.preferences;
        json.addProperty("context_window", p.contextWindow());
        json.addProperty("compact_threshold", p.compactThreshold());
        json.addProperty("recent_budget", p.recentBudget());
        json.addProperty("auto_compact", p.autoCompact());
        json.addProperty("fold_tools", p.foldTools());
        json.addProperty("dark", p.dark());
        json.addProperty("show_hud", p.showHud());
        return json;
    }

    public static void save(ClientConfig cfg) {
        try {
            Path file = configFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file,
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(toJson(cfg)),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            LoggerFactory.getLogger("mcbot").error("client.json 写入失败", e);
            throw new java.io.UncheckedIOException("配置保存失败，请检查本地文件权限。", e);
        }
    }

    private static String string(JsonObject json, String key, String fallback) {
        var v = json.get(key);
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? v.getAsString() : fallback;
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        var v = json.get(key);
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() ? v.getAsBoolean() : fallback;
    }

    private static int integer(JsonObject json, String key, int fallback) {
        var v = json.get(key);
        if (v == null) return fallback;
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Invalid context value");
        try { return v.getAsBigDecimal().intValueExact(); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid context value"); }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
