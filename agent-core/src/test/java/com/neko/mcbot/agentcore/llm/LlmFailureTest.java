package com.neko.mcbot.agentcore.llm;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class LlmFailureTest {
    @Test
    void httpCodesAreUsefulWithoutServerResponseText() {
        for (int code : new int[]{400, 401, 403, 404, 422, 429, 502}) {
            String text = LlmFailure.userMessage(new CompletionException(
                    new LlmFailure(LlmFailure.Kind.HTTP, code)));
            assertTrue(text.contains("HTTP " + code));
        }
    }

    @Test
    void unknownExceptionsCannotEchoCredentialsOrUrls() {
        String text = LlmFailure.userMessage(new IllegalArgumentException(
                "Authorization: Bearer private-value https://example.test/?token=private-value"));
        assertFalse(text.contains("private-value"));
        assertFalse(text.contains("example.test"));
        assertFalse(text.contains("Authorization"));
    }

    @Test
    void connectionAndTimeoutCategoriesDoNotEchoExceptionDetails() {
        assertTrue(LlmFailure.userMessage(new HttpTimeoutException("private")).contains("超时"));
        assertTrue(LlmFailure.userMessage(new ConnectException("private")).contains("无法连接"));
    }

    @Test
    void emptyResponseDoesNotClaimThatTheServiceCannotSupportTheProtocol() {
        String text = LlmFailure.userMessage(new LlmFailure(LlmFailure.Kind.EMPTY_STREAM, 0));
        assertTrue(text.contains("不代表模型一定不兼容"));
        assertFalse(text.contains("只支持"));
    }

    @Test
    void serviceErrorCategoriesReachUsersWithoutRemoteText() {
        var detail = ServiceErrorDiagnostics.from(com.google.gson.JsonParser.parseString(
                "{\"code\":\"insufficient_quota\",\"message\":\"private-value\"}"));
        var failure = new LlmFailure(LlmFailure.Kind.SERVICE_ERROR, 200, detail);
        String text = LlmFailure.userMessage(failure);
        assertTrue(text.contains("额度"));
        assertTrue(text.contains("不会自动重发"));
        assertFalse(text.contains("private-value"));
        assertFalse(failure.toString().contains("private-value"));
        assertTrue(LlmFailure.userMessage(new LlmFailure(
                LlmFailure.Kind.SERVICE_ERROR, 200)).contains("尚未识别"));
    }

    @Test
    void messageHintsAreNotPresentedAsStructuredErrorCodes() {
        var detail = ServiceErrorDiagnostics.from(com.google.gson.JsonParser.parseString(
                "{\"message\":\"maximum context length private-value\"}"));
        String text = LlmFailure.userMessage(new LlmFailure(LlmFailure.Kind.SERVICE_ERROR, 200, detail));
        assertTrue(text.contains("错误文本线索"));
        assertFalse(text.contains("private-value"));
        assertFalse(text.contains("服务错误分类"));
    }
}
