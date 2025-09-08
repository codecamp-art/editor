package org.example;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-place editor for plain text files. Preserves all formatting, EOLs, encoding, BOM, etc.
 */
public final class TextInPlaceEditor {
    private TextInPlaceEditor() {}

    // --- Public API ---

    /**
     * Replace the first occurrence of a pattern (regex or literal) in a file, in-place.
     * <p>Preserves all formatting, EOLs, encoding, and BOM. Thread-safe and stateless.
     * @param file Text file to edit
     * @param pattern Regex or literal pattern to match
     * @param replacement Replacement string
     * @param isRegex If true, pattern is regex; if false, literal
     * @throws IOException on I/O error
     */
    public static void replace(File file, String pattern, String replacement, boolean isRegex) throws IOException {
        Objects.requireNonNull(file, "file");
        byte[] original = Files.readAllBytes(file.toPath());
        FileInfo info = FileInfo.detect(original);
        Pattern compiled = isRegex ? Pattern.compile(pattern) : Pattern.compile(Pattern.quote(pattern));
        byte[] modified = replaceInternal(original, info, compiled, replacement, true);
        Files.write(file.toPath(), modified);
    }

    /**
     * Replace the first occurrence of a pattern (regex or literal) in an InputStream. Returns the modified bytes.
     * <p>Preserves all formatting, EOLs, encoding, and BOM. Thread-safe and stateless.
     * @param in InputStream containing text data
     * @param pattern Regex or literal pattern to match
     * @param replacement Replacement string
     * @param isRegex If true, pattern is regex; if false, literal
     * @return Modified content as bytes
     * @throws IOException on I/O error
     */
    public static byte[] replace(InputStream in, String pattern, String replacement, boolean isRegex) throws IOException {
        byte[] original = in.readAllBytes();
        FileInfo info = FileInfo.detect(original);
        Pattern compiled = isRegex ? Pattern.compile(pattern) : Pattern.compile(Pattern.quote(pattern));
        return replaceInternal(original, info, compiled, replacement, true);
    }

    /**
     * Replace the first occurrence of a pattern (regex or literal) in an InputStream. Returns the modified bytes.
     * <p>Preserves all formatting, EOLs, encoding, and BOM. Thread-safe and stateless.
     * @param pattern Regex or literal pattern to match
     * @param replacement Replacement string
     * @param isRegex If true, pattern is regex; if false, literal
     * @return Modified content as bytes
     * @throws IOException on I/O error
     */
    public static byte[] replace(byte[] data, String pattern, String replacement, boolean isRegex, String encoding) throws IOException {
        FileInfo info = FileInfo.detect(data);
        if (isValidEncoding(encoding)) {
            info = new FileInfo(Charset.forName(encoding), info.eol, info.originalEols, info.lastLineNoEol, info.bom, info.offset);
        }
        Pattern compiled = isRegex ? Pattern.compile(pattern) : Pattern.compile(Pattern.quote(pattern));
        return replaceInternal(data, info, compiled, replacement, true);
    }

    /**
     * Replace the first occurrence of a pattern (regex or literal) in a file, with explicit encoding.
     * <p>Preserves all formatting, EOLs, encoding, and BOM. Thread-safe and stateless.
     * @param file Text file to edit
     * @param encoding Character encoding (e.g. "GBK")
     * @param pattern Regex or literal pattern to match
     * @param replacement Replacement string
     * @param isRegex If true, pattern is regex; if false, literal
     * @throws IOException on I/O error
     */
    public static void replaceWithEncoding(File file, String encoding, String pattern, String replacement, boolean isRegex) throws IOException {
        byte[] original = Files.readAllBytes(file.toPath());
        FileInfo base = FileInfo.detect(original);
        Charset cs = Charset.forName(encoding);
        FileInfo overridden = new FileInfo(cs, base.eol, base.originalEols, base.lastLineNoEol, base.bom, base.offset);
        Pattern compiled = isRegex ? Pattern.compile(pattern) : Pattern.compile(Pattern.quote(pattern));
        byte[] modified = replaceInternal(original, overridden, compiled, replacement, true);
        Files.write(file.toPath(), modified);
    }

    /**
     * Remove (delete) the first line that matches the given pattern in a file, in-place.
     * <p>Preserves all formatting, EOLs, encoding, and BOM. Thread-safe and stateless.
     * @param file Text file to edit
     * @param pattern Regex or literal pattern to match
     * @param isRegex If true, pattern is regex; if false, literal
     * @throws IOException on I/O error
     */
    public static void removeLine(File file, String pattern, boolean isRegex) throws IOException {
        Objects.requireNonNull(file, "file");
        byte[] original = Files.readAllBytes(file.toPath());
        FileInfo info = FileInfo.detect(original);
        Pattern compiled = isRegex ? Pattern.compile(pattern) : Pattern.compile(Pattern.quote(pattern));
        byte[] modified = removeLineInternal(original, info, compiled);
        Files.write(file.toPath(), modified);
    }

    /**
     * Remove (delete) the first line that matches the given pattern in an InputStream. Returns the modified bytes.
     * <p>Preserves all formatting, EOLs, encoding, and BOM.</p>
     */
    public static byte[] removeLine(InputStream in, String pattern, boolean isRegex) throws IOException {
        byte[] original = in.readAllBytes();
        FileInfo info = FileInfo.detect(original);
        Pattern compiled = isRegex ? Pattern.compile(pattern) : Pattern.compile(Pattern.quote(pattern));
        return removeLineInternal(original, info, compiled);
    }

    /**
     * Remove (delete) the first line that matches the given pattern in an InputStream. Returns the modified bytes.
     * <p>Preserves all formatting, EOLs, encoding, and BOM.</p>
     */
    public static byte[] removeLine(byte[] data, String pattern, boolean isRegex, String encoding) throws IOException {
        FileInfo info = FileInfo.detect(data);
        if (isValidEncoding(encoding)) {
            info = new FileInfo(Charset.forName(encoding), info.eol, info.originalEols, info.lastLineNoEol, info.bom, info.offset);
        }
        Pattern compiled = isRegex ? Pattern.compile(pattern) : Pattern.compile(Pattern.quote(pattern));
        return removeLineInternal(data, info, compiled);
    }

    /**
     * Remove (delete) the first line that matches the given pattern in a file with explicit encoding.
     */
    public static void removeLineWithEncoding(File file, String encoding, String pattern, boolean isRegex) throws IOException {
        byte[] original = Files.readAllBytes(file.toPath());
        FileInfo base = FileInfo.detect(original);
        Charset cs = Charset.forName(encoding);
        FileInfo overridden = new FileInfo(cs, base.eol, base.originalEols, base.lastLineNoEol, base.bom, base.offset);
        Pattern compiled = isRegex ? Pattern.compile(pattern) : Pattern.compile(Pattern.quote(pattern));
        byte[] modified = removeLineInternal(original, overridden, compiled);
        Files.write(file.toPath(), modified);
    }

    // --- Internals ---

    private static byte[] replaceInternal(byte[] originalBytes, FileInfo info, Pattern pattern, String replacement, boolean firstOnly) {
        String content = new String(originalBytes, info.offset, originalBytes.length - info.offset, info.charset);
        Matcher m = pattern.matcher(content);
        StringBuffer sb = new StringBuffer(content.length());
        boolean found = false;
        while (m.find()) {
            if (!found) {
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                found = true;
                if (firstOnly) break;
            }
        }
        if (found) {
            m.appendTail(sb);
        } else {
            return originalBytes; // No change
        }
        byte[] out = sb.toString().getBytes(info.charset);
        if (info.bom != null && info.bom.length > 0) {
            byte[] withBom = new byte[info.bom.length + out.length];
            System.arraycopy(info.bom, 0, withBom, 0, info.bom.length);
            System.arraycopy(out, 0, withBom, info.bom.length, out.length);
            return withBom;
        }
        return out;
    }

    private static byte[] removeLineInternal(byte[] originalBytes, FileInfo info, Pattern pattern) {
        String content = new String(originalBytes, info.offset, originalBytes.length - info.offset, info.charset);
        Matcher m = pattern.matcher(content);
        if (!m.find()) {
            return originalBytes; // No match, return original
        }
        int matchStart = m.start();
        int matchEnd = m.end();
        // Determine line start
        int lineStart = matchStart;
        while (lineStart > 0) {
            char c = content.charAt(lineStart - 1);
            if (c == '\n' || c == '\r') {
                // Handle CRLF (\r\n). If current char is \n and previous char is \r, move one more back.
                if (c == '\n' && lineStart - 2 >= 0 && content.charAt(lineStart - 2) == '\r') {
                    lineStart -= 1; // move to LF
                    lineStart -= 1; // move to CR to include both chars
                }
                break;
            }
            lineStart--;
        }
        // Determine line end (include EOL chars)
        int lineEnd = matchEnd;
        while (lineEnd < content.length()) {
            char c = content.charAt(lineEnd);
            if (c == '\n' || c == '\r') {
                lineEnd++;
                if (c == '\r' && lineEnd < content.length() && content.charAt(lineEnd) == '\n') {
                    lineEnd++; // include LF of CRLF
                }
                break;
            }
            lineEnd++;
        }
        // Build new content
        String newContent = content.substring(0, lineStart) + content.substring(lineEnd);
        byte[] out = newContent.getBytes(info.charset);
        if (info.bom != null && info.bom.length > 0) {
            byte[] withBom = new byte[info.bom.length + out.length];
            System.arraycopy(info.bom, 0, withBom, 0, info.bom.length);
            System.arraycopy(out, 0, withBom, info.bom.length, out.length);
            return withBom;
        }
        return out;
    }

    private static boolean isValidEncoding(String encoding) {
        if (encoding == null) return false;
        try {
            Charset.forName(encoding);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // --- Encoding & EOL detection (copied from IniInPlaceEditor) ---
    private static final class FileInfo {
        final Charset charset;
        final String eol;
        final List<String> originalEols;
        final boolean lastLineNoEol;
        final byte[] bom;
        final int offset;
        FileInfo(Charset charset, String eol, List<String> originalEols, boolean lastLineNoEol, byte[] bom, int offset) {
            this.charset = charset;
            this.eol = eol;
            this.originalEols = originalEols;
            this.lastLineNoEol = lastLineNoEol;
            this.bom = bom;
            this.offset = offset;
        }
        static FileInfo detect(byte[] content) {
            var encodingInfo = EncodingUtil.detectEncoding(content);
            var eolInfo = EolUtil.detectLineEndings(content, encodingInfo.charset);
            return new FileInfo(encodingInfo.charset, eolInfo.eol, eolInfo.originalEols, eolInfo.lastLineNoEol, encodingInfo.bom, encodingInfo.offset);
        }
    }
    private static final class EncodingUtil {
        private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        private static final byte[] UTF16BE_BOM = {(byte) 0xFE, (byte) 0xFF};
        private static final byte[] UTF16LE_BOM = {(byte) 0xFF, (byte) 0xFE};
        static EncodingInfo detectEncoding(byte[] content) {
            if (startsWith(content, UTF8_BOM)) {
                return new EncodingInfo(StandardCharsets.UTF_8, UTF8_BOM, UTF8_BOM.length);
            }
            if (startsWith(content, UTF16BE_BOM)) {
                return new EncodingInfo(StandardCharsets.UTF_16BE, UTF16BE_BOM, UTF16BE_BOM.length);
            }
            if (startsWith(content, UTF16LE_BOM)) {
                return new EncodingInfo(StandardCharsets.UTF_16LE, UTF16LE_BOM, UTF16LE_BOM.length);
            }
            // Fallback: Try UTF-8, else GBK
            try {
                new String(content, StandardCharsets.UTF_8);
                return new EncodingInfo(StandardCharsets.UTF_8, new byte[0], 0);
            } catch (Exception ignored) {}
            return new EncodingInfo(Charset.forName("GBK"), new byte[0], 0);
        }
        private static boolean startsWith(byte[] content, byte[] prefix) {
            if (content.length < prefix.length) return false;
            for (int i = 0; i < prefix.length; i++) {
                if (content[i] != prefix[i]) return false;
            }
            return true;
        }
    }
    private record EncodingInfo(Charset charset, byte[] bom, int offset) {}
    private static final class EolUtil {
        private static final Pattern EOL_PATTERN = Pattern.compile("(\r\n|\r|\n)");
        static EolInfo detectLineEndings(byte[] content, Charset charset) {
            String text = new String(content, charset);
            Matcher matcher = EOL_PATTERN.matcher(text);
            List<String> eols = new ArrayList<>();
            while (matcher.find()) {
                eols.add(matcher.group());
            }
            String dominant = eols.stream().reduce((a, b) -> a.length() >= b.length() ? a : b).orElse("\n");
            boolean lastLineNoEol = !text.endsWith("\n") && !text.endsWith("\r");
            return new EolInfo(dominant, eols, lastLineNoEol);
        }
    }
    private record EolInfo(String eol, List<String> originalEols, boolean lastLineNoEol) {}
} 