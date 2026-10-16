package com.downloader.bencode;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Bencode 编码解析器
 * 支持: 字符串（字节串）、整数、列表、字典。
 *
 * 重要语义：Bencode 的字符串本质是字节序列（如种子文件中的 pieces 是二进制 SHA-1 拼接），
 * 因此 {@link #readValue()} 对字符串统一返回 {@code byte[]}；
 * 只有字典 key 按 UTF-8 解码为 String。需要文本时由上层用 {@link #asText(Object)} 转换。
 */
public class BencodeParser {

    private final byte[] data;
    private int pos;
    /** info 字典在原始字节中的区间 [infoStart, infoEnd)，用于计算 info_hash；只记录首个顶层 info */
    private int infoStart = -1;
    private int infoEnd = -1;

    public BencodeParser(byte[] data) {
        this.data = Objects.requireNonNull(data, "bencode data");
        this.pos = 0;
    }

    public Map<String, Object> readDict() {
        expect('d');
        pos++; // skip 'd'

        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            if (pos >= data.length) {
                throw new BencodeException("字典未闭合，位置 " + pos);
            }
            if (data[pos] == 'e') {
                pos++; // skip 'e'
                return map;
            }
            String key = readKeyString();
            // 仅记录第一个 info 键（顶层 info 先于其内部嵌套字典被解析），
            // 避免嵌套字典中同名键覆盖 info_hash 区间
            boolean captureInfo = "info".equals(key) && infoStart < 0;
            if (captureInfo) {
                infoStart = pos;
            }
            Object val = readValue();
            if (captureInfo) {
                infoEnd = pos;
            }
            map.put(key, val);
        }
    }

    public List<Object> readList() {
        expect('l');
        pos++; // skip 'l'
        List<Object> list = new ArrayList<>();
        while (true) {
            if (pos >= data.length) {
                throw new BencodeException("列表未闭合，位置 " + pos);
            }
            if (data[pos] == 'e') {
                pos++; // skip 'e'
                return list;
            }
            list.add(readValue());
        }
    }

    /** 读取字节串（Bencode 字符串的原生形态） */
    public byte[] readBytes() {
        int colon = pos;
        while (colon < data.length && data[colon] != ':') {
            if (data[colon] < '0' || data[colon] > '9') {
                throw new BencodeException("字节串长度前缀非法，位置 " + pos);
            }
            colon++;
        }
        if (colon >= data.length) {
            throw new BencodeException("字节串缺少冒号，位置 " + pos);
        }

        int len = parseLength(pos, colon);
        int start = colon + 1;
        if (len < 0 || start > data.length - len) {
            throw new BencodeException(
                    "字节串越界：start=" + start + ", len=" + len + ", total=" + data.length);
        }
        byte[] b = Arrays.copyOfRange(data, start, start + len);
        pos = start + len;
        return b;
    }

    /** 仅供字典 key 使用：按 UTF-8 解码字节串 */
    public String readKeyString() {
        return new String(readBytes(), StandardCharsets.UTF_8);
    }

    /**
     * 兼容旧调用：读取文本字符串。
     * @deprecated Bencode 字符串是字节序列，新代码请使用 {@link #readBytes()}；
     *             解析值用 {@link #readValue()}（返回 byte[]）。
     */
    @Deprecated
    public String readString() {
        return new String(readBytes(), StandardCharsets.UTF_8);
    }

    public Long readInt() {
        expect('i');
        pos++; // skip 'i'
        int start = pos;
        while (pos < data.length && data[pos] != 'e') {
            pos++;
        }
        if (pos >= data.length) {
            throw new BencodeException("整数未闭合，位置 " + start);
        }
        String numStr = new String(data, start, pos - start, StandardCharsets.UTF_8);
        pos++; // skip 'e'
        try {
            return Long.parseLong(numStr);
        } catch (NumberFormatException e) {
            throw new BencodeException("非法整数: \"" + numStr + "\"");
        }
    }

    private Object readValue() {
        if (pos >= data.length) {
            throw new BencodeException("意外的数据结尾");
        }
        char c = (char) data[pos];
        switch (c) {
            case 'd': return readDict();
            case 'l': return readList();
            case 'i': return readInt();
            default:
                if (c >= '0' && c <= '9') {
                    // Bencode 字符串统一以 byte[] 承载，pieces 等二进制字段才能原样保留
                    return readBytes();
                }
                throw new BencodeException(
                        "非法 bencode 字符 '" + c + "'，位置 " + pos);
        }
    }

    private void expect(char c) {
        if (pos >= data.length || data[pos] != c) {
            throw new BencodeException(
                    "期望 '" + c + "' 但位置 " + pos + " 处是 "
                            + (pos < data.length ? "'" + (char) data[pos] + "'" : "EOF"));
        }
    }

    private int parseLength(int from, int colon) {
        // 长度前缀至少 1 位数字
        if (colon == from) {
            throw new BencodeException("字节串长度为空，位置 " + from);
        }
        try {
            return Integer.parseInt(
                    new String(data, from, colon - from, StandardCharsets.UTF_8));
        } catch (NumberFormatException e) {
            throw new BencodeException("字节串长度非法，位置 " + from);
        }
    }

    /**
     * 获取 info 字典的原始字节（用于计算 info_hash），未找到时返回空数组
     */
    public byte[] getInfoBytes() {
        if (infoStart < 0 || infoEnd < 0 || infoEnd > data.length) {
            return new byte[0];
        }
        return Arrays.copyOfRange(data, infoStart, infoEnd);
    }

    /** 把解析出的字节串值按 UTF-8 转成文本；非 byte[] 返回 null */
    public static String asText(Object value) {
        return value instanceof byte[] b
                ? new String(b, StandardCharsets.UTF_8) : null;
    }

    /**
     * Bencode 解析异常
     */
    public static class BencodeException extends RuntimeException {
        public BencodeException(String message) {
            super(message);
        }
    }
}
