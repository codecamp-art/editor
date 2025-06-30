package org.example;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-place editor for Java .properties files with full formatting preservation.
 * <p>Features:
 * <ul>
 *   <li>Replace the value of a given key (first occurrence) while leaving every unrelated byte intact.</li>
 *   <li>Preserves indentation, spaces, comments (#/!), mixed EOLs, encoding, and any existing BOM.</li>
 *   <li>Supports both {@link File} and {@link InputStream} sources and explicit encoding override.</li>
 *   <li>Thread-safe: stateless with only static methods and no shared mutable state.</li>
 * </ul>
 */
public final class PropertiesInPlaceEditor {
    private PropertiesInPlaceEditor() {
    }

    /* --- Public API ---------------------------------------------------- */

    /**
     * Edit the value of a property key in a .properties file in-place.
     * <p>Preserves all formatting, comments, indentation, EOLs, encoding, and BOM. Thread-safe and stateless.
     * @param file Properties file to edit
     * @param key Property key to replace
     * @param newValue Replacement value
     * @throws IOException on I/O error
     */
    public static void editProperty(File file, String key, String newValue) throws IOException {
        Objects.requireNonNull(file, "file");
        byte[] original = Files.readAllBytes(file.toPath());
        FileInfo info = FileInfo.detect(original);
        byte[] modified = editInternal(original, info, key, newValue);
        Files.write(file.toPath(), modified);
    }

    /**
     * Edit the value of a property key from an InputStream, returning the modified bytes.
     * <p>Preserves all formatting, comments, indentation, EOLs, encoding, and BOM. Thread-safe and stateless.
     * @param in InputStream containing properties data
     * @param key Property key to replace
     * @param newValue Replacement value
     * @return Modified properties content as bytes
     * @throws IOException on I/O error
     */
    public static byte[] editProperty(InputStream in, String key, String newValue) throws IOException {
        byte[] original = in.readAllBytes();
        FileInfo info = FileInfo.detect(original);
        return editInternal(original, info, key, newValue);
    }

    /**
     * Edit the value of a property key in a file with explicit encoding (e.g. "GBK").
     * <p>Preserves all formatting, comments, indentation, EOLs, and BOM. Thread-safe and stateless.
     * @param file Properties file to edit
     * @param encoding Character encoding (e.g. "GBK")
     * @param key Property key to replace
     * @param newValue Replacement value
     * @throws IOException on I/O error
     */
    public static void editPropertyWithEncoding(File file, String encoding, String key, String newValue) throws IOException {
        byte[] original = Files.readAllBytes(file.toPath());
        FileInfo base = FileInfo.detect(original);
        Charset cs = Charset.forName(encoding);
        FileInfo overridden = new FileInfo(cs, base.eol, base.originalEols, base.lastLineNoEol, base.bom, base.offset);
        byte[] modified = editInternal(original, overridden, key, newValue);
        Files.write(file.toPath(), modified);
    }

    /**
     * Unified API to replace value (or clear when newValue==null). If encodingHint is null, auto-detect encoding else override.
     */
    public static void setValue(File file, String key, String expectedOldValue, String newValue, String encodingHint) throws IOException {
        Objects.requireNonNull(file, "file");
        byte[] original = Files.readAllBytes(file.toPath());
        FileInfo base = FileInfo.detect(original);
        Charset cs = encodingHint == null ? base.charset : Charset.forName(encodingHint);
        FileInfo info = encodingHint == null ? base : new FileInfo(cs, base.eol, base.originalEols, base.lastLineNoEol, base.bom, base.offset);
        byte[] modified = (newValue == null)
                ? clearInternal(original, info, key, expectedOldValue)
                : replaceInternal(original, info, key, expectedOldValue, newValue);
        Files.write(file.toPath(), modified);
    }

    /**
     * Unified InputStream variant. Returns modified bytes.
     */
    public static byte[] setValue(InputStream in, String key, String expectedOldValue, String newValue, String encodingHint) throws IOException {
        byte[] original = in.readAllBytes();
        FileInfo base = FileInfo.detect(original);
        Charset cs = encodingHint == null ? base.charset : Charset.forName(encodingHint);
        FileInfo info = encodingHint == null ? base : new FileInfo(cs, base.eol, base.originalEols, base.lastLineNoEol, base.bom, base.offset);
        return (newValue == null)
                ? clearInternal(original, info, key, expectedOldValue)
                : replaceInternal(original, info, key, expectedOldValue, newValue);
    }

    public static void removeLine(File file, String key, String expectedOldValue) throws IOException {
        byte[] original = Files.readAllBytes(file.toPath());
        FileInfo info = FileInfo.detect(original);
        byte[] modified = removeLineInternal(original, info, key, expectedOldValue);
        Files.write(file.toPath(), modified);
    }

    public static byte[] removeLine(InputStream in, String key, String expectedOldValue) throws IOException {
        byte[] original = in.readAllBytes();
        FileInfo info = FileInfo.detect(original);
        return removeLineInternal(original, info, key, expectedOldValue);
    }

    public static void removeLineWithEncoding(File file, String encoding, String key, String expectedOldValue) throws IOException {
        byte[] original = Files.readAllBytes(file.toPath());
        FileInfo base = FileInfo.detect(original);
        Charset cs = Charset.forName(encoding);
        FileInfo overridden = new FileInfo(cs, base.eol, base.originalEols, base.lastLineNoEol, base.bom, base.offset);
        byte[] modified = removeLineInternal(original, overridden, key, expectedOldValue);
        Files.write(file.toPath(), modified);
    }

    /* --- Internals ----------------------------------------------------- */

    private static byte[] editInternal(byte[] originalBytes, FileInfo info, String targetKey, String newValue) {
        String content = new String(originalBytes, info.offset, originalBytes.length - info.offset, info.charset);

        // Split into lines while preserving EOL tokens.
        List<String> lines = new ArrayList<>();
        Matcher m = Pattern.compile("(.*?(?:\r\n|\r|\n|$))", Pattern.DOTALL).matcher(content);
        while (m.find()) {
            if (m.group(1).isEmpty()) break; // final empty match
            lines.add(m.group(1));
        }

        // Regex to match a properties key-value line (without line continuations).
        Pattern kvPattern = Pattern.compile("^(\\s*)([^:=\\s\\\\]+)(\\s*[=:]\\s*)(.*)$");

        for (int i = 0; i < lines.size(); i++) {
            String fullLine = lines.get(i);
            // Separate logical line and EOL.
            String logical = fullLine.replaceAll("(\r\n|\r|\n)$", "");
            String eol = fullLine.substring(logical.length());

            String trimmed = logical.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue; // comment/blank
            }

            Matcher kv = kvPattern.matcher(logical);
            if (kv.find()) {
                String key = kv.group(2).trim();
                if (key.equals(targetKey)) {
                    String indent = kv.group(1);
                    String separator = kv.group(3);
                    String valueAndRest = kv.group(4); // includes any spaces after value and inline comment

                    // Preserve inline comment, if present (# or !) not inside value.
                    int commentIdx = findInlineComment(valueAndRest);
                    String valuePart;
                    String commentPart = "";
                    if (commentIdx >= 0) {
                        valuePart = valueAndRest.substring(0, commentIdx);
                        commentPart = valueAndRest.substring(commentIdx);
                    } else {
                        valuePart = valueAndRest;
                    }

                    // Preserve trailing spaces before comment.
                    int trailingSpaces = countTrailingSpaces(valuePart);
                    String spaces = " ".repeat(trailingSpaces);

                    String newLogical = indent + key + separator + newValue + spaces + commentPart;
                    lines.set(i, newLogical + eol);
                    return assemble(lines, info);
                }
            }
        }
        throw new IllegalArgumentException("Key not found: " + targetKey);
    }

    private static int findInlineComment(String s) {
        boolean inEscape = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && !inEscape) {
                inEscape = true;
                continue;
            }
            inEscape = false;
            if ((c == '#' || c == '!') && (i == 0 || Character.isWhitespace(s.charAt(i - 1)))) {
                return i;
            }
        }
        return -1;
    }

    private static int countTrailingSpaces(String s) {
        int cnt = 0;
        for (int i = s.length() - 1; i >= 0 && Character.isWhitespace(s.charAt(i)); i--) cnt++;
        return cnt;
    }

    private static byte[] assemble(List<String> lines, FileInfo info) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l);
        byte[] body = sb.toString().getBytes(info.charset);
        if (info.bom != null && info.bom.length > 0) {
            byte[] out = new byte[info.bom.length + body.length];
            System.arraycopy(info.bom, 0, out, 0, info.bom.length);
            System.arraycopy(body, 0, out, info.bom.length, body.length);
            return out;
        }
        return body;
    }

    // ---------------------------------------------------------------------
    // Internal helper implementations (previously removed by mistake)
    // ---------------------------------------------------------------------
    private static byte[] replaceInternal(byte[] originalBytes, FileInfo info, String targetKey, String expectedOldValue, String newValue) {
        return mutateLinesMulti(originalBytes, info, targetKey, expectedOldValue, newValue, false);
    }

    private static byte[] clearInternal(byte[] originalBytes, FileInfo info, String targetKey, String expectedOldValue) {
        return mutateLinesMulti(originalBytes, info, targetKey, expectedOldValue, "", false);
    }

    private static byte[] removeLineInternal(byte[] originalBytes, FileInfo info, String targetKey, String expectedOldValue) {
        return mutateLinesMulti(originalBytes, info, targetKey, expectedOldValue, "", true);
    }

    private static byte[] mutateLinesMulti(byte[] originalBytes, FileInfo info, String targetKey, String expectedOldValue, String newValue, boolean removeLine) {
        String content = new String(originalBytes, info.offset, originalBytes.length - info.offset, info.charset);
        List<String> lines = splitLines(content);

        Pattern kvPattern = Pattern.compile("^(\\s*)([^:=\\s\\\\]+)(\\s*[=:]\\s*)(.*)$");

        for (int i = 0; i < lines.size(); i++) {
            String fullLine = lines.get(i);
            String logical = stripEol(fullLine);
            String eol = fullLine.substring(logical.length());

            String trimmed = logical.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) continue;

            Matcher kv = kvPattern.matcher(logical);
            if (!kv.find()) continue;

            String key = kv.group(2).trim();
            if (!key.equals(targetKey)) continue;

            String indent = kv.group(1);
            String separator = kv.group(3);
            String valueAndRest = kv.group(4);

            int commentIdx = findInlineComment(valueAndRest);
            String valuePart = commentIdx >= 0 ? valueAndRest.substring(0, commentIdx) : valueAndRest;
            String commentPart = commentIdx >= 0 ? valueAndRest.substring(commentIdx) : "";

            int trailingSpaces = countTrailingSpaces(valuePart);
            String spaces = " ".repeat(trailingSpaces);

            // Determine continuation block extent
            int endIdx = i;
            while (endIdx < lines.size()) {
                String curLogical = stripEol(lines.get(endIdx));
                if (endsWithContinuation(curLogical)) {
                    endIdx++;
                } else {
                    break;
                }
            }

            // Aggregate existing value for expectedOldValue comparison (optional)
            if (expectedOldValue != null) {
                StringBuilder agg = new StringBuilder();
                // first value fragment (strip possible trailing backslash and spaces)
                agg.append(valuePart.replaceAll("\\\\\\s*$", ""));
                for (int j = i + 1; j <= endIdx; j++) {
                    String l = stripEol(lines.get(j));
                    String segment = l.trim();
                    segment = segment.replaceAll("\\\\\\s*$", ""); // remove trailing backslash and spaces
                    agg.append(segment);
                }
                if (!agg.toString().trim().equals(expectedOldValue)) continue; // mismatch
            }

            if (removeLine) { // delete whole block
                lines.subList(i, endIdx + 1).clear();
                return assemble(lines, info);
            }

            // Build replacement lines
            List<String> newLines = new ArrayList<>();

            String indentContinuation;
            if (endIdx > i) {
                indentContinuation = leadingWhitespace(stripEol(lines.get(i + 1)));
            } else {
                indentContinuation = indent.isEmpty() ? " " : indent;
            }

            if (newValue.contains("\n")) {
                String[] parts = newValue.split("\\n", -1);
                // first line
                String firstLineLogical = indent + key + separator + parts[0] + " \\" + spaces + commentPart;
                newLines.add(firstLineLogical + eol);

                for (int idx = 1; idx < parts.length; idx++) {
                    boolean last = idx == parts.length - 1;
                    String cont = indentContinuation + parts[idx] + (last ? "" : " \\") ;
                    newLines.add(cont + eol);
                }
            } else {
                // single-line value; collapse any previous continuation
                String singleLogical = indent + key + separator + newValue + spaces + commentPart;
                newLines.add(singleLogical + eol);
            }

            // Replace in list
            lines.subList(i, endIdx + 1).clear();
            lines.addAll(i, newLines);

            return assemble(lines, info);
        }
        throw new IllegalArgumentException("Key not found (or value mismatch): " + targetKey);
    }

    private static List<String> splitLines(String content) {
        List<String> lines = new ArrayList<>();
        Matcher m = Pattern.compile("(.*?(?:\r\n|\r|\n|$))", Pattern.DOTALL).matcher(content);
        while (m.find()) {
            if (m.group(1).isEmpty()) break;
            lines.add(m.group(1));
        }
        return lines;
    }

    private static String stripEol(String fullLine) {
        return fullLine.replaceAll("(\r\n|\r|\n)$", "");
    }

    private static boolean endsWithContinuation(String logical) {
        int idx = logical.length() - 1;
        // Skip trailing whitespace
        while (idx >= 0 && Character.isWhitespace(logical.charAt(idx))) idx--;
        if (idx < 0 || logical.charAt(idx) != '\\') return false;
        // Count consecutive backslashes backwards
        int bs = 0;
        while (idx >= 0 && logical.charAt(idx) == '\\') { bs++; idx--; }
        return bs % 2 == 1; // odd means the last backslash is not escaped
    }

    private static String leadingWhitespace(String s) {
        int i = 0; while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; return s.substring(0, i);
    }

    /* --- Encoding & EOL detection utilities (copied) -------------------- */
    private static final class FileInfo {
        final Charset charset;
        final String eol;
        final List<String> originalEols;
        final boolean lastLineNoEol;
        final byte[] bom;
        final int offset;
        FileInfo(Charset charset, String eol, List<String> originalEols, boolean lastLineNoEol, byte[] bom, int offset) {
            this.charset = charset; this.eol = eol; this.originalEols = originalEols; this.lastLineNoEol = lastLineNoEol; this.bom = bom; this.offset = offset; }
        static FileInfo detect(byte[] content) {
            var enc = EncodingUtil.detectEncoding(content);
            var eol = EolUtil.detectLineEndings(content, enc.charset);
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
            try { new String(content, StandardCharsets.UTF_8); return new EncodingInfo(StandardCharsets.UTF_8, new byte[0],0);} catch(Exception ignored){}
            return new EncodingInfo(Charset.forName("GBK"), new byte[0],0);
        }
        private static boolean startsWith(byte[] content, byte[] prefix){ if(content.length< prefix.length) return false; for(int i=0;i<prefix.length;i++){ if(content[i]!=prefix[i]) return false;} return true; }
    }
    private record EncodingInfo(Charset charset, byte[] bom, int offset){}

    private static final class EolUtil {
        private static final Pattern P = Pattern.compile("(\r\n|\r|\n)");
        static EolInfo detectLineEndings(byte[] content, Charset cs){ String t=new String(content,cs); Matcher m=P.matcher(t); List<String> e=new ArrayList<>(); while(m.find()){e.add(m.group());} String d=e.stream().reduce((a,b)->a.length()>=b.length()?a:b).orElse("\n"); boolean no=!t.endsWith("\n")&&!t.endsWith("\r"); return new EolInfo(d,e,no);} }
    private record EolInfo(String eol,List<String> originalEols,boolean lastLineNoEol){}
} 