package com.downloader.util;

import org.junit.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * MiniJson 单元测试：类型判定（Long vs Double）、转义、往返、语法错误。
 */
public class MiniJsonTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseObject(String json) throws IOException {
        return (Map<String, Object>) MiniJson.parse(json);
    }

    // ── 类型判定 ──

    @Test
    public void integersParseAsLong() throws IOException {
        Object v = MiniJson.parse("42");
        assertTrue("整数必须是 Long（JLS 数值提升陷阱回归）", v instanceof Long);
        assertEquals(42L, v);
    }

    @Test
    public void negativeIntegerParsesAsLong() throws IOException {
        Object v = MiniJson.parse("-7");
        assertTrue(v instanceof Long);
        assertEquals(-7L, v);
    }

    @Test
    public void floatsParseAsDouble() throws IOException {
        Object v = MiniJson.parse("4.5");
        assertTrue(v instanceof Double);
        assertEquals(4.5, (Double) v, 1e-12);
    }

    @Test
    public void exponentParsesAsDouble() throws IOException {
        Object v = MiniJson.parse("1e3");
        assertTrue("带指数的数字必须解析为 Double", v instanceof Double);
        assertEquals(1000.0, (Double) v, 1e-9);
    }

    @Test
    public void objectFieldIntegerStaysLong() throws IOException {
        Map<String, Object> map = parseObject("{\"a\": 1, \"b\": 2.0}");
        assertTrue("对象内整数字段必须是 Long", map.get("a") instanceof Long);
        assertTrue(map.get("b") instanceof Double);
    }

    @Test
    public void literalsAndNull() throws IOException {
        assertEquals(Boolean.TRUE, MiniJson.parse("true"));
        assertEquals(Boolean.FALSE, MiniJson.parse("false"));
        assertNull(MiniJson.parse("null"));
    }

    // ── 转义 ──

    @Test
    public void stringEscapes() throws IOException {
        assertEquals("a中\n\"b\\",
                MiniJson.parse("\"a\\u4e2d\\n\\\"b\\\\\""));
    }

    @Test
    public void controlCharEscape() throws IOException {
        assertEquals("t\tb\fb", MiniJson.parse("\"t\\tb\\fb\""));
    }

    // ── 嵌套结构 ──

    @Test
    public void nestedCollections() throws IOException {
        Map<String, Object> map = parseObject(
                "{\"a\": [1, {\"b\": true}, null], \"c\": []}");
        List<Object> list = (List<Object>) map.get("a");
        assertEquals(3, list.size());
        assertEquals(1L, list.get(0));
        assertTrue(list.get(1) instanceof Map);
        assertNull(list.get(2));
        assertTrue(((List<Object>) map.get("c")).isEmpty());
    }

    // ── 序列化往返 ──

    @Test
    public void writeParseRoundTripPreservesTypes() throws IOException {
        Map<String, Object> src = new LinkedHashMap<>();
        src.put("name", "任务\"一\"\n");
        src.put("count", 100L);
        src.put("ratio", 0.5);
        src.put("ok", true);
        src.put("nil", null);

        Map<String, Object> back = parseObject(MiniJson.write(src));
        assertEquals(src.get("name"), back.get("name"));
        assertTrue(back.get("count") instanceof Long);
        assertEquals(100L, back.get("count"));
        assertTrue(back.get("ratio") instanceof Double);
        assertEquals(Boolean.TRUE, back.get("ok"));
        assertNull(back.get("nil"));
    }

    // ── 语法错误 ──

    @Test
    public void trailingGarbageThrows() {
        try {
            MiniJson.parse("42 43");
            fail("根值后多余字符应抛 IOException");
        } catch (IOException expected) {
        }
    }

    @Test
    public void unclosedStringThrows() {
        try {
            MiniJson.parse("\"abc");
            fail("未闭合字符串应抛 IOException");
        } catch (IOException expected) {
        }
    }

    @Test
    public void badEscapeThrows() {
        try {
            MiniJson.parse("\"a\\qb\"");
            fail("非法转义应抛 IOException");
        } catch (IOException expected) {
        }
    }

    @Test
    public void truncatedUnicodeEscapeThrows() {
        try {
            MiniJson.parse("\"a\\u12\"");
            fail("不完整的 \\u 转义应抛 IOException");
        } catch (IOException expected) {
        }
    }

    @Test
    public void nonStringKeyThrows() {
        try {
            MiniJson.parse("{1: 2}");
            fail("对象键非字符串应抛 IOException");
        } catch (IOException expected) {
        }
    }

    @Test
    public void bareWordThrows() {
        try {
            MiniJson.parse("tru");
            fail("非法字面量应抛 IOException");
        } catch (IOException expected) {
        }
    }
}
