package org.example;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-place JSON editor that operates on plain text without reparsing/serialising,
 * thereby preserving every unrelated byte (indentation, spaces, comments, EOLs,
 * encoding, BOM).  It supports simple slash-separated object paths (arrays may
 * be referenced via numeric components) and performs three kinds of mutation:
 *   – replace value               (REPLACE)
 *   – clear value (keep the key)  (CLEAR_VALUE)
 *   – delete the entire key/line  (DELETE_LINE)
 * All operations have conditional variants that execute only when the current
 * value matches an expected one, providing optimistic concurrency.
 * <p>
 * Restrictions: this utility targets <em>single-line</em> scalar values (string,
 * number, boolean, null).  Multiline strings / objects / arrays as values are
 * not supported for mutation (they are still preserved if untouched).
 */
public final class JsonInPlaceEditor {

    // --- Public API (File) -------------------------------------------------

    public static void setValue(File file, String jsonPath, String newValue) throws IOException {
        setValue(file, jsonPath, null, newValue, null);
    }

    public static void setValue(File file, String jsonPath, String expectedOld, String newValue) throws IOException {
        setValue(file, jsonPath, expectedOld, newValue, null);
    }

    public static void setValue(File file, String jsonPath, String expectedOld, String newValue, String encodingHint) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(jsonPath, "jsonPath");
        byte[] original = Files.readAllBytes(file.toPath());
        FileInfo info = FileInfo.get(original, encodingHint);
        Op op = (newValue == null || newValue.isEmpty()) ? Op.CLEAR_VALUE : Op.REPLACE;
        String val = (newValue == null) ? "" : newValue;
        byte[] out = editInternal(original, info, toPath(jsonPath), val, expectedOld, op);
        Files.write(file.toPath(), out);
    }

    public static void deleteLine(File file, String jsonPath) throws IOException { deleteLine(file, jsonPath, null, null); }
    public static void deleteLine(File file, String jsonPath, String expectedOld) throws IOException { deleteLine(file, jsonPath, expectedOld, null); }
    public static void deleteLine(File file, String jsonPath, String expectedOld, String encodingHint) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(jsonPath, "jsonPath");
        byte[] original = Files.readAllBytes(file.toPath());
        FileInfo info = FileInfo.get(original, encodingHint);
        byte[] out = editInternal(original, info, toPath(jsonPath), null, expectedOld, Op.DELETE_LINE);
        Files.write(file.toPath(), out);
    }

    // --- Public API (InputStream) -----------------------------------------

    public static byte[] setValue(InputStream in, String jsonPath, String newValue) throws IOException {
        return setValue(in, jsonPath, null, newValue, null);
    }
    public static byte[] setValue(InputStream in, String jsonPath, String expectedOld, String newValue) throws IOException {
        return setValue(in, jsonPath, expectedOld, newValue, null);
    }
    public static byte[] setValue(InputStream in, String jsonPath, String expectedOld, String newValue, String encodingHint) throws IOException {
        byte[] original = in.readAllBytes();
        FileInfo info = FileInfo.get(original, encodingHint);
        Op op = (newValue == null || newValue.isEmpty()) ? Op.CLEAR_VALUE : Op.REPLACE;
        String val = (newValue == null) ? "" : newValue;
        return editInternal(original, info, toPath(jsonPath), val, expectedOld, op);
    }

    public static byte[] deleteLine(InputStream in, String jsonPath) throws IOException { return deleteLine(in, jsonPath, null, null); }
    public static byte[] deleteLine(InputStream in, String jsonPath, String expectedOld) throws IOException { return deleteLine(in, jsonPath, expectedOld, null); }
    public static byte[] deleteLine(InputStream in, String jsonPath, String expectedOld, String encodingHint) throws IOException {
        byte[] original = in.readAllBytes();
        FileInfo info = FileInfo.get(original, encodingHint);
        return editInternal(original, info, toPath(jsonPath), null, expectedOld, Op.DELETE_LINE);
    }

    // --- Core ----------------------------------------------------------------

    private enum Op { REPLACE, CLEAR_VALUE, DELETE_LINE }

    private static byte[] editInternal(byte[] originalBytes, FileInfo info, List<String> path, String newValue, String expectedOld, Op op) {
        String content = new String(originalBytes, info.offset, originalBytes.length - info.offset, info.charset);

        List<String> lines = splitLines(content);
        Deque<PathEntry> stack = new ArrayDeque<>();
        Deque<Integer> indexStack = new ArrayDeque<>(); // parallel to array properties

        for (int i = 0; i < lines.size(); i++) {
            String full = lines.get(i);
            String logical = stripEol(full);
            String eol = full.substring(logical.length());
            String trimmed = logical.trim();

            updateBraceStackBefore(stack, indexStack, trimmed);

            Matcher kv = PROP_PATTERN.matcher(logical);
            if (kv.find()) {
                String key = kv.group(2);
                // Build current path candidate
                List<String> candidate = new ArrayList<>();
                for (PathEntry p : stack) candidate.add(p.key);
                candidate.add(key);

                if (candidate.equals(path)) {
                    // Extract pieces for reconstruction
                    String indent = kv.group(1);
                    String spacesBeforeColon = kv.group(3);
                    String spacesAfterColon = kv.group(4);
                    String valueAndRest = kv.group(5);

                    ValParts vp = splitValueAndRest(valueAndRest);
                    // expected value check (ignore surrounding quotes for convenience)
                    if (expectedOld != null) {
                        String actual = vp.value.trim();
                        if (actual.length() >= 2 && actual.startsWith("\"") && actual.endsWith("\"")) {
                            actual = actual.substring(1, actual.length()-1);
                        }
                        String exp = expectedOld;
                        if (exp.length() >= 2 && exp.startsWith("\"") && exp.endsWith("\"")) {
                            exp = exp.substring(1, exp.length()-1);
                        }
                        if (!actual.equals(exp)) {
                            continue; // value mismatch
                        }
                    }

                    switch (op) {
                        case REPLACE -> {
                            String newLogical = indent + "\"" + key + "\"" + spacesBeforeColon + ":" + spacesAfterColon + newValue + vp.trailing;
                            lines.set(i, newLogical + eol);
                        }
                        case CLEAR_VALUE -> {
                            String newLogical = indent + "\"" + key + "\"" + spacesBeforeColon + ":" + spacesAfterColon + "\"\"" + vp.trailing;
                            lines.set(i, newLogical + eol);
                        }
                        case DELETE_LINE -> {
                            boolean deletedHadComma = logical.trim().endsWith(",") && !logical.trim().endsWith(",,");
                            lines.remove(i);

                            // Determine if deleted line was the last property inside its object
                            int nextIdx = i;
                            while (nextIdx < lines.size()) {
                                String nxtTrim = stripEol(lines.get(nextIdx)).trim();
                                if (!nxtTrim.isEmpty() && !nxtTrim.startsWith("//")) break;
                                nextIdx++;
                            }

                            String nt = nextIdx < lines.size() ? stripEol(lines.get(nextIdx)).trim() : "";
                            boolean objectClosedNext = (nextIdx >= lines.size()) || nt.startsWith("}") || nt.startsWith("]");

                            if (!deletedHadComma && objectClosedNext && i-1 >= 0) {
                                String nextTrim = stripEol(lines.get(nextIdx)).trim();
                                boolean nextHasComma = nextTrim.endsWith(",");
                                if (!nextHasComma) {
                                    String prevFull = lines.get(i-1);
                                    String prevLogical = stripEol(prevFull);
                                    int commaIdx = prevLogical.lastIndexOf(',');
                                    if (commaIdx >= 0) {
                                        String newPrev = prevLogical.substring(0, commaIdx) + prevFull.substring(prevLogical.length());
                                        lines.set(i-1, newPrev);
                                    }
                                }
                            }
                            i--; // stay at same index after removal
                        }
                    }
                    return assemble(lines, info);
                }
            }

            updateBraceStackAfter(stack, indexStack, trimmed);
        }
        throw new IllegalArgumentException("Path not found or not a scalar: " + String.join("/", path));
    }

    // --- Parsing helpers ------------------------------------------------------

    private static final Pattern PROP_PATTERN = Pattern.compile("^(\\s*)\"([^\"]+)\"(\\s*):(\\s*)(.*)$");

    // ------------------------------------------------------------
    // Brace / bracket stack helpers – now with array awareness
    // ------------------------------------------------------------

    private static void updateBraceStackBefore(Deque<PathEntry> stack, Deque<Integer> indexStack, String trimmed) {
        int idx = 0;
        while (idx < trimmed.length() && Character.isWhitespace(trimmed.charAt(idx))) idx++;
        if (idx >= trimmed.length()) return;

        char ch = trimmed.charAt(idx);
        switch (ch) {
            case '}': {
                // Close object – pop path segment if present (could be key or numeric index)
                if (!stack.isEmpty()) {
                    stack.removeLast();
                    // If we just closed an array element (numeric), nothing else required.
                }
                break;
            }
            case ']': {
                // Leaving an array – pop array property and its counter
                if (!stack.isEmpty()) stack.removeLast();
                if (!indexStack.isEmpty()) indexStack.removeLast();
                break;
            }
            default: break;
        }
    }

    private static void updateBraceStackAfter(Deque<PathEntry> stack, Deque<Integer> indexStack, String trimmed) {
        // Case 1 & 2: property definition line
        Matcher m = PROP_PATTERN.matcher(trimmed);
        if (m.find()) {
            String key = m.group(2);
            String valueAndRest = m.group(5).trim();
            if (valueAndRest.startsWith("{")) {
                stack.addLast(new PathEntry(key));
                return;
            }
            if (valueAndRest.startsWith("[")) {
                stack.addLast(new PathEntry(key));
                // push new counter for this array (start at 0)
                indexStack.addLast(0);
                return;
            }
        }

        // Case 3: opening brace for array element object
        if (trimmed.startsWith("{")) {
            if (!indexStack.isEmpty() && !stack.isEmpty()) {
                int currIdx = indexStack.removeLast();
                stack.addLast(new PathEntry(String.valueOf(currIdx)));
                indexStack.addLast(currIdx + 1); // increment for next sibling
            }
        }
    }

    private record PathEntry(String key) { }

    private record ValParts(String value, String trailing) { }

    private static ValParts splitValueAndRest(String valueAndRest) {
        // Detect comment start outside quotes
        int commentIdx = findCommentStart(valueAndRest);
        String beforeComment = commentIdx >= 0 ? valueAndRest.substring(0, commentIdx) : valueAndRest;
        String commentPart = commentIdx >= 0 ? valueAndRest.substring(commentIdx) : "";

        // detect trailing comma (outside quotes)
        String rtrim = rtrim(beforeComment);
        boolean hasComma = rtrim.endsWith(",");
        String valuePart = hasComma ? rtrim.substring(0, rtrim.length() - 1) : rtrim;
        String trailing = beforeComment.substring(valuePart.length()) + commentPart; // already contains comma/spaces if present
        return new ValParts(valuePart, trailing);
    }

    private static String rtrim(String s) {
        int i = s.length();
        while (i > 0 && Character.isWhitespace(s.charAt(i - 1))) i--;
        return s.substring(0, i);
    }

    private static int findCommentStart(String s) {
        boolean inString = false; boolean escape = false;
        for (int i = 0; i < s.length() - 1; i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\' && !escape) { escape = true; continue; }
                if (c == '"' && !escape) inString = false;
                escape = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == '/' && (s.charAt(i+1) == '/' || s.charAt(i+1) == '*')) return i;
        }
        return -1;
    }

    private static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = LINE_SPLIT.matcher(text);
        while (m.find()) {
            if (m.group(1).isEmpty()) break;
            out.add(m.group(1));
        }
        return out;
    }

    private static final Pattern LINE_SPLIT = Pattern.compile("(.*?(?:\\r\\n|\\r|\\n|$))", Pattern.DOTALL);

    private static String stripEol(String full) { return full.replaceAll("(\\r\\n|\\r|\\n)$", ""); }

    private static byte[] assemble(List<String> lines, FileInfo info) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l);
        byte[] body = sb.toString().getBytes(info.charset);
        if (info.bom.length > 0) {
            byte[] out = new byte[info.bom.length + body.length];
            System.arraycopy(info.bom, 0, out, 0, info.bom.length);
            System.arraycopy(body, 0, out, info.bom.length, body.length);
            return out;
        }
        return body;
    }

    // --- FileInfo + encoding/eol detection -----------------------------------

    private record FileInfo(Charset charset, String eol, List<String> originalEols, boolean lastLineNoEol, byte[] bom, int offset) {
        static FileInfo get(byte[] content, String encodingHint) {
            if (encodingHint == null || encodingHint.isEmpty()) {
                return detect(content);
            }
            FileInfo base = detect(content);
            Charset cs = Charset.forName(encodingHint);
            return new FileInfo(cs, base.eol, base.originalEols, base.lastLineNoEol, base.bom, base.offset);
        }
        static FileInfo detect(byte[] content) {
            EncodingInfo enc = EncodingUtil.detectEncoding(content);
            EolInfo eol = EolUtil.detectLineEndings(content, enc.charset);
            return new FileInfo(enc.charset, eol.eol, eol.originalEols, eol.lastLineNoEol, enc.bom, enc.offset);
        }
    }

    private static final class EncodingUtil {
        private static final byte[] UTF8_BOM = {(byte)0xEF,(byte)0xBB,(byte)0xBF};
        private static final byte[] UTF16BE_BOM = {(byte)0xFE,(byte)0xFF};
        private static final byte[] UTF16LE_BOM = {(byte)0xFF,(byte)0xFE};
        static EncodingInfo detectEncoding(byte[] content) {
            if (startsWith(content, UTF8_BOM)) return new EncodingInfo(StandardCharsets.UTF_8, UTF8_BOM, 3);
            if (startsWith(content, UTF16BE_BOM)) return new EncodingInfo(StandardCharsets.UTF_16BE, UTF16BE_BOM, 2);
            if (startsWith(content, UTF16LE_BOM)) return new EncodingInfo(StandardCharsets.UTF_16LE, UTF16LE_BOM, 2);
            // Fallback – try UTF-8 else GBK
            try { new String(content, StandardCharsets.UTF_8); return new EncodingInfo(StandardCharsets.UTF_8, new byte[0], 0);} catch(Exception ignored){}
            return new EncodingInfo(Charset.forName("GBK"), new byte[0], 0);
        }
        private static boolean startsWith(byte[] src, byte[] prefix){ if(src.length<prefix.length) return false; for(int i=0;i<prefix.length;i++) if(src[i]!=prefix[i]) return false; return true; }
    }
    private record EncodingInfo(Charset charset, byte[] bom, int offset) {}

    private static final class EolUtil {
        private static final Pattern P = Pattern.compile("(\\r\\n|\\r|\\n)");
        static EolInfo detectLineEndings(byte[] content, Charset cs){
            String t = new String(content, cs);
            Matcher m = P.matcher(t);
            List<String> eols = new ArrayList<>();
            while (m.find()) eols.add(m.group());
            String dominant = eols.stream().findFirst().orElse("\n");
            boolean noEol = t.isEmpty() || (!t.endsWith("\n") && !t.endsWith("\r"));
            return new EolInfo(dominant, eols, noEol);
        }
    }
    private record EolInfo(String eol, List<String> originalEols, boolean lastLineNoEol) {}

    private static List<String> toPath(String slashPath) {
        if (slashPath.startsWith("/")) slashPath = slashPath.substring(1);
        return Arrays.asList(slashPath.split("/"));
    }
} 