package com.neko.mcbot.agentcore.llm;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Only locally generated categories are safe to show; never echo a remote error body. */
public final class LlmFailure extends IOException {

    public enum Kind { HTTP, HTML, EMPTY_STREAM, SERVICE_ERROR }

    private final Kind kind;
    private final int status;
    private final ServiceErrorDiagnostics error;

    public LlmFailure(Kind kind, int status) {
        this(kind, status, ServiceErrorDiagnostics.NONE);
    }

    public LlmFailure(Kind kind, int status, ServiceErrorDiagnostics error) {
        super("LLM " + kind + (status > 0 ? " HTTP " + status : "")
                + " " + error.summary());
        this.kind = kind;
        this.status = status;
        this.error = error;
    }

    public static String userMessage(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String detail;
        if (cause instanceof LlmFailure known) {
            String http = known.status > 0 ? "（HTTP " + known.status + "）" : "";
            detail = http + switch (known.kind) {
                case HTML -> "：端点返回网页而不是模型接口，请检查 API 地址。";
                case EMPTY_STREAM -> "：服务未返回可识别的回答或工具调用。请重试或测试连接；这不代表模型一定不兼容。";
                case SERVICE_ERROR -> "：服务在响应中报告错误，任务未正常完成。" + known.serviceDetail();
                case HTTP -> known.error.category() != ServiceErrorDiagnostics.Category.NONE
                        && known.error.category() != ServiceErrorDiagnostics.Category.UNKNOWN
                        ? "：服务拒绝请求。" + known.serviceDetail() : switch (known.status) {
                    case 401 -> "：密钥无效或未通过鉴权，请重新填写完整密钥。";
                    case 403 -> "：服务拒绝访问，请检查密钥权限或服务限制。";
                    case 404 -> "：接口路径或模型不存在，请核对 API 地址和完整模型名。";
                    case 400, 422 -> "：服务拒绝请求，请检查模型名及工具调用、流式参数兼容性。";
                    case 429 -> "：请求过于频繁或额度受限，请检查服务额度后重试。";
                    default -> known.status >= 500
                            ? "：模型服务或网关异常，请稍后重试。"
                            : "：服务拒绝请求，请检查客户端配置。";
                };
            };
        } else if (cause instanceof HttpTimeoutException) {
            detail = "：请求超时，请检查服务与网络。";
        } else if (cause instanceof ConnectException) {
            detail = "：无法连接模型服务，请检查地址与网络。";
        } else {
            detail = "，请检查客户端配置与网络后重试。";
        }
        return "模型调用失败" + detail;
    }

    private String serviceDetail() {
        String label = switch (error.category()) {
            case AUTHENTICATION -> "鉴权失败";
            case PERMISSION -> "权限受限";
            case RATE_LIMIT -> "请求限流";
            case QUOTA -> "额度或余额受限";
            case MODEL_NOT_FOUND -> "模型不可用";
            case NOT_FOUND -> "接口或模型未找到";
            case CONTEXT_LIMIT -> "上下文超限";
            case TOOL_PROTOCOL -> "工具调用与回执协议问题";
            case UNSUPPORTED_PARAMETER -> "请求参数不受支持";
            case INVALID_REQUEST -> "请求未通过校验";
            case CONTENT_POLICY -> "内容策略限制";
            case TIMEOUT -> "服务处理超时";
            case UPSTREAM -> "上游或路由异常";
            case MIXED -> "多个不同类别的错误";
            case NONE, UNKNOWN -> "";
        };
        if (label.isEmpty()) return "具体类别尚未识别，请保留本次日志以继续定位。";
        return (error.source() == ServiceErrorDiagnostics.Source.MESSAGE_HINT
                ? "错误文本线索：" : "服务错误分类：") + label
                + "。请保留日志；本任务不会自动重发。";
    }
}
