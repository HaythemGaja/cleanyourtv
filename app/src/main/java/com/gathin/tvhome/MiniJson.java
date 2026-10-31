package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser for the fork's tracker and remote code.
 * No Android imports, so the headless harness can use it too.
 * Values: Map (object), List (array), String, Long/Double,
 * Boolean, or null. Malformed input throws IllegalArgumentException.
 */
final class MiniJson {
    private MiniJson() {}

    static Object parse(String json) {
        if (json == null) throw new IllegalArgumentException("null json");
        Parser p = new Parser(json);
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw new IllegalArgumentException("trailing data");
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asObj(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asArr(Object v) {
        return v instanceof List ? (List<Object>) v : null;
    }

    static String asStr(Object v) {
        return v instanceof String ? (String) v : null;
    }

    static Boolean asBool(Object v) {
        return v instanceof Boolean ? (Boolean) v : null;
    }

    static Long asNum(Object v) {
        if (v instanceof Long) return (Long) v;
        if (v instanceof Double) return ((Double) v).longValue();
        return null;
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) { this.s = s; }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        Object value() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("eof");
            char c = s.charAt(i);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            if (c == 't') { lit("true"); return Boolean.TRUE; }
            if (c == 'f') { lit("false"); return Boolean.FALSE; }
            if (c == 'n') { lit("null"); return null; }
            return num();
        }

        void lit(String w) {
            if (!s.startsWith(w, i)) throw new IllegalArgumentException("bad literal");
            i += w.length();
        }

        Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = str();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw new IllegalArgumentException("expected :");
                i++;
                m.put(k, value());
                ws();
                if (i >= s.length()) throw new IllegalArgumentException("eof in object");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("expected , in object");
            }
        }

        List<Object> arr() {
            List<Object> out = new ArrayList<>();
            i++; // [
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return out; }
            while (true) {
                out.add(value());
                ws();
                if (i >= s.length()) throw new IllegalArgumentException("eof in array");
                char c = s.charAt(i++);
                if (c == ']') return out;
                if (c != ',') throw new IllegalArgumentException("expected , in array");
            }
        }

        String str() {
            if (i >= s.length() || s.charAt(i) != '"') throw new IllegalArgumentException("expected string");
            i++;
            StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    if (i >= s.length()) break;
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': b.append('"'); break;
                        case '\\': b.append('\\'); break;
                        case '/': b.append('/'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break;
                        case 't': b.append('\t'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw new IllegalArgumentException("bad \\u");
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: throw new IllegalArgumentException("bad escape");
                    }
                } else {
                    b.append(c);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        Object num() {
            int start = i;
            if (i < s.length() && s.charAt(i) == '-') i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            boolean dbl = false;
            if (i < s.length() && s.charAt(i) == '.') {
                dbl = true;
                i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                dbl = true;
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            if (i == start) throw new IllegalArgumentException("bad number");
            String t = s.substring(start, i);
            try {
                return dbl ? (Object) Double.parseDouble(t) : (Object) Long.parseLong(t);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad number");
            }
        }
    }
}
