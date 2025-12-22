package org.example;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Character-level XML in-place editor.
 * <p>
 * Features:
 * • Boolean search (tag text / attribute) via simple XPath ("/" separated tags, optional @attr).
 * • Conditional/unconditional replacement of element text or attribute value.
 * • Removal of element (and its subtree) preserving formatting.
 * • BOM + UTF-8/UTF-16/GBK detection, mixed EOL, comments & indentation preservation.
 * • Thread-safe – all public methods are stateless.
 */
public final class XmlInPlaceEditor {

    private XmlInPlaceEditor() {
    }

    /* ===================== Public Byte Array API ===================== */
    
    public static byte[] setValue(byte[] bytes, String xPath, String newVal) throws IOException {
        return setValue(bytes, xPath, null, newVal, null);
    }

    public static byte[] setValue(byte[] bytes, String xPath, String expectedOld, String newVal) throws IOException {
        return setValue(bytes, xPath, expectedOld, newVal, null);
    }

    public static byte[] setValue(byte[] bytes, String xPath, String expectedOld, String newVal, String encHint) throws IOException {
        Encoding info = Encoding.detect(bytes, encHint);
        String xml = new String(bytes, info.offset, bytes.length - info.offset, info.cs);

        Locator loc = new Locator(xml);
        Match m = loc.locate(xPath);
        if (m == null) throw new IllegalArgumentException("XPath not found: " + xPath);
        if (expectedOld != null) {
            String cur = xml.substring(m.valStart, m.valEnd);
            if (!stripQuote(cur).equals(stripQuote(expectedOld))) return bytes; // unchanged
        }

        String before = xml.substring(0, m.valStart);
        String after = xml.substring(m.valEnd);
        String rep = newVal == null ? "" : newVal;
        String outStr = before + rep + after;
        return info.encode(outStr);
    }

    public static byte[] deleteTag(byte[] bytes, String xPath) throws IOException {
        return deleteTag(bytes, xPath, null, null);
    }

    public static byte[] deleteTag(byte[] bytes, String xPath, String expectedValAttr) throws IOException {
        return deleteTag(bytes, xPath, expectedValAttr, null);
    }

    public static byte[] deleteTag(byte[] bytes, String xPath, String expectedValAttr, String encHint) throws IOException {
        Encoding info = Encoding.detect(bytes, encHint);
        String xml = new String(bytes, info.offset, bytes.length - info.offset, info.cs);

        Locator loc = new Locator(xml);
        Match m = loc.locate(xPath);
        if (m == null) throw new IllegalArgumentException("XPath not found: " + xPath);
        if (expectedValAttr != null) {
            String cur = xml.substring(m.valStart, m.valEnd);
            if (!stripQuote(cur).equals(stripQuote(expectedValAttr))) return bytes;
        }

        int delStart = m.tagStart;
        int delEnd = m.tagEnd;

        boolean tagMultiLine = xml.substring(delStart, delEnd).indexOf('\n') >= 0 || xml.substring(delStart, delEnd).indexOf('\r') >= 0;
        if (!tagMultiLine) {
            // determine if other non-whitespace content exists on the same line AFTER the tag
            boolean rightContent = false;
            int p = delEnd;
            while (p < xml.length() && xml.charAt(p) != '\n' && xml.charAt(p) != '\r') {
                if (!Character.isWhitespace(xml.charAt(p))) {
                    rightContent = true;
                    break;
                }
                p++;
            }

            // determine if other content exists on the same line BEFORE the tag
            boolean leftContent = false;
            int q = delStart - 1;
            while (q >= 0 && xml.charAt(q) != '\n' && xml.charAt(q) != '\r') {
                if (!Character.isWhitespace(xml.charAt(q))) {
                    leftContent = true;
                    break;
                }
                q--;
            }

            if (!rightContent && !leftContent) {
                // entire line can be removed (keep EOL for previous line)
                while (delStart > 0 && xml.charAt(delStart - 1) != '\n' && xml.charAt(delStart - 1) != '\r' && Character.isWhitespace(xml.charAt(delStart - 1)))
                    delStart--;
                while (delEnd < xml.length() && xml.charAt(delEnd) != '\n' && xml.charAt(delEnd) != '\r' && Character.isWhitespace(xml.charAt(delEnd)))
                    delEnd++;
                // include trailing EOLs
                if (delEnd < xml.length() && xml.charAt(delEnd) == '\r') delEnd++;
                if (delEnd < xml.length() && xml.charAt(delEnd) == '\n') delEnd++;
            }
        }

        String out = xml.substring(0, delStart) + xml.substring(delEnd);
        // collapse potential blank lines introduced by the deletion (newline + spaces + newline)
        out = out.replaceAll("(?m)(\\r?\\n)[ \\t]*(\\r?\\n)", "$1");
        return info.encode(out);
    }

    public static boolean search(byte[] bytes, String xPath) throws IOException {
        return search(bytes, xPath, null, null);
    }

    public static boolean search(byte[] bytes, String xPath, String valAttr) throws IOException {
        return search(bytes, xPath, valAttr, null);
    }

    public static boolean search(byte[] bytes, String xPath, String valAttr, String encHint) throws IOException {
        Encoding info = Encoding.detect(bytes, encHint);
        String xml = new String(bytes, info.offset, bytes.length - info.offset, info.cs);
        Locator loc = new Locator(xml);
        Match m = loc.locate(xPath);
        if (m == null) return false;
        if (valAttr == null) return true;
        String cur = xml.substring(m.valStart, m.valEnd);
        return stripQuote(cur).equals(stripQuote(valAttr));
    }

    /* ===================== Implementation helpers ===================== */

    private static class Match {
        int tagStart;
        int tagEnd; // full tag text
        int valStart;
        int valEnd; // text or attr value part
    }

    /* Very small XPath subset:
       path ::= step ('/' step)*
       step ::= tagName | '@attr'
       We allow last step to be attribute.
    */
    private static class Locator {
        private final String s;
        int pos;
        private final Deque<String> stack = new ArrayDeque<>();

        Locator(String s) {
            this.s = s;
        }

        Match locate(String xpath) {
            // normalize parts (strip [index])
            List<String> raw = Arrays.asList(xpath.split("/"));
            List<String> parts = new ArrayList<>(raw.size());
            for (String p : raw) {
                int b = p.indexOf('[');
                if (b > 0) p = p.substring(0, b);
                parts.add(p);
            }
            while (pos < s.length()) {
                if (s.charAt(pos) == '<') {
                    if (pos + 1 < s.length() && s.charAt(pos + 1) == '!') {
                        skipComment();
                        continue;
                    }
                    if (pos + 1 < s.length() && s.charAt(pos + 1) == '/') { // close tag
                        pos += 2;
                        String name = parseName();
                        stack.pollLast();
                        skipUntil('>');
                        pos++;
                        continue;
                    }
                    int tagStart = pos;
                    pos++;
                    boolean selfClose = false;
                    String name = parseName();
                    stack.addLast(name);
                    // attributes
                    int attrValStart = -1, attrValEnd = -1;
                    String targetAttr = null;
                    if (parts.get(parts.size() - 1).startsWith("@"))
                        targetAttr = parts.get(parts.size() - 1).substring(1);

                    while (pos < s.length() && s.charAt(pos) != '>' && !(s.charAt(pos) == '/' && pos + 1 < s.length() && s.charAt(pos + 1) == '>')) {
                        skipWs();
                        if (s.charAt(pos) == '>' || (s.charAt(pos) == '/' && pos + 1 < s.length() && s.charAt(pos + 1) == '>'))
                            break;
                        String attr = parseName();
                        skipWs();
                        if (pos < s.length() && s.charAt(pos) == '=') {
                            pos++;
                            skipWs();
                            char quote = s.charAt(pos++);
                            int vStart = pos;
                            while (pos < s.length() && s.charAt(pos) != quote) pos++;
                            int vEnd = pos;
                            pos++;
                            if (targetAttr != null && attr.equals(targetAttr)) {
                                attrValStart = vStart;
                                attrValEnd = vEnd;
                            }
                        }
                    }
                    if (pos < s.length() && s.charAt(pos) == '/' && pos + 1 < s.length() && s.charAt(pos + 1) == '>') {
                        selfClose = true;
                        pos += 2;
                    } else {
                        pos++;
                    }
                    int tagEnd = selfClose ? pos : findClosing(name);
                    // check path match
                    if (matchPath(parts)) {
                        Match m = new Match();
                        m.tagStart = tagStart;
                        m.tagEnd = tagEnd;
                        if (parts.get(parts.size() - 1).startsWith("@")) {
                            m.valStart = attrValStart;
                            m.valEnd = attrValEnd;
                        } else { // element text
                            int txtStart = findTextStart(tagStart, name);
                            int txtEnd = findTextEnd(txtStart, name);
                            m.valStart = txtStart;
                            m.valEnd = txtEnd;
                        }
                        return m;
                    }
                    if (selfClose) stack.pollLast();
                    continue;
                }
                pos++;
            }
            return null;
        }

        private boolean matchPath(List<String> parts) {
            if (stack.size() != parts.size() && !(parts.get(parts.size() - 1).startsWith("@") && stack.size() == parts.size() - 1))
                return false;
            int i = 0;
            for (String t : stack) {
                if (!t.equals(parts.get(i++))) return false;
            }
            return true;
        }

        private int findClosing(String name) {
            int p = pos; // start searching from current position
            int depth = 1;
            while (p < s.length()) {
                if (s.charAt(p) == '<') {
                    if (p + 1 < s.length()) {
                        char c = s.charAt(p + 1);
                        if (c == '!') {               // comment or doctype
                            p = skipComment(p);
                            continue;
                        }
                        if (c == '/') {               // closing tag
                            int q = p + 2;
                            String n = parseName(s, q);
                            if (n.equals(name)) {
                                depth--;
                                if (depth == 0) {
                                    q = s.indexOf('>', q);
                                    return q + 1;      // position after closing '>'
                                }
                            }
                        } else {                       // opening tag
                            String n = parseName(s, p + 1);
                            if (n.equals(name)) {
                                // check if NOT self-closing
                                int gt = s.indexOf('>', p + 1);
                                if (gt > 0 && s.charAt(gt - 1) != '/') {
                                    depth++;
                                }
                            }
                        }
                    }
                }
                p++;
            }
            return s.length();
        }

        private int findTextStart(int tagStart, String name) {
            int openEnd = s.indexOf('>', tagStart);
            if (openEnd < 0) return openEnd;
            if (s.charAt(openEnd - 1) == '/') return openEnd;
            return openEnd + 1;
        }

        private int findTextEnd(int txtStart, String name) {
            int closePos = s.indexOf("</" + name, txtStart);
            return closePos < 0 ? txtStart : closePos;
        }

        private String parseName() {
            int start = pos;
            while (pos < s.length() && !isNameDelimiter(s.charAt(pos))) pos++;
            return s.substring(start, pos);
        }

        private static String parseName(String str, int p) {
            int start = p;
            while (p < str.length() && !isNameDelimiter(str.charAt(p))) p++;
            return str.substring(start, p);
        }

        private static boolean isNameDelimiter(char c) {
            return Character.isWhitespace(c) || c == '/' || c == '>' || c == '=';
        }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        private void skipUntil(char c) {
            while (pos < s.length() && s.charAt(pos) != c) pos++;
        }

        private void skipComment() { // assume pos at '<!'
            int end = s.indexOf("-->", pos);
            if (end < 0) pos = s.length();
            else pos = end + 3;
        }

        private int skipComment(int p) {
            int end = s.indexOf("-->", p);
            return end < 0 ? s.length() : end + 3;
        }
    }

    private static class Encoding {
        final Charset cs;
        final int offset;

        Encoding(Charset cs, int off) {
            this.cs = cs;
            this.offset = off;
        }

        byte[] encode(String str) {
            byte[] body = str.getBytes(cs);
            if (offset == 0) return body;
            byte[] out = new byte[offset + body.length];
            if (cs == StandardCharsets.UTF_8) {
                out[0] = (byte) 0xEF;
                out[1] = (byte) 0xBB;
                out[2] = (byte) 0xBF;
            } else if (cs.name().startsWith("UTF-16BE")) {
                out[0] = (byte) 0xFE;
                out[1] = (byte) 0xFF;
            } else if (cs.name().startsWith("UTF-16LE")) {
                out[0] = (byte) 0xFF;
                out[1] = (byte) 0xFE;
            }
            System.arraycopy(body, 0, out, offset, body.length);
            return out;
        }

        static Encoding detect(byte[] bytes, String hint) {
            if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF)
                return new Encoding(StandardCharsets.UTF_8, 3);
            if (bytes.length >= 2 && bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF)
                return new Encoding(StandardCharsets.UTF_16BE, 2);
            if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE)
                return new Encoding(StandardCharsets.UTF_16LE, 2);
            Charset cs = hint != null ? Charset.forName(hint) : StandardCharsets.UTF_8;
            return new Encoding(cs, 0);
        }
    }

    private static String stripQuote(String v) {
        if (v == null) return "";
        v = v.trim();
        if (v.startsWith("\"") && v.endsWith("\"")) return v.substring(1, v.length() - 1);
        return v;
    }
} 