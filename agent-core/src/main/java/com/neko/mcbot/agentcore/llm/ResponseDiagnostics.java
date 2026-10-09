package com.neko.mcbot.agentcore.llm;

/** Only fixed categories and counts cross the diagnostic boundary, never provider strings. */
public record ResponseDiagnostics(long requestId, int attempt, int http, Format format, long bytes, int dataLines,
                                  int jsonFrames, int malformedFrames, int parseErrors,
                                  int deltaFrames, int messageFrames, int toolFrames,
                                  int reasoningFrames, int refusalFrames, int errorFrames,
                                  boolean done, Finish finish, boolean empty, boolean errorBodyTruncated,
                                  ServiceErrorDiagnostics error) {
    public enum Format { SSE, JSON, HTML, OTHER, UNKNOWN }
    public enum Finish { STOP, TOOL_CALLS, LENGTH, CONTENT_FILTER, FUNCTION_CALL, OTHER, ABSENT }

    public String summary() {
        return "request=" + requestId + " attempt=" + attempt
                + " http=" + http + " format=" + format + " bytes=" + bytes
                + " data=" + dataLines + " json=" + jsonFrames
                + " malformed=" + malformedFrames + " parse_errors=" + parseErrors
                + " delta=" + deltaFrames + " message=" + messageFrames
                + " tools=" + toolFrames + " reasoning=" + reasoningFrames
                + " refusal=" + refusalFrames + " errors=" + errorFrames
                + " done=" + done + " finish=" + finish + " empty=" + empty
                + " error_body_truncated=" + errorBodyTruncated + " " + error.summary();
    }
}
