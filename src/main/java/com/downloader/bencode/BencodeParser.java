package com.downloader.bencode;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Bencode 编码解析器
 * 支持: 字符串、整数、列表、字典
 */
public class BencodeParser {

    private final byte[] data;
    private int pos;
    private int infoStart = -1;
    private int infoEnd = -1;

    public BencodeParser(byte[] data) {
        this.data = data;
        this.pos = 0;
    }

    public Map<String, Object> readDict() {
        if (data[pos] != 'd') {
            throw new BencodeException("Expected dict at pos " + pos);
        }
        pos++; // skip 'd'

        Map<String, Object> map = new LinkedHashMap<>();
        while (pos < data.length && data[pos] != 'e') {
            String key = readString();
            if ("info".equals(key)) {
                infoStart = pos;
            }
            Object val = readValue();
            if ("info".equals(key)) {
                infoEnd = pos;
            }
            map.put(key, val);
        }
        pos++; // skip 'e'
        return map;
    }

    public List<Object> readList() {
        pos++; // skip 'l'
        List<Object> list = new ArrayList<>();
        while (pos < data.length && data[pos] != 'e') {
            list.add(readValue());
        }
        pos++; // skip 'e'
        return list;
    }

    public String readString() {
        int colon = pos;
        while (colon < data.length && data[colon] != ':') {
            colon++;
        }
        if (colon >= data.length) {
            throw new BencodeException("Invalid string at pos " + pos);
        }

        int len = Integer.parseInt(
                new String(data, pos, colon - pos, StandardCharsets.UTF_8));
        pos = colon + 1;

        String s = new String(data, pos, len, StandardCharsets.UTF_8);
        pos += len;
        return s;
    }

    public byte[] readBytes() {
        int colon = pos;
        while (colon < data.length && data[colon] != ':') {
            colon++;
        }
        if (colon >= data.length) {
            throw new BencodeException("Invalid byte string at pos " + pos);
        }

        int len = Integer.parseInt(
                new String(data, pos, colon - pos, StandardCharsets.UTF_8));
        pos = colon + 1;

        byte[] b = Arrays.copyOfRange(data, pos, pos + len);
        pos += len;
        return b;
    }

    public Long readInt() {
        pos++; // skip 'i'
        int start = pos;
        while (pos < data.length && data[pos] != 'e') {
            pos++;
        }
        long val = Long.parseLong(
                new String(data, start, pos - start, StandardCharsets.UTF_8));
        pos++; // skip 'e'
        return val;
    }

    private Object readValue() {
        if (pos >= data.length) {
            throw new BencodeException("Unexpected end of data");
        }
        char c = (char) data[pos];
        switch (c) {
            case 'd': return readDict();
            case 'l': return readList();
            case 'i': return readInt();
            default:
                if (Character.isDigit(c)) return readString();
                throw new BencodeException(
                        "Invalid bencode char '" + c + "' at pos " + pos);
        }
    }

    /**
     * 获取 info 字典的原始字节（用于计算 info_hash）
     */
    public byte[] getInfoBytes() {
        if (infoStart < 0 || infoEnd < 0) {
            return new byte[0];
        }
        return Arrays.copyOfRange(data, infoStart, infoEnd);
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
