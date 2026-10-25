package com.downloader.bencode;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * BencodeParser 单元测试：基础解析、info 区间捕获（深度限定）、边界异常。
 */
public class BencodeParserTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ── 基础类型 ──

    @Test
    public void readDictBasic() {
        BencodeParser parser = new BencodeParser(bytes("d3:fooi42e4:name4:teste"));
        java.util.Map<String, Object> map = parser.readDict();
        assertEquals(42L, map.get("foo"));
        // 字符串值统一返回 byte[]（pieces 等二进制字段才能原样保留）
        assertTrue(map.get("name") instanceof byte[]);
        assertEquals("test", BencodeParser.asText(map.get("name")));
    }

    @Test
    public void readListBasic() {
        BencodeParser parser = new BencodeParser(bytes("l4:spami7eee"));
        java.util.List<Object> list = parser.readList();
        assertEquals(2, list.size());
        assertEquals("spam", BencodeParser.asText(list.get(0)));
        assertEquals(7L, list.get(1));
    }

    @Test
    public void nestedStructures() {
        BencodeParser parser = new BencodeParser(
                bytes("d1:ad1:ki3ee1:bl1:xl1:yeee"));
        java.util.Map<String, Object> map = parser.readDict();
        assertTrue(map.get("a") instanceof java.util.Map);
        assertTrue(map.get("b") instanceof java.util.List);
    }

    @Test
    public void binaryStringValue() {
        BencodeParser parser = new BencodeParser(new byte[]{
                '3', ':', 0x00, 0x01, 0x02});
        assertArrayEquals(new byte[]{0x00, 0x01, 0x02}, parser.readBytes());
    }

    // ── info 区间捕获 ──

    @Test
    public void infoBytesCapturedForTopLevelInfo() {
        String torrent = "d8:announce4:http4:infod4:name3:abc6:lengthi10eee";
        BencodeParser parser = new BencodeParser(bytes(torrent));
        parser.readDict();
        // info 区间必须是原始字节中的 info 字典本体（用于 info_hash）
        assertArrayEquals(bytes("d4:name3:abc6:lengthi10ee"), parser.getInfoBytes());
    }

    @Test
    public void nestedInfoKeyDoesNotPolluteCapture() {
        // 嵌套结构中的同名 info 键不得改写捕获区间（depth==1 限定）
        String torrent = "d4:infod4:name3:abc5:otherd4:infoi5eeee";
        BencodeParser parser = new BencodeParser(bytes(torrent));
        parser.readDict();
        assertArrayEquals(bytes("d4:name3:abc5:otherd4:infoi5eee"),
                parser.getInfoBytes());
    }

    @Test
    public void infoBytesEmptyWhenAbsent() {
        BencodeParser parser = new BencodeParser(bytes("d3:fooi1ee"));
        parser.readDict();
        assertEquals(0, parser.getInfoBytes().length);
    }

    // ── 边界与异常 ──

    @Test
    public void unclosedDictThrows() {
        try {
            new BencodeParser(bytes("d3:key")).readDict();
            fail("未闭合字典应抛 BencodeException");
        } catch (BencodeParser.BencodeException expected) {
        }
    }

    @Test
    public void unclosedListThrows() {
        try {
            new BencodeParser(bytes("l4:spam")).readList();
            fail("未闭合列表应抛 BencodeException");
        } catch (BencodeParser.BencodeException expected) {
        }
    }

    @Test
    public void illegalLengthPrefixThrows() {
        try {
            new BencodeParser(bytes("x4:ab")).readBytes();
            fail("非法长度前缀应抛 BencodeException");
        } catch (BencodeParser.BencodeException expected) {
        }
    }

    @Test
    public void emptyLengthThrows() {
        try {
            new BencodeParser(bytes(":abc")).readBytes();
            fail("空长度前缀应抛 BencodeException");
        } catch (BencodeParser.BencodeException expected) {
        }
    }

    @Test
    public void lengthOverflowThrows() {
        try {
            new BencodeParser(bytes("9:abc")).readBytes();
            fail("长度越界应抛 BencodeException");
        } catch (BencodeParser.BencodeException expected) {
        }
    }

    @Test
    public void unclosedIntThrows() {
        try {
            new BencodeParser(bytes("i42")).readInt();
            fail("未闭合整数应抛 BencodeException");
        } catch (BencodeParser.BencodeException expected) {
        }
    }

    @Test
    public void illegalIntThrows() {
        try {
            new BencodeParser(bytes("ixee")).readInt();
            fail("非法整数应抛 BencodeException");
        } catch (BencodeParser.BencodeException expected) {
        }
    }

    @Test
    public void illegalCharThrows() {
        try {
            new BencodeParser(bytes("z")).readDict();
            fail("非法起始字符应抛 BencodeException");
        } catch (BencodeParser.BencodeException expected) {
        }
    }

    @Test
    public void asTextNonBytesReturnsNull() {
        assertNull(BencodeParser.asText(42L));
        assertNull(BencodeParser.asText(null));
    }
}
