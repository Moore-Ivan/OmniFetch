package com.downloader.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小 JSON 解析与序列化工具（项目刻意不引入第三方依赖）。
 * <p>解析仅支持标准 JSON 子集，返回类型：
 * 对象 → LinkedHashMap，数组 → ArrayList，字符串 → String，
 * 整数 → Long，小数 → Double，true/false → Boolean，null → null。
 * 任何语法错误抛 IOException。
 * <p>序列化支持 Map / List / String / Number / Boolean / null，
 * 输出带两空格缩进的格式化文本，便于人工查看与调试。
 */
public final class MiniJson {

    private MiniJson() {
    }

    // ══════════════════════════════════════════
    //  解析
    // ══════════════════════════════════════════

    public static Object parse(String json) throws IOException {
        Parser p = new Parser(json);
        p.skipWhitespace();
        Object value = p.readValue();
        p.skipWhitespace();
        if (p.pos != p.s.length()) {
            throw new IOException("JSON 解析：根值之后存在多余字符");
        }
        return value;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        private Parser(String s) {
            this.s = s;
        }

        private Object readValue() throws IOException {
            skipWhitespace();
            if (pos >= s.length()) {
                throw new IOException("JSON 解析：意外结束");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't', 'f' -> readBoolean();
                case 'n' -> readNull();
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() throws IOException {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw new IOException("JSON 解析：对象键必须是字符串");
                }
                String key = readString();
                skipWhitespace();
                expect(':');
                Object value = readValue();
                map.put(key, value);
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new IOException("JSON 解析：对象成员后应为 ',' 或 '}'");
                }
            }
        }

        private List<Object> readArray() throws IOException {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new IOException("JSON 解析：数组元素后应为 ',' 或 ']'");
                }
            }
        }

        private String readString() throws IOException {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw new IOException("JSON 解析：字符串未闭合");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c < 0x20) {
                    throw new IOException("JSON 解析：字符串中存在未转义控制字符");
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= s.length()) {
                    throw new IOException("JSON 解析：转义序列不完整");
                }
                char esc = s.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> sb.append(readUnicodeEscape());
                    default -> throw new IOException("JSON 解析：非法转义 \\" + esc);
                }
            }
        }

        private char readUnicodeEscape() throws IOException {
            if (pos + 4 > s.length()) {
                throw new IOException("JSON 解析：\\u 转义需要 4 位十六进制数");
            }
            String hex = s.substring(pos, pos + 4);
            try {
                char ch = (char) Integer.parseInt(hex, 16);
                pos += 4;
                return ch;
            } catch (NumberFormatException e) {
                throw new IOException("JSON 解析：非法 \\u 转义 " + hex);
            }
        }

        private Object readNumber() throws IOException {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            if (pos >= s.length() || !Character.isDigit(s.charAt(pos))) {
                throw new IOException("JSON 解析：非法数字");
            }
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                pos++;
            }
            boolean floating = false;
            if (pos < s.length() && s.charAt(pos) == '.') {
                floating = true;
                pos++;
                if (pos >= s.length() || !Character.isDigit(s.charAt(pos))) {
                    throw new IOException("JSON 解析：小数部分缺少数字");
                }
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                floating = true;
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                if (pos >= s.length() || !Character.isDigit(s.charAt(pos))) {
                    throw new IOException("JSON 解析：指数部分缺少数字");
                }
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            String num = s.substring(start, pos);
            try {
                // 注意不能写成 `floating ? Double.parseDouble(..) : Long.parseLong(..)`：
                // 两分支均为数值时条件表达式按数值提升统一为 double，整数会被装箱成 Double
                if (floating) {
                    return Double.parseDouble(num);
                }
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                throw new IOException("JSON 解析：无法解析数字 " + num);
            }
        }

        private Boolean readBoolean() throws IOException {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IOException("JSON 解析：非法字面量");
        }

        private Object readNull() throws IOException {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IOException("JSON 解析：非法字面量");
        }

        private void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        private char peek() throws IOException {
            if (pos >= s.length()) {
                throw new IOException("JSON 解析：意外结束");
            }
            return s.charAt(pos);
        }

        private char next() throws IOException {
            if (pos >= s.length()) {
                throw new IOException("JSON 解析：意外结束");
            }
            return s.charAt(pos++);
        }

        private void expect(char expected) throws IOException {
            if (pos >= s.length() || s.charAt(pos) != expected) {
                throw new IOException("JSON 解析：期望 '" + expected + "'");
            }
            pos++;
        }
    }

    // ══════════════════════════════════════════
    //  序列化（两空格缩进格式化输出）
    // ══════════════════════════════════════════

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(1024);
        writeValue(sb, value, 0);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value, int indent) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String str) {
            writeString(sb, str);
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            writeObject(sb, map, indent);
        } else if (value instanceof List<?> list) {
            writeArray(sb, list, indent);
        } else {
            // 兜底：枚举等未知类型按字符串输出
            writeString(sb, value.toString());
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map, int indent) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            indent(sb, indent + 1);
            writeString(sb, String.valueOf(entry.getKey()));
            sb.append(": ");
            writeValue(sb, entry.getValue(), indent + 1);
            if (++i < map.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list, int indent) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            indent(sb, indent + 1);
            writeValue(sb, list.get(i), indent + 1);
            if (i < list.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static void indent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) {
            sb.append("  ");
        }
    }
}
