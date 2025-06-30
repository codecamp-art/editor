package org.example;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-place YAML editor with comprehensive formatting preservation.
 * <p>
 * Features:
 * 1. Replace a scalar value specified by a slash-separated path (e.g. "root/child/key").
 * 2. Conditional replacement by matching both path and current value.
 * 3. Remove value (clear but keep key) or delete entire line.
 * 4. Preserves every byte not related to the updated value: indentation, spaces, line endings (mixed CR/LF/CRLF),
 *    comments, quoting style, block scalars, etc.
 * 5. Supports GBK, UTF-8, UTF-16 and other encodings (auto-detect) and preserves any existing BOM.
 * 6. Provides convenience overloads for editing via {@link File} as well as arbitrary {@link InputStream}s.
 */
public final class YamlInPlaceEditor {

    /* Precompiled regex patterns reused across calls for performance */
    private static final Pattern MAP_KV_PATTERN     = Pattern.compile("^(\\s*)([^:]+?):(\\s*)(.*)$");
    private static final Pattern SEQ_KV_PATTERN     = Pattern.compile("^(\\s*)-\\s+([^:]+?):(\\s*)(.*)$");
    private static final Pattern SEQ_SCALAR_PATTERN = Pattern.compile("^(\\s*)-\\s+(.*)$");
    private static final Pattern LINE_SPLIT_PATTERN = Pattern.compile("(.*?(?:\\r\\n|\\r|\\n|$))", Pattern.DOTALL);

    /** Types of in-place edit supported */
    private enum Op { REPLACE, CLEAR_VALUE, DELETE_LINE }

    /* =====================================================
     * Public API - Unified methods
     * ===================================================== */

    /**
     * Set a value in YAML file in-place. If newValue is null or empty, clears the value but keeps the key.
     * <p>Preserves all formatting, comments, indentation, EOLs, encoding, and BOM. Thread-safe and stateless.
     * @param file YAML file to update
     * @param yamlPath Slash-separated path (e.g. "root/child/key")
     * @param newValue New scalar value (null or empty to clear value)
     * @throws IOException on I/O error
     */
    public static void setValue(File file, String yamlPath, String newValue) throws IOException {
        setValue(file, yamlPath, null, newValue, null);
    }

    /**
     * Set a value in YAML file in-place, only if current value matches expected value.
     * If newValue is null or empty, clears the value but keeps the key.
     * <p>Preserves all formatting, comments, indentation, EOLs, encoding, and BOM. Thread-safe and stateless.
     * @param file YAML file to update
     * @param yamlPath Slash-separated path (e.g. "root/child/key")
     * @param expectedOldValue Current value that must match (null for unconditional)
     * @param newValue New scalar value (null or empty to clear value)
     * @throws IOException on I/O error
     */
    public static void setValue(File file, String yamlPath, String expectedOldValue, String newValue) throws IOException {
        setValue(file, yamlPath, expectedOldValue, newValue, null);
    }

    /**
     * Set a value in YAML file in-place with encoding hint, only if current value matches expected value.
     * If newValue is null or empty, clears the value but keeps the key.
     * <p>Preserves all formatting, comments, indentation, EOLs, and BOM. Thread-safe and stateless.
     * @param file YAML file to update
     * @param yamlPath Slash-separated path (e.g. "root/child/key")
     * @param expectedOldValue Current value that must match (null for unconditional)
     * @param newValue New scalar value (null or empty to clear value)
     * @param encodingHint Character encoding hint (e.g. "GBK", "UTF-8")
     * @throws IOException on I/O error
     */
    public static void setValue(File file, String yamlPath, String expectedOldValue, String newValue, String encodingHint) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        Objects.requireNonNull(yamlPath, "yamlPath must not be null");

        byte[] originalBytes = Files.readAllBytes(file.toPath());
        FileInfo fileInfo = getFileInfo(originalBytes, encodingHint);

        Op op = (newValue == null || newValue.isEmpty()) ? Op.CLEAR_VALUE : Op.REPLACE;
        String valueToSet = (newValue == null) ? "" : newValue;
        
        byte[] modified = editYamlInternal(originalBytes, fileInfo, toPathList(yamlPath), valueToSet, expectedOldValue, op);
        Files.write(file.toPath(), modified);
    }

    /**
     * Set a value in YAML content from InputStream. If newValue is null or empty, clears the value but keeps the key.
     * <p>Preserves all formatting, comments, indentation, EOLs, encoding, and BOM. Thread-safe and stateless.
     * @param inputStream InputStream containing YAML data
     * @param yamlPath Slash-separated path (e.g. "root/child/key")
     * @param newValue New scalar value (null or empty to clear value)
     * @return Modified YAML content as bytes
     * @throws IOException on I/O error
     */
    public static byte[] setValue(InputStream inputStream, String yamlPath, String newValue) throws IOException {
        return setValue(inputStream, yamlPath, null, newValue, null);
    }

    /**
     * Set a value in YAML content from InputStream, only if current value matches expected value.
     * If newValue is null or empty, clears the value but keeps the key.
     * <p>Preserves all formatting, comments, indentation, EOLs, encoding, and BOM. Thread-safe and stateless.
     * @param inputStream InputStream containing YAML data
     * @param yamlPath Slash-separated path (e.g. "root/child/key")
     * @param expectedOldValue Current value that must match (null for unconditional)
     * @param newValue New scalar value (null or empty to clear value)
     * @return Modified YAML content as bytes
     * @throws IOException on I/O error
     */
    public static byte[] setValue(InputStream inputStream, String yamlPath, String expectedOldValue, String newValue) throws IOException {
        return setValue(inputStream, yamlPath, expectedOldValue, newValue, null);
    }

    /**
     * Set a value in YAML content from InputStream with encoding hint, only if current value matches expected value.
     * If newValue is null or empty, clears the value but keeps the key.
     * <p>Preserves all formatting, comments, indentation, EOLs, and BOM. Thread-safe and stateless.
     * @param inputStream InputStream containing YAML data
     * @param yamlPath Slash-separated path (e.g. "root/child/key")
     * @param expectedOldValue Current value that must match (null for unconditional)
     * @param newValue New scalar value (null or empty to clear value)
     * @param encodingHint Character encoding hint (e.g. "GBK", "UTF-8")
     * @return Modified YAML content as bytes
     * @throws IOException on I/O error
     */
    public static byte[] setValue(InputStream inputStream, String yamlPath, String expectedOldValue, String newValue, String encodingHint) throws IOException {
        byte[] originalBytes = inputStream.readAllBytes();
        FileInfo fileInfo = getFileInfo(originalBytes, encodingHint);

        Op op = (newValue == null || newValue.isEmpty()) ? Op.CLEAR_VALUE : Op.REPLACE;
        String valueToSet = (newValue == null) ? "" : newValue;
        
        return editYamlInternal(originalBytes, fileInfo, toPathList(yamlPath), valueToSet, expectedOldValue, op);
    }

    /**
     * Delete the entire line containing the key-value pair.
     * @param file YAML file to update
     * @param yamlPath Slash-separated path
     * @throws IOException on I/O error
     */
    public static void deleteLine(File file, String yamlPath) throws IOException {
        deleteLine(file, yamlPath, null, null);
    }

    /**
     * Delete the entire line only if current value matches expected value.
     * @param file YAML file to update
     * @param yamlPath Slash-separated path
     * @param expectedOldValue Current value that must match
     * @throws IOException on I/O error
     */
    public static void deleteLine(File file, String yamlPath, String expectedOldValue) throws IOException {
        deleteLine(file, yamlPath, expectedOldValue, null);
    }

    /**
     * Delete the entire line with encoding hint, only if current value matches expected value.
     * @param file YAML file to update
     * @param yamlPath Slash-separated path
     * @param expectedOldValue Current value that must match
     * @param encodingHint Character encoding hint (e.g. "GBK", "UTF-8")
     * @throws IOException on I/O error
     */
    public static void deleteLine(File file, String yamlPath, String expectedOldValue, String encodingHint) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        Objects.requireNonNull(yamlPath, "yamlPath must not be null");

        byte[] originalBytes = Files.readAllBytes(file.toPath());
        FileInfo fileInfo = getFileInfo(originalBytes, encodingHint);

        byte[] modified = editYamlInternal(originalBytes, fileInfo, toPathList(yamlPath), null, expectedOldValue, Op.DELETE_LINE);
        Files.write(file.toPath(), modified);
    }

    /**
     * Delete the entire line in InputStream content.
     * @param inputStream InputStream containing YAML data
     * @param yamlPath Slash-separated path
     * @return Modified YAML content as bytes
     * @throws IOException on I/O error
     */
    public static byte[] deleteLine(InputStream inputStream, String yamlPath) throws IOException {
        return deleteLine(inputStream, yamlPath, null, null);
    }

    /**
     * Delete the entire line in InputStream content, only if current value matches expected value.
     * @param inputStream InputStream containing YAML data
     * @param yamlPath Slash-separated path
     * @param expectedOldValue Current value that must match
     * @return Modified YAML content as bytes
     * @throws IOException on I/O error
     */
    public static byte[] deleteLine(InputStream inputStream, String yamlPath, String expectedOldValue) throws IOException {
        return deleteLine(inputStream, yamlPath, expectedOldValue, null);
    }

    /**
     * Delete the entire line in InputStream content with encoding hint, only if current value matches expected value.
     * @param inputStream InputStream containing YAML data
     * @param yamlPath Slash-separated path
     * @param expectedOldValue Current value that must match
     * @param encodingHint Character encoding hint (e.g. "GBK", "UTF-8")
     * @return Modified YAML content as bytes
     * @throws IOException on I/O error
     */
    public static byte[] deleteLine(InputStream inputStream, String yamlPath, String expectedOldValue, String encodingHint) throws IOException {
        byte[] originalBytes = inputStream.readAllBytes();
        FileInfo fileInfo = getFileInfo(originalBytes, encodingHint);
        return editYamlInternal(originalBytes, fileInfo, toPathList(yamlPath), null, expectedOldValue, Op.DELETE_LINE);
    }

    /* =====================================================
     * Internals
     * ===================================================== */

    private static FileInfo getFileInfo(byte[] content, String encodingHint) {
        if (encodingHint != null) {
            Charset charset = Charset.forName(encodingHint);
            var encodingInfo = EncodingUtil.detectEncoding(content);
            var eolInfo = EolUtil.detectLineEndings(content, charset); // Use hinted charset for EOL detection
            return new FileInfo(charset, eolInfo.eol(), eolInfo.originalEols(), eolInfo.lastLineNoEol(), encodingInfo.bom(), encodingInfo.offset());
        }
        return FileInfo.detect(content);
    }

    private static List<String> toPathList(String slashPath) {
        if (slashPath.startsWith("/")) slashPath = slashPath.substring(1);
        String[] parts = slashPath.split("/");
        return Arrays.asList(parts);
    }

    private static byte[] editYamlInternal(byte[] originalBytes, FileInfo fileInfo, List<String> path, String newValue, String expectedOldValue, Op op) {
        String content = new String(originalBytes, fileInfo.offset(), originalBytes.length - fileInfo.offset(), fileInfo.charset());

        // Split keeping trailing empty lines.
        List<String> rawLines = new ArrayList<>();
        Matcher m = LINE_SPLIT_PATTERN.matcher(content);
        while (m.find()) {
            if (m.group(1).isEmpty()) break; // Last empty match
            rawLines.add(m.group(1));
        }

        List<String> updatedLines = updateLines(rawLines, path, newValue, expectedOldValue, op);

        // Re-assemble string.
        StringBuilder sb = new StringBuilder(content.length());
        for (String l : updatedLines) sb.append(l);
        String updatedContent = sb.toString();

        // Encode using original charset and BOM.
        byte[] bytes = updatedContent.getBytes(fileInfo.charset());
        if (fileInfo.bom() != null && fileInfo.bom().length > 0) {
            byte[] withBom = new byte[fileInfo.bom().length + bytes.length];
            System.arraycopy(fileInfo.bom(), 0, withBom, 0, fileInfo.bom().length);
            System.arraycopy(bytes, 0, withBom, fileInfo.bom().length, bytes.length);
            return withBom;
        }
        return bytes;
    }

    /**
     * Updates list of raw lines, replacing value at target path. Only inline scalar values are supported. If the path
     * cannot be resolved to an inline scalar, the method throws {@link IllegalArgumentException}.
     */
    private static List<String> updateLines(List<String> lines, List<String> path, String newValue, String expectedOldValue, Op op) {
        // Stack representing current path components (mapping keys or sequence indices)
        Deque<PathEntry> stack = new ArrayDeque<>();
        // Sequence index counters per indent level
        int[] seqIdx = new int[64];          // enlarge if ever needed
        Arrays.fill(seqIdx, -1);

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String logicalLine = line.replaceAll("(\\r\\n|\\r|\\n)$", "");
            String eol = line.substring(logicalLine.length());

            String trimmed = logicalLine.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue; // skip blank & comment
            }

            // Determine indent (spaces before first non-space char)
            int indent = 0;
            while (indent < logicalLine.length() && logicalLine.charAt(indent) == ' ') indent++;

            // Pop stack levels deeper or equal to current indent
            while (!stack.isEmpty() && stack.peekLast().indent() >= indent) {
                stack.pollLast();
            }
            // Clear sequence counters deeper than current indent
            final int currentIndent = indent;
            seqIdx[currentIndent+1] = -1;               // cheap "reset deeper levels"

            Matcher mSeqKv = SEQ_KV_PATTERN.matcher(logicalLine);
            boolean matchedSeqKv = mSeqKv.find();
            Matcher mSeqScalar = SEQ_SCALAR_PATTERN.matcher(logicalLine);
            boolean matchedSeqScalar = !matchedSeqKv && mSeqScalar.find();
            Matcher mMapKv = MAP_KV_PATTERN.matcher(logicalLine);

            if (matchedSeqKv) {
                // Sequence item with inline key-value ("- key: value")
                int seqIndent = mSeqKv.group(1).length();
                int index = ++seqIdx[seqIndent];
                seqIdx[seqIndent+1] = -1;               // cheap "reset deeper levels"

                stack.addLast(new PathEntry(seqIndent, String.valueOf(index)));

                String key = mSeqKv.group(2).trim();
                String afterColonSpaces = mSeqKv.group(3);
                String valueAndComment = mSeqKv.group(4);

                // Prepare candidate path
                List<String> candidate = new ArrayList<>();
                for (PathEntry p : stack) candidate.add(p.key());
                candidate.add(key);

                if (candidate.equals(path)) {
                    int dashIdx = logicalLine.indexOf('-');
                    String beforeDash = dashIdx >= 0 ? logicalLine.substring(0, dashIdx) : "";
                    int cIdx = findCommentIndex(valueAndComment);
                    String commentPart = cIdx>=0 ? valueAndComment.substring(cIdx):"";
                    String valuePrefix = cIdx>=0 ? valueAndComment.substring(0,cIdx):valueAndComment;

                    boolean isBlock = valuePrefix.trim().startsWith("|") || valuePrefix.trim().startsWith(">");

                    if (isBlock) {
                        // locate block end
                        int startIndent = indent;
                        int end = i+1;
                        while(end < lines.size()){
                            String next = lines.get(end);
                            String nl = next.replaceAll("(\\r\\n|\\r|\\n)$", "");
                            int ni=0; while(ni<nl.length() && nl.charAt(ni)==' ') ni++;
                            if(nl.trim().isEmpty()){ end++; continue; }
                            if(ni <= startIndent) break;
                            end++;
                        }

                        switch(op){
                            case REPLACE -> {
                                String newLogical;
                                if(newValue.contains("\n")){
                                    newLogical = beforeDash + "- " + key + ": |" + commentPart + eol;
                                    List<String> block = new ArrayList<>();
                                    String seqIndentStr = beforeDash + "  ";
                                    for(String part:newValue.split("\\n",-1)){
                                        block.add(seqIndentStr + part + eol);
                                    }
                                    lines.subList(i,end).clear();
                                    lines.add(i,newLogical);
                                    lines.addAll(i+1,block);
                                }else{
                                    newLogical = beforeDash + "- " + key + ": " + newValue + commentPart;
                                    lines.subList(i,end).clear();
                                    lines.add(i,newLogical+eol);
                                }
                            }
                            case CLEAR_VALUE -> {
                                String newLogical = beforeDash + "- " + key + ":" + afterColonSpaces + "" + commentPart + eol;
                                lines.subList(i,end).clear();
                                lines.add(i,newLogical);
                            }
                            case DELETE_LINE -> {
                                lines.subList(i,end).clear();
                                i--;
                            }
                        }
                        return lines;
                    }

                    String valuePart = valuePrefix;

                    // Check expected value
                    if (expectedOldValue != null && !valuePart.trim().equals(expectedOldValue)) {
                        // value mismatch – skip
                        return lines;
                    }

                    int trailingSpaces = countTrailingSpaces(valuePart);
                    String spacesBeforeComment = " ".repeat(trailingSpaces);

                    switch(op) {
                        case REPLACE -> {
                            String newLogical = beforeDash + "- " + key + ":" + afterColonSpaces + newValue + spacesBeforeComment + commentPart;
                            lines.set(i, newLogical + eol);
                        }
                        case CLEAR_VALUE -> {
                            String newLogical = beforeDash + "- " + key + ":" + afterColonSpaces + "" + spacesBeforeComment + commentPart;
                            lines.set(i, newLogical + eol);
                        }
                        case DELETE_LINE -> {
                            lines.remove(i);
                            i--; // adjust index
                        }
                    }
                    return lines;
                }

            } else if (matchedSeqScalar) {
                // Sequence item with scalar ("- value")
                int seqIndent = mSeqScalar.group(1).length();
                int index = ++seqIdx[seqIndent];
                seqIdx[seqIndent+1] = -1;               // cheap "reset deeper levels"

                stack.addLast(new PathEntry(seqIndent, String.valueOf(index)));

                String valueAndComment = mSeqScalar.group(2);

                List<String> candidate = new ArrayList<>();
                for (PathEntry p : stack) candidate.add(p.key());

                if (candidate.equals(path)) {
                    if (valueAndComment.startsWith("|") || valueAndComment.startsWith(">") || valueAndComment.isEmpty()) {
                        throw new IllegalArgumentException("Path points to non-scalar or multi-line value which is not supported by in-place editor.");
                    }

                    int commentIdx = findCommentIndex(valueAndComment);
                    String valuePart;
                    String commentPart = "";
                    if (commentIdx >= 0) {
                        valuePart = valueAndComment.substring(0, commentIdx);
                        commentPart = valueAndComment.substring(commentIdx);
                    } else {
                        valuePart = valueAndComment;
                    }

                    // Check if value matches expected (if specified)
                    if (expectedOldValue != null && !valuePart.trim().equals(expectedOldValue)) {
                        continue; // Not the occurrence we want
                    }

                    // Perform the operation
                    switch (op) {
                        case REPLACE -> {
                            int trailingSpaces = countTrailingSpaces(valuePart);
                            String spacesBeforeComment = " ".repeat(trailingSpaces);
                            int dashIdx = logicalLine.indexOf('-');
                            String beforeDash = dashIdx >= 0 ? logicalLine.substring(0, dashIdx) : "";
                            String newLogicalLine = beforeDash + "- " + newValue + spacesBeforeComment + commentPart;
                            lines.set(i, newLogicalLine + eol);
                        }
                        case CLEAR_VALUE -> {
                            int trailingSpaces = countTrailingSpaces(valuePart);
                            String spacesBeforeComment = " ".repeat(trailingSpaces);
                            int dashIdx = logicalLine.indexOf('-');
                            String beforeDash = dashIdx >= 0 ? logicalLine.substring(0, dashIdx) : "";
                            String newLogicalLine = beforeDash + "- " + "" + spacesBeforeComment + commentPart;
                            lines.set(i, newLogicalLine + eol);
                        }
                        case DELETE_LINE -> {
                            lines.remove(i);
                            i--; // Adjust index since we removed a line
                        }
                    }
                    return lines;
                }

            } else if (mMapKv.find()) {
                // Standard mapping key-value
                String indentStr = mMapKv.group(1);
                String key = mMapKv.group(2).trim();
                String afterColonSpaces = mMapKv.group(3);
                String valueAndComment = mMapKv.group(4);

                stack.addLast(new PathEntry(indent, key));

                List<String> candidate = new ArrayList<>();
                for (PathEntry p : stack) candidate.add(p.key());

                if (candidate.equals(path)) {
                    // split comment from value string (if any)
                    int dashIdx = logicalLine.indexOf('-');
                    String beforeDash = dashIdx >= 0 ? logicalLine.substring(0, dashIdx) : "";
                    int cIdx = findCommentIndex(valueAndComment);
                    String commentPart = cIdx>=0 ? valueAndComment.substring(cIdx):"";
                    String valuePrefix = cIdx>=0 ? valueAndComment.substring(0,cIdx):valueAndComment;

                    boolean isBlock = valuePrefix.trim().startsWith("|") || valuePrefix.trim().startsWith(">");

                    if (isBlock) {
                        // locate end of block (first line whose indent <= current indent)
                        int end = i + 1;
                        while (end < lines.size()) {
                            String next = lines.get(end);
                            String nextLogical = next.replaceAll("(\\r\\n|\\r|\\n)$", "");
                            int nextIndent = 0; while (nextIndent < nextLogical.length() && nextLogical.charAt(nextIndent)==' ') nextIndent++;
                            if (nextLogical.trim().isEmpty()) { end++; continue; }
                            if (nextIndent <= indent) break;
                            end++;
                        }

                        switch(op) {
                            case REPLACE -> {
                                // Build new block with '|'
                                String newLogicalLine;
                                if (newValue.contains("\n")) {
                                    newLogicalLine = indentStr + key + ": |" + commentPart + eol;
                                    List<String> newBlock = new ArrayList<>();
                                    String seqIndentStr = indentStr + "  ";
                                    for (String p : newValue.split("\n", -1)) {
                                        newBlock.add(seqIndentStr + p + eol);
                                    }
                                    lines.subList(i, end).clear();
                                    lines.add(i, newLogicalLine);
                                    lines.addAll(i+1, newBlock);
                                } else {
                                    // simple scalar replacement, drop old block
                                    newLogicalLine = indentStr + key + ": " + newValue + commentPart;
                                    lines.subList(i, end).clear();
                                    lines.add(i, newLogicalLine + eol);
                                }
                            }
                            case CLEAR_VALUE -> {
                                String newLogicalLine = indentStr + key + ":" + afterColonSpaces + "" + commentPart;
                                lines.subList(i, end).clear();
                                lines.add(i, newLogicalLine + eol);
                            }
                            case DELETE_LINE -> {
                                lines.subList(i, end).clear();
                                i--; // adjust after deletion
                            }
                        }
                        return lines;
                    }

                    // ---- Inline scalar handling (original logic) ----
                    String valuePart = valuePrefix;

                    // Check expected value
                    if (expectedOldValue != null && !valuePart.trim().equals(expectedOldValue)) {
                        // value mismatch – skip
                        return lines;
                    }

                    int trailingSpaces = countTrailingSpaces(valuePart);
                    String spacesBeforeComment = " ".repeat(trailingSpaces);

                    switch(op) {
                        case REPLACE -> {
                            String newLogical = indentStr + key + ":" + afterColonSpaces + newValue + spacesBeforeComment + commentPart;
                            lines.set(i, newLogical + eol);
                        }
                        case CLEAR_VALUE -> {
                            String newLogical = indentStr + key + ":" + afterColonSpaces + "" + spacesBeforeComment + commentPart;
                            lines.set(i, newLogical + eol);
                        }
                        case DELETE_LINE -> {
                            lines.remove(i);
                            i--; // adjust index
                        }
                    }
                    return lines;
                }
            }
        }
        throw new IllegalArgumentException("Path not found or does not correspond to an inline scalar value: " + String.join("/", path));
    }

    /**
     * Returns the index (within {@code valueAndComment}) of the # that starts an inline comment, or -1 if none.
     */
    private static int findCommentIndex(String valueAndComment) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = 0; i < valueAndComment.length(); i++) {
            char c = valueAndComment.charAt(i);
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (c == '#' && !inSingleQuote && !inDoubleQuote) {
                // Comment starts if preceding char is whitespace or start of string
                if (i == 0 || Character.isWhitespace(valueAndComment.charAt(i - 1))) {
                    return i;
                }
            }
        }
        return -1;
    }

    /* =====================================================
     * Helpers & internal records
     * ===================================================== */

    private record PathEntry(int indent, String key) {}

    private record FileInfo(Charset charset, String eol, List<String> originalEols, boolean lastLineNoEol, byte[] bom, int offset) {
        static FileInfo detect(byte[] content) {
            var encodingInfo = EncodingUtil.detectEncoding(content);
            var eolInfo = EolUtil.detectLineEndings(content, encodingInfo.charset());
            return new FileInfo(encodingInfo.charset(), eolInfo.eol(), eolInfo.originalEols(), eolInfo.lastLineNoEol(), encodingInfo.bom(), encodingInfo.offset());
        }
    }

    /* =====================================================
     * Encoding & EOL detection utilities (copied from IniInPlaceEditor)
     * ===================================================== */

    private static final class EncodingUtil {
        private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        private static final byte[] UTF16BE_BOM = {(byte) 0xFE, (byte) 0xFF};
        private static final byte[] UTF16LE_BOM = {(byte) 0xFF, (byte) 0xFE};

        private static EncodingInfo detectEncoding(byte[] content) {
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
            String utf8 = StandardCharsets.UTF_8.name();
            try {
                new String(content, StandardCharsets.UTF_8);
                return new EncodingInfo(StandardCharsets.UTF_8, new byte[0], 0);
            } catch (Exception ignored) {
            }
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

        private static EolInfo detectLineEndings(byte[] content, Charset charset) {
            String text = new String(content, charset);
            Matcher matcher = EOL_PATTERN.matcher(text);
            List<String> eols = new ArrayList<>();
            while (matcher.find()) {
                eols.add(matcher.group());
            }
            String dominant = eols.stream()
                    .reduce((a, b) -> a.length() >= b.length() ? a : b)
                    .orElse("\n");
            boolean lastLineNoEol = !text.endsWith("\n") && !text.endsWith("\r");
            return new EolInfo(dominant, eols, lastLineNoEol);
        }
    }

    private record EolInfo(String eol, List<String> originalEols, boolean lastLineNoEol) {}

    private static int countTrailingSpaces(String valuePart) {
        int trailingSpaces = 0;
        for (int p = valuePart.length() - 1; p >= 0 && Character.isWhitespace(valuePart.charAt(p)); p--) trailingSpaces++;
        return trailingSpaces;
    }
} 