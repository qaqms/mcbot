package com.neko.mcbot.agentcore.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;

/** Provider values are inspected in memory and discarded; only local enums leave this class. */
public record ServiceErrorDiagnostics(Category category, Source source, Category code,
                                      Category type, Param param, int status, Shape shape) {
    public enum Category {
        NONE, AUTHENTICATION, PERMISSION, RATE_LIMIT, QUOTA, MODEL_NOT_FOUND, NOT_FOUND, CONTEXT_LIMIT,
        TOOL_PROTOCOL, UNSUPPORTED_PARAMETER, INVALID_REQUEST, CONTENT_POLICY, TIMEOUT,
        UPSTREAM, UNKNOWN, MIXED
    }
    public enum Source { NONE, CODE, TYPE, STATUS, MESSAGE_HINT, UNKNOWN, MIXED }
    public enum Param { NONE, MODEL, MESSAGES, TOOLS, TOOL_CHOICE, STREAM, STREAM_OPTIONS, OTHER, MIXED }
    public enum Shape { NONE, OBJECT, STRING, OTHER, MIXED }

    public static final ServiceErrorDiagnostics NONE = new ServiceErrorDiagnostics(
            Category.NONE, Source.NONE, Category.NONE, Category.NONE, Param.NONE, 0, Shape.NONE);

    public static ServiceErrorDiagnostics from(JsonElement error) {
        if (error == null || error.isJsonNull()) return NONE;
        JsonObject object = error.isJsonObject() ? error.getAsJsonObject() : new JsonObject();
        Category code = fieldCategory(object.get("code"));
        Category type = fieldCategory(object.get("type"));
        int status = status(object.get("status"));
        if (status == 0) status = status(object.get("status_code"));
        if (status == 0) status = status(object.get("code"));
        JsonElement parameter = object.get("param");
        Param param = parameter == null || parameter.isJsonNull() ? Param.NONE
                : param(string(parameter, 128));
        Shape shape = error.isJsonObject() ? Shape.OBJECT
                : error.isJsonPrimitive() && error.getAsJsonPrimitive().isString()
                ? Shape.STRING : Shape.OTHER;
        Category hint = messageHint(string(shape == Shape.STRING ? error : object.get("message"), 4096));
        Category category;
        Source source;
        if (specific(code)) {
            category = code;
            source = Source.CODE;
        } else if (specific(type)) {
            category = type;
            source = Source.TYPE;
        } else if (hint != Category.UNKNOWN) {
            category = hint;
            source = Source.MESSAGE_HINT;
        } else if (statusCategory(status) != Category.UNKNOWN) {
            category = statusCategory(status);
            source = Source.STATUS;
        } else if (code != Category.NONE && code != Category.UNKNOWN) {
            category = code;
            source = Source.CODE;
        } else if (type != Category.NONE && type != Category.UNKNOWN) {
            category = type;
            source = Source.TYPE;
        } else {
            category = Category.UNKNOWN;
            source = Source.UNKNOWN;
        }
        return new ServiceErrorDiagnostics(category, source, code, type, param, status, shape);
    }

    public ServiceErrorDiagnostics merge(ServiceErrorDiagnostics next) {
        if (shape == Shape.NONE) return next;
        if (next.shape == Shape.NONE) return this;
        return new ServiceErrorDiagnostics(category == next.category ? category : Category.MIXED,
                source == next.source ? source : Source.MIXED,
                code == next.code ? code : Category.MIXED,
                type == next.type ? type : Category.MIXED,
                param == next.param ? param : Param.MIXED,
                status == next.status ? status : 0,
                shape == next.shape ? shape : Shape.MIXED);
    }

    public String summary() {
        return "error_category=" + category + " error_source=" + source
                + " error_code=" + code + " error_type=" + type
                + " error_param=" + param + " error_status=" + status + " error_shape=" + shape;
    }

    private static boolean specific(Category category) {
        return category != Category.NONE && category != Category.UNKNOWN
                && category != Category.INVALID_REQUEST && category != Category.UPSTREAM;
    }

    private static Category fieldCategory(JsonElement value) {
        if (value == null || value.isJsonNull()) return Category.NONE;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            return statusCategory(status(value));
        }
        String text = string(value, 80);
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "invalid_api_key", "authentication_error", "unauthorized" -> Category.AUTHENTICATION;
            case "permission_denied", "permission_error", "forbidden" -> Category.PERMISSION;
            case "rate_limit_exceeded", "rate_limit_error", "too_many_requests" -> Category.RATE_LIMIT;
            case "insufficient_quota", "quota_exceeded", "billing_hard_limit_reached" -> Category.QUOTA;
            case "model_not_found", "invalid_model", "model_not_available" -> Category.MODEL_NOT_FOUND;
            case "context_length_exceeded", "context_window_exceeded" -> Category.CONTEXT_LIMIT;
            case "invalid_tool_call", "tool_call_error", "tool_protocol_error" -> Category.TOOL_PROTOCOL;
            case "unsupported_parameter", "unsupported_value" -> Category.UNSUPPORTED_PARAMETER;
            case "invalid_request_error", "invalid_request", "invalid_argument" -> Category.INVALID_REQUEST;
            case "content_policy_violation", "content_filter", "safety_error" -> Category.CONTENT_POLICY;
            case "timeout", "request_timeout", "upstream_timeout", "gateway_timeout" -> Category.TIMEOUT;
            case "api_error", "server_error", "internal_server_error", "upstream_error",
                    "service_unavailable", "no_available_channel", "channel_no_available" -> Category.UPSTREAM;
            default -> Category.UNKNOWN;
        };
    }

    private static Category messageHint(String message) {
        String text = message.toLowerCase(Locale.ROOT);
        // Hints are deliberately labelled weaker evidence than a structured error code.
        if ((text.contains("tool_call_id") || text.contains("tool_calls"))
                && (text.contains("must be followed") || text.contains("did not have response")
                || text.contains("without response") || text.contains("not found")
                || text.contains("must be a response"))) return Category.TOOL_PROTOCOL;
        if (text.contains("maximum context length") || text.contains("context length exceeded")
                || text.contains("context window exceeded")) return Category.CONTEXT_LIMIT;
        if (text.contains("insufficient quota") || text.contains("exceeded your current quota")
                || text.contains("insufficient balance")) return Category.QUOTA;
        if (text.contains("unsupported parameter") || text.contains("unrecognized request argument"))
            return Category.UNSUPPORTED_PARAMETER;
        if (text.contains("no available channel") || text.contains("upstream error"))
            return Category.UPSTREAM;
        return Category.UNKNOWN;
    }

    private static Param param(String value) {
        if (value.isEmpty()) return Param.OTHER;
        String root = value.split("[.\\[]", 2)[0];
        return switch (root) {
            case "model" -> Param.MODEL;
            case "messages" -> Param.MESSAGES;
            case "tools" -> Param.TOOLS;
            case "tool_choice" -> Param.TOOL_CHOICE;
            case "stream" -> Param.STREAM;
            case "stream_options" -> Param.STREAM_OPTIONS;
            default -> Param.OTHER;
        };
    }

    private static Category statusCategory(int status) {
        return switch (status) {
            case 400, 422 -> Category.INVALID_REQUEST;
            case 401 -> Category.AUTHENTICATION;
            case 403 -> Category.PERMISSION;
            case 404 -> Category.NOT_FOUND;
            case 408, 504 -> Category.TIMEOUT;
            case 429 -> Category.RATE_LIMIT;
            case 500, 502, 503 -> Category.UPSTREAM;
            default -> Category.UNKNOWN;
        };
    }

    private static int status(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return 0;
        return switch (value.getAsString()) {
            case "400" -> 400;
            case "401" -> 401;
            case "403" -> 403;
            case "404" -> 404;
            case "408" -> 408;
            case "422" -> 422;
            case "429" -> 429;
            case "500" -> 500;
            case "502" -> 502;
            case "503" -> 503;
            case "504" -> 504;
            default -> 0;
        };
    }

    private static String string(JsonElement value, int limit) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return "";
        String text = value.getAsString();
        return text.length() <= limit ? text : "";
    }
}
