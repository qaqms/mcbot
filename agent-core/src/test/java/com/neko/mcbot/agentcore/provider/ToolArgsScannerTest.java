package com.neko.mcbot.agentcore.provider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.mcbot.agentcore.llm.ToolCall;
import com.neko.mcbot.agentcore.llm.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R2-A 闭合判定的守门测试。核心风险只有一句话：
 * <b>别在参数只写了一半的时候说"写完了"</b>——那会让工具拿着半截坐标真去挖方块。
 */
class ToolArgsScannerTest {

    private static final List<String> REQ = List.of("x", "y");

    private static boolean feed(ToolArgsScanner sc, String... pieces) {
        boolean ready = false;
        for (String p : pieces) {
            ready = sc.accept(p);
        }
        return ready;
    }

    @Test
    void incompleteObjectIsNotReady() {
        assertFalse(feed(new ToolArgsScanner(REQ), "{\"x\":1,"), "缺 y 且括号未闭合");
        assertFalse(feed(new ToolArgsScanner(REQ), "{\"x\":1,\"y\":2"), "括号未闭合");
    }

    @Test
    void completeObjectIsReady() {
        assertTrue(feed(new ToolArgsScanner(REQ), "{\"x\":1,\"y\":2}"));
        // 跨碎片拼起来才算闭合
        assertTrue(feed(new ToolArgsScanner(REQ), "{\"x\":", "1,\"y\"", ":2}"));
    }

    /**
     * 本类存在的最直接理由：模型第一片常常就是"合法但残缺"的 JSON。
     * 只看括号配平会把 {@code {"x":1}} 当成写完。
     */
    @Test
    void balancedButMissingRequiredIsNotReady() {
        ToolArgsScanner sc = new ToolArgsScanner(REQ);
        assertFalse(sc.accept("{\"x\":1}"), "括号配平但 y 还没出现，不算写完");
    }

    /** 半截对象（括号未闭合）不得就绪；补齐 required 并闭合后才就绪。 */
    @Test
    void completesOnlyWhenRequiredFieldsArrive() {
        ToolArgsScanner sc = new ToolArgsScanner(REQ);
        assertFalse(sc.accept("{\"x\":1"), "未闭合");
        assertTrue(sc.accept(",\"y\":2}"), "补齐 y 且闭合后才就绪");
        assertEquals("{\"x\":1,\"y\":2}", sc.normalizedArgs());
    }

    /**
     * 端点可能把同一个 index 的 arguments 吐成两个独立对象（先 {@code {"x":1}} 再 {@code {"y":2}}）。
     * 拼接起来是非法 JSON，所以必须以"最后看到的那个完整对象"为准，否则永远不就绪。
     */
    @Test
    void secondTopLevelObjectReplacesTheFirst() {
        ToolArgsScanner sc = new ToolArgsScanner(REQ);
        assertFalse(sc.accept("{\"x\":1}"), "只有 x，不算完成");
        assertTrue(sc.accept("{\"x\":3,\"y\":4}"), "新对象齐了 required 就该就绪");
        assertEquals("{\"x\":3,\"y\":4}", sc.normalizedArgs(), "旧的半截对象必须被丢弃");
    }

    /** required 为 null 值同样算没给（模型爱用 null 占位）。 */
    @Test
    void nullRequiredValueIsNotReady() {
        assertFalse(feed(new ToolArgsScanner(REQ), "{\"x\":1,\"y\":null}"));
    }

    /** 字符串里的括号不得把深度算平。 */
    @Test
    void bracesInsideStringsAreIgnored() {
        ToolArgsScanner sc = new ToolArgsScanner(REQ);
        assertFalse(sc.accept("{\"x\":\"} {\",\"y\":"), "字符串里的括号不能影响配平");
        assertTrue(sc.accept("2}"));
        assertEquals("{\"x\":\"} {\",\"y\":2}", sc.normalizedArgs());
    }

    /** 转义引号不得提前结束字符串，否则后面的引号会把状态机带偏。 */
    @Test
    void escapedQuoteInsideStringDoesNotEndIt() {
        ToolArgsScanner sc = new ToolArgsScanner(REQ);
        assertTrue(sc.accept("{\"x\":\"a\\\"}b\",\"y\":2}"), "转义引号不应提前结束字符串");
    }

    @Test
    void nestedObjectsAndArraysAreCounted() {
        ToolArgsScanner sc = new ToolArgsScanner(List.of("blocks"));
        assertFalse(sc.accept("{\"blocks\":[{\"x\":1},{\"x\":2}"), "内层括号也要配平");
        assertTrue(sc.accept("],\"z\":3}"));
    }

    /** required 只查顶层：内层同名键不算数。 */
    @Test
    void requiredIsCheckedAtTopLevelOnly() {
        ToolArgsScanner sc = new ToolArgsScanner(List.of("y"));
        assertFalse(sc.accept("{\"x\":{\"y\":1}"), "内层 y 不该冒充顶层 y（且外层还没闭合）");
        assertTrue(sc.accept(",\"y\":2}"), "顶层 y 到位才就绪");
    }

    /** 键里带转义也能被正确还原（否则 required 永远匹配不上）。 */
    @Test
    void escapedKeyNamesAreUnquotedBeforeMatching() {
        // schema 原文里 required 是 ["a\"b","y"]，解析出来的键名是 a"b
        String schema = "{\"required\":[\"a\\\"b\",\"y\"]}";
        assertEquals(List.of("a\"b", "y"), ToolArgsScanner.requiredOf(schema));
        ToolArgsScanner sc = ToolArgsScanner.forSchema(schema);
        assertFalse(sc.accept("{\"a\\\"b\":1"), "未闭合且缺 y");
        assertTrue(sc.accept(",\"y\":2}"), "补齐 y 才就绪");
    }

    @Test
    void nonObjectRootIsNotReady() {
        assertFalse(feed(new ToolArgsScanner(REQ), "[1,2]"), "根不是对象");
        assertFalse(feed(new ToolArgsScanner(REQ), "\"just a string\""));
    }

    /** 就绪是单调的：报过 true 之后永不回退。 */
    @Test
    void readinessIsMonotonic() {
        ToolArgsScanner sc = new ToolArgsScanner(REQ);
        assertTrue(sc.accept("{\"x\":1,\"y\":2}"));
        assertTrue(sc.accept("")); // 无新增
        assertTrue(sc.accept(" "));
        assertTrue(sc.isReady());
    }

    @Test
    void normalizeStripsInsignificantWhitespaceButKeepsStringContent() {
        assertEquals("{\"a\":1}", ToolArgsScanner.normalize("{ \"a\" : 1 }"));
        assertEquals("{\"a\":\" x y \"}", ToolArgsScanner.normalize("{\"a\":\" x y \"}"));
        assertEquals("", ToolArgsScanner.normalize(null));
    }

    @Test
    void requiredOfParsesSchemaAndDegradesOnGarbage() {
        assertEquals(List.of("x", "y"),
                ToolArgsScanner.requiredOf("{\"type\":\"object\",\"required\":[\"x\",\"y\"]}"));
        assertEquals(List.of(), ToolArgsScanner.requiredOf("{\"type\":\"object\"}"));
        assertEquals(List.of(), ToolArgsScanner.requiredOf("not json"));
        assertEquals(List.of(), ToolArgsScanner.requiredOf(null));
    }

    @Test
    void toolSpecExposesItsRequiredFields() {
        ToolSpec spec = ToolSpec.of("move", "走", "{\"required\":[\"x\",\"z\"]}");
        assertEquals(List.of("x", "z"), spec.requiredFields());
        assertEquals(List.of(), ToolSpec.of("status", "看", "{}").requiredFields());
    }

    // ---- 读取器：碎片 → 就绪信号 ----

    private static final ToolSpec MOVE = ToolSpec.of("move_to",
            "走", "{\"required\":[\"x\",\"y\",\"z\"]}");

    /** 碎片跨片到达：只有凑齐 required 且括号闭合的那一片才报就绪，且只报一次。 */
    @Test
    void readerReportsReadyOnceAndOnlyOnce() {
        StreamingTurnReader reader = new StreamingTurnReader(List.of(MOVE), true);
        reader.toolCallDelta(0, "c0", "move_to", "{\"x\":1");
        assertTrue(reader.drainReady().isEmpty(), "还没写完");
        reader.toolCallDelta(0, "c0", null, ",\"y\":2");
        assertTrue(reader.drainReady().isEmpty());
        reader.toolCallDelta(0, null, null, ",\"z\":3}");
        List<StreamingTurnReader.ReadyCall> ready = reader.drainReady();
        assertEquals(1, ready.size(), "三片刚好凑齐才应报就绪一次");
        assertEquals(0, ready.get(0).index());
        assertEquals("move_to", ready.get(0).call().name());
        assertEquals("{\"x\":1,\"y\":2,\"z\":3}", ready.get(0).signature());
        assertTrue(reader.drainReady().isEmpty(), "同一个 index 不得重复报就绪");
    }

    @Test
    void readerReportsMultipleCallsInIndexOrder() {
        StreamingTurnReader reader = new StreamingTurnReader(List.of(MOVE, MOVE), true);
        reader.toolCallDelta(0, "c0", "move_to", "{\"x\":1,\"y\":2,\"z\":3}");
        List<StreamingTurnReader.ReadyCall> ready = reader.drainReady();
        assertEquals(1, ready.size());
        assertEquals(0, ready.get(0).index());
        reader.toolCallDelta(1, "c1", "move_to", "{\"x\":9,\"y\":8,\"z\":7}");
        ready = reader.drainReady();
        assertEquals(1, ready.size());
        assertEquals(1, ready.get(0).index());
        assertEquals("{\"x\":9,\"y\":8,\"z\":7}", ready.get(0).signature());
    }

    @Test
    void readerWithoutAccumulationStillReportsReady() {
        StreamingTurnReader reader = new StreamingTurnReader(List.of(MOVE), false);
        reader.toolCallDelta(0, "c0", "move_to", "{\"x\":1,\"y\":2,\"z\":3}");
        assertEquals(1, reader.drainReady().size());
        assertEquals("", reader.builder().textTail(0), "不累积时不该留文本");
    }

    /** name 属于首个碎片；name 没到就不能派发（派发了也不知道调什么工具）。 */
    @Test
    void readerDoesNotReportWhenNameNeverArrives() {
        StreamingTurnReader reader = new StreamingTurnReader(List.of(MOVE), true);
        reader.toolCallDelta(0, "c0", null, "{\"x\":1,\"y\":2,\"z\":3}");
        assertTrue(reader.drainReady().isEmpty());
        assertNull(reader.builder().snapshot(0), "没有 name 就没有可派发的调用");
    }

    /** 快照必须是一份能被标准解析器吃下的真 JSON。 */
    @Test
    void snapshotIsValidJson() {
        StreamingTurnReader reader = new StreamingTurnReader(List.of(MOVE), true);
        reader.toolCallDelta(0, "c0", "move_to", "{\"x\":1,\"y\":2,\"z\":3}");
        reader.drainReady();
        ToolCall call = reader.builder().snapshot(0);
        assertNotNull(call);
        JsonObject parsed = JsonParser.parseString(call.argsJson()).getAsJsonObject();
        assertEquals(1, parsed.get("x").getAsInt());
    }
}


