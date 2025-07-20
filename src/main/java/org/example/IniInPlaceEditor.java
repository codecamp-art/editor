package org.example;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Modern in-place editor for INI files with comprehensive formatting preservation.
 * Supports mixed line endings, multiple encodings (UTF-8, GBK), and advanced comment handling.
 */
public final class IniInPlaceEditor {
    
    /** Utility class – no instances allowed */
    private IniInPlaceEditor() { }

    /**
     * Encapsulates file encoding and line ending information
     */
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

    //  =====================================================
    //  New operation enum and convenience methods for removal
    //  =====================================================
    /** Types of in-place edit supported */
    private enum Op { REPLACE, BLANK_VALUE, DELETE_LINE }

    /* =====================================================
     * Public high-level API (slash-path based)
     * ===================================================== */

    /*
     * Centralised worker that every public overload eventually calls. Having a single place greatly improves
     * maintainability and eliminates the earlier copy-pasted code paths for GBK vs UTF-8 vs BOM cases.
     */
    private static void doEdit(File file,
                               String encodingHint,       // null / "" ⇒ auto/BOM detect
                               String iniPath,
                               String newValue,
                               String expectedOldValue,   // null ⇒ unconditional
                               Op op,
                               List<String> linePrefixes,
                               List<String[]> blockPrefixes) throws IOException {

        byte[] original = Files.readAllBytes(file.toPath());

        // Determine effective FileInfo respecting optional override charset.
        FileInfo base = FileInfo.detect(original);
        FileInfo eff;
        if (encodingHint == null || encodingHint.isEmpty()) {
            eff = base;
        } else {
            Charset cs = Charset.forName(encodingHint);
            eff = new FileInfo(cs, base.eol(), base.originalEols(), base.lastLineNoEol(), base.bom(), base.offset());
        }

        byte[] modified = editIniInternal(original, eff, iniPath, newValue, expectedOldValue,
                linePrefixes, blockPrefixes, op);

        Files.write(file.toPath(), modified);
    }

    /* ------------------------------------------------------------------
     * Thin public facades – keep old signatures for compatibility
     * ------------------------------------------------------------------ */

    public static void editIni(File file, String iniPath, String newValue) throws IOException {
        doEdit(file, null, iniPath, newValue, null, Op.REPLACE, null, null);
    }

    /** Explicit-encoding variant kept for compatibility (e.g. GBK). */
    public static void editIniWithEncoding(File file, String encoding, String iniPath, String newValue) throws IOException {
        doEdit(file, encoding, iniPath, newValue, null, Op.REPLACE, null, null);
    }

    /* Advanced comment overload retained – delegates to the core */
    public static void editIni(File file, String section, String key, String newValue,
                               List<String> lineCommentPrefixes, List<String[]> blockCommentDelimiters) throws IOException {
        String path = (section == null || section.isEmpty()) ? key : section + "/" + key;
        doEdit(file, null, path, newValue, null, Op.REPLACE, lineCommentPrefixes, blockCommentDelimiters);
    }

    /* =================================================================
       Guarded / removal operations now delegate to core as well
       ================================================================= */

    public static void replaceIfMatches(File file, String iniPath, String expectedOld, String newValue) throws IOException {
        doEdit(file, null, iniPath, newValue, expectedOld, Op.REPLACE, null, null);
    }

    public static void replaceIfMatchesWithEncoding(File file, String encoding, String iniPath,
                                                     String expectedOldValue, String newValue) throws IOException {
        doEdit(file, encoding, iniPath, newValue, expectedOldValue, Op.REPLACE, null, null);
    }

    public static void removeValue(File file, String iniPath) throws IOException {
        doEdit(file, null, iniPath, "", null, Op.BLANK_VALUE, null, null);
    }

    public static void removeValueIfMatches(File file, String iniPath, String expectedOld) throws IOException {
        doEdit(file, null, iniPath, "", expectedOld, Op.BLANK_VALUE, null, null);
    }

    public static void removeLine(File file, String iniPath) throws IOException {
        doEdit(file, null, iniPath, null, null, Op.DELETE_LINE, null, null);
    }

    public static void removeLineIfMatches(File file, String iniPath, String expectedOld) throws IOException {
        doEdit(file, null, iniPath, null, expectedOld, Op.DELETE_LINE, null, null);
    }

    /* ------------------------------------------------------------------
     * Universal helper already added previously – keep but delegate
     * ------------------------------------------------------------------ */
    public static void replaceIfMatches(File file, String iniPath, String expectedOldValue,
                                        String newValue, String encodingHint) throws IOException {
        doEdit(file, encodingHint, iniPath, newValue, expectedOldValue, Op.REPLACE, null, null);
    }

    /* =====================================================
     * Internals
     * ===================================================== */

    /** Shared worker that performs the in-memory mutation and returns new bytes ready for persistence. */
    private static byte[] editIniInternal(byte[] originalBytes,
                                          FileInfo fileInfo,
                                          String iniPath,
                                          String newValue,
                                          String expectedOldValue,
                                          List<String> lineCommentPrefixes,
                                          List<String[]> blockCommentDelimiters,
                                          Op op) throws IOException {

        if (op == null) op = Op.REPLACE; // backward-compatibility

        String[] parts = parseIniPath(iniPath);
        String section = parts[0];
        String key = parts[1];

        byte[] slice = Arrays.copyOfRange(originalBytes, fileInfo.offset(), originalBytes.length);
        List<IniLine> lines = parseIniContent(slice, fileInfo.charset(), lineCommentPrefixes, blockCommentDelimiters);

        List<IniLine> result = processEdit(lines, section, key, newValue, expectedOldValue, lineCommentPrefixes, op);

        // Build updated content preserving original line endings & BOM
        StringBuilder sb = new StringBuilder(slice.length);
        for (int i = 0; i < result.size(); i++) {
            IniLine l = result.get(i);
            sb.append(l.raw());
            if (l.eol() != null) {
                sb.append(l.eol());
            } else if (i < result.size() - 1) {
                sb.append(fileInfo.eol());
            }
        }

        byte[] body = sb.toString().getBytes(fileInfo.charset());
        if (fileInfo.bom() != null) {
            byte[] out = new byte[fileInfo.bom().length + body.length];
            System.arraycopy(fileInfo.bom(), 0, out, 0, fileInfo.bom().length);
            System.arraycopy(body, 0, out, fileInfo.bom().length, body.length);
            return out;
        }
        return body;
    }

    /** Convert slash-separated path into { section, key }. Empty section implies global scope. */
    private static String[] parseIniPath(String path) {
        if (path.startsWith("/")) path = path.substring(1);
        String[] parts = path.split("/", 2);
        if (parts.length == 1) {
            return new String[]{"", parts[0]};
        }
        return new String[]{parts[0], parts[1]};
    }

    /**
     * Much faster single-pass parser that walks the raw bytes once, harvesting line endings on the fly.
     */
    private static List<IniLine> parseIniContent(byte[] bytes, Charset cs,
                                                 List<String> lineCommentPrefixes,
                                                 List<String[]> blockCommentDelimiters) {
        List<IniLine> result = new ArrayList<>();
        int start = 0;
        boolean inBlock = false;
        String blockEnd = null;

        for (int i = 0; i < bytes.length; i++) {
            byte b = bytes[i];
            if (b == '\n' || b == '\r') {
                int lineEnd = i;
                String eol;
                if (b == '\r' && i + 1 < bytes.length && bytes[i + 1] == '\n') {
                    eol = "\r\n";
                    i++; // skip \n in next iteration
                } else {
                    eol = b == '\r' ? "\r" : "\n";
                }

                String raw = new String(bytes, start, lineEnd - start, cs);

                String trimmed = raw.trim();

                // state transitions for block comments
                if (inBlock) {
                    result.add(IniLine.blockComment(raw, eol));
                    if (blockEnd != null && trimmed.contains(blockEnd)) {
                        inBlock = false;
                        blockEnd = null;
                    }
                } else {
                    // check if this line starts a block comment
                    boolean startsBlock = false;
                    if (blockCommentDelimiters != null) {
                        for (String[] blk : blockCommentDelimiters) {
                            if (trimmed.startsWith(blk[0])) {
                                startsBlock = true;
                                inBlock = true;
                                blockEnd = blk[1];
                                break;
                            }
                        }
                    }

                    if (startsBlock) {
                        result.add(IniLine.blockComment(raw, eol));
                        // If block ends on the same line, do not enter block state
                        if (blockEnd != null && trimmed.contains(blockEnd)) {
                            inBlock = false;
                            blockEnd = null;
                        }
                    } else {
                        addNonBlockLine(raw, trimmed, eol, result, lineCommentPrefixes);
                    }
                }

                start = i + 1;
            }
        }
        // last line (no EOL)
        if (start < bytes.length) {
            String raw = new String(bytes, start, bytes.length - start, cs);
            String trimmed = raw.trim();
            if (inBlock) {
                result.add(IniLine.blockComment(raw, null));
            } else {
                addNonBlockLine(raw, trimmed, null, result, lineCommentPrefixes);
            }
        }
        return result;
    }

    private static void addNonBlockLine(String raw, String trimmed, String eol,
                                        List<IniLine> out, List<String> lineCommentPrefixes) {
        if (trimmed.isEmpty()) {
            out.add(IniLine.blank(raw, eol));
        } else if (isLineComment(trimmed, lineCommentPrefixes)) {
            out.add(IniLine.comment(raw, eol));
        } else if (isSectionHeader(trimmed)) {
            out.add(IniLine.section(raw, extractSectionName(trimmed), eol));
        } else if (containsKeyValue(trimmed)) {
            var kv = extractKeyValue(raw, lineCommentPrefixes);
            out.add(IniLine.keyValue(raw, kv.key(), kv.value(), kv.comment(), eol));
        } else {
            out.add(IniLine.comment(raw, eol));
        }
    }

    /**
     * Process the edit operation
     */
    private static List<IniLine> processEdit(List<IniLine> lines, String targetSection, String targetKey, 
                                           String newValue, String expectedOldValue, List<String> lineCommentPrefixes, Op op) {
        List<IniLine> result = new ArrayList<>();
        String currentSection = "";
        boolean inTargetSection = (targetSection == null || targetSection.isEmpty());
        boolean keyUpdated = false;
        boolean sectionFound = (targetSection == null || targetSection.isEmpty());
        int insertIndex = -1;
        
        for (int i = 0; i < lines.size(); i++) {
            IniLine line = lines.get(i);
            
            switch (line.type()) {
                case SECTION -> {
                    // Check if we should insert the key before this section
                    if (inTargetSection && !keyUpdated && sectionFound && insertIndex == -1) {
                        insertIndex = result.size();
                    }
                    
                    currentSection = line.section();
                    inTargetSection = targetSection != null && targetSection.equals(currentSection);
                    if (inTargetSection) sectionFound = true;
                    result.add(line);
                }
                
                case KEY_VALUE -> {
                    if (inTargetSection && line.key().equals(targetKey) && !keyUpdated) {
                        boolean valueMatches = expectedOldValue == null || (line.value() != null && line.value().trim().equals(expectedOldValue));
                        if (!valueMatches) {
                            // Not the occurrence we want (value mismatch); treat as normal line
                            result.add(line);
                            continue;
                        }
                        // Found the target key – act depending on op
                        switch (op) {
                            case REPLACE -> {
                                String newLine = formatKeyValueLine(line, targetKey, newValue, lineCommentPrefixes);
                                result.add(IniLine.keyValue(newLine, targetKey, newValue, line.comment(), line.eol()));
                            }
                            case BLANK_VALUE -> {
                                String newLine = formatKeyValueLine(line, targetKey, "", lineCommentPrefixes);
                                result.add(IniLine.keyValue(newLine, targetKey, "", line.comment(), line.eol()));
                            }
                            case DELETE_LINE -> {
                                // Skip adding this line -> effectively deletes it.
                            }
                        }
                        keyUpdated = true;
                    } else {
                        result.add(line);
                    }
                }
                
                default -> result.add(line);
            }
        }
        
        // Fail fast if the key was not located – replacement only, no implicit inserts.
        if (!keyUpdated) {
            throw new IllegalArgumentException("Path not found or does not correspond to a scalar value: "
                    + (targetSection.isEmpty() ? targetKey : targetSection + "/" + targetKey));
        }
        
        return result;
    }

    /**
     * Format a key-value line with proper spacing preservation
     */
    private static String formatKeyValueLine(IniLine originalLine, String key, String value, List<String> commentPrefixes) {
        String original = originalLine.raw();
        int eqIndex = original.indexOf('=');
        
        if (eqIndex == -1) {
            // Fallback: create new line
            return key + "=" + value;
        }
        
        // Preserve original spacing around equals sign
        String beforeEq = original.substring(0, eqIndex);
        String afterEq = original.substring(eqIndex + 1);
        
        // Find where the value starts (after spaces)
        int valueStart = 0;
        while (valueStart < afterEq.length() && Character.isWhitespace(afterEq.charAt(valueStart))) {
            valueStart++;
        }
        
        // Find where the value ends (before comment)
        int valueEnd = afterEq.length();
        for (String prefix : getCommentPrefixes(commentPrefixes)) {
            int commentIndex = afterEq.indexOf(prefix);
            if (commentIndex != -1 && commentIndex < valueEnd) {
                valueEnd = commentIndex;
            }
        }
        
        // Preserve trailing spaces before comment
        while (valueEnd > valueStart && Character.isWhitespace(afterEq.charAt(valueEnd - 1))) {
            valueEnd--;
        }
        
        // Reconstruct the line
        String newAfterEq = afterEq.substring(0, valueStart) + value + afterEq.substring(valueEnd);
        return beforeEq + "=" + newAfterEq;
    }

    // Utility methods
    private static boolean isLineComment(String trimmed, List<String> prefixes) {
        return getCommentPrefixes(prefixes).stream().anyMatch(trimmed::startsWith);
    }
    
    private static List<String> getCommentPrefixes(List<String> prefixes) {
        return prefixes != null ? prefixes : List.of(";", "#");
    }
    
    private static boolean isSectionHeader(String trimmed) {
        return trimmed.startsWith("[") && trimmed.endsWith("]");
    }
    
    private static String extractSectionName(String trimmed) {
        return trimmed.substring(1, trimmed.length() - 1);
    }
    
    private static boolean containsKeyValue(String trimmed) {
        return trimmed.contains("=");
    }
    
    private record KeyValueInfo(String key, String value, String comment) {}
    
    private static KeyValueInfo extractKeyValue(String line, List<String> commentPrefixes) {
        int eqIndex = line.indexOf('=');
        if (eqIndex == -1) return new KeyValueInfo("", "", null);
        
        String beforeEq = line.substring(0, eqIndex);
        String afterEq = line.substring(eqIndex + 1);
        
        // Extract key (trimmed)
        String key = beforeEq.trim();
        
        // Find comment in afterEq
        String comment = null;
        int commentIndex = -1;
        for (String prefix : getCommentPrefixes(commentPrefixes)) {
            int idx = afterEq.indexOf(prefix);
            if (idx != -1 && (commentIndex == -1 || idx < commentIndex)) {
                commentIndex = idx;
                comment = afterEq.substring(idx);
            }
        }
        
        // Extract value (before comment)
        String value = commentIndex != -1 ? afterEq.substring(0, commentIndex) : afterEq;
        
        return new KeyValueInfo(key, value, comment);
    }

    /**
     * Encoding detection utility
     */
    private static final class EncodingUtil {
        private static final byte[] UTF8_BOM = {(byte)0xEF,(byte)0xBB,(byte)0xBF};
        private static final byte[] UTF16BE_BOM = {(byte)0xFE,(byte)0xFF};
        private static final byte[] UTF16LE_BOM = {(byte)0xFF,(byte)0xFE};
        static IniInPlaceEditor.EncodingInfo detectEncoding(byte[] content) {
            if (startsWith(content, UTF8_BOM)) return new IniInPlaceEditor.EncodingInfo(StandardCharsets.UTF_8, UTF8_BOM, 3);
            if (startsWith(content, UTF16BE_BOM)) return new IniInPlaceEditor.EncodingInfo(StandardCharsets.UTF_16BE, UTF16BE_BOM, 2);
            if (startsWith(content, UTF16LE_BOM)) return new IniInPlaceEditor.EncodingInfo(StandardCharsets.UTF_16LE, UTF16LE_BOM, 2);
            // Fallback – try UTF-8 else GBK
            try { new String(content, StandardCharsets.UTF_8); return new IniInPlaceEditor.EncodingInfo(StandardCharsets.UTF_8, new byte[0], 0);} catch(Exception ignored){}
            return new IniInPlaceEditor.EncodingInfo(Charset.forName("GBK"), new byte[0], 0);
        }
        private static boolean startsWith(byte[] src, byte[] prefix){ if(src.length<prefix.length) return false; for(int i=0;i<prefix.length;i++) if(src[i]!=prefix[i]) return false; return true; }
    }
    
    private record EncodingInfo(Charset charset, byte[] bom, int offset) {}
    
    /**
     * Line ending detection utility
     */
    private static final class EolUtil {
        private static final Pattern P = Pattern.compile("(\\r\\n|\\r|\\n)");
        static IniInPlaceEditor.EolInfo detectLineEndings(byte[] content, Charset cs){
            String t = new String(content, cs);
            Matcher m = P.matcher(t);
            List<String> eols = new ArrayList<>();
            while (m.find()) eols.add(m.group());
            String dominant = eols.stream().findFirst().orElse("\n");
            boolean noEol = t.isEmpty() || (!t.endsWith("\n") && !t.endsWith("\r"));
            return new IniInPlaceEditor.EolInfo(dominant, eols, noEol);
        }
    }
    
    private record EolInfo(String eol, List<String> originalEols, boolean lastLineNoEol) {}

    /**
     * Back-compat overload using default comment prefixes (';') and no block comments.
     */
    public static void editIni(File file, String section, String key, String newValue) throws IOException {
        editIni(file, section, key, newValue, Arrays.asList(";"), null);
    }

    /**
     * Back-compat overload for GBK with advanced comment settings.
     */
    public static void editIniWithEncoding(File file,
                                           String section,
                                           String key,
                                           String newValue,
                                           String encoding,
                                           List<String> lineCommentPrefixes,
                                           List<String[]> blockCommentDelimiters) throws IOException {
        String path = (section == null || section.isEmpty()) ? key : section + "/" + key;
        doEdit(file, encoding, path, newValue, null, Op.REPLACE, lineCommentPrefixes, blockCommentDelimiters);
    }

    /*
     * Public helpers ––––––––––––––––––––––––––––––––––––––––––––––––––
     */

    /**
     * Represents a single logical line of an INI file.
     * All fields store raw untouched segments so we can rebuild the file byte-for-byte.
     */
    private record IniLine(
            String raw,             // full line without EOL
            LineType type,
            String section,         // for SECTION
            String key,             // for KEY_VALUE
            String value,           // for KEY_VALUE (raw value part)
            String comment,         // inline comment (may be null)
            String eol              // original EOL chars or null
    ) {
        enum LineType { SECTION, KEY_VALUE, COMMENT, BLANK, BLOCK_COMMENT }

        static IniLine section(String raw, String sectionName, String eol) {
            return new IniLine(raw, LineType.SECTION, sectionName, null, null, null, eol);
        }

        static IniLine keyValue(String raw, String key, String value, String comment, String eol) {
            return new IniLine(raw, LineType.KEY_VALUE, null, key, value, comment, eol);
        }

        static IniLine comment(String raw, String eol) {
            return new IniLine(raw, LineType.COMMENT, null, null, null, null, eol);
        }

        static IniLine blank(String raw, String eol) {
            return new IniLine(raw, LineType.BLANK, null, null, null, null, eol);
        }

        static IniLine blockComment(String raw, String eol) {
            return new IniLine(raw, LineType.BLOCK_COMMENT, null, null, null, null, eol);
        }
    }

    /* =================================================================
       Unified public API – prefer these going forward
       ================================================================= */

    /**
     * Unified value editor. If {@code newValue} is empty the value is cleared but the key remains.
     * Set {@code expectedOld} for optimistic concurrency; set {@code encodingHint} (e.g. "GBK") to override charset.
     */
    public static void setValue(File file,
                                String iniPath,
                                String newValue,
                                String expectedOld,
                                String encodingHint,
                                List<String> linePrefixes,
                                List<String[]> blockPrefixes) throws IOException {
        doEdit(file, encodingHint, iniPath, newValue == null ? "" : newValue,
                expectedOld, Op.REPLACE, linePrefixes, blockPrefixes);
    }

    // Convenience overloads
    public static void setValue(File file, String iniPath, String newValue) throws IOException {
        setValue(file, iniPath, newValue, null, null, null, null);
    }

    /** Delete entire key-value line */
    public static void deleteLine(File file,
                                  String iniPath,
                                  String expectedOld,
                                  String encodingHint,
                                  List<String> linePrefixes,
                                  List<String[]> blockPrefixes) throws IOException {
        doEdit(file, encodingHint, iniPath, null, expectedOld, Op.DELETE_LINE, linePrefixes, blockPrefixes);
    }

    public static void deleteLine(File file, String iniPath) throws IOException {
        deleteLine(file, iniPath, null, null, null, null);
    }

    /* =====================================================
       InputStream-based variants (no direct file write)   
       ===================================================== */

    public static byte[] setValue(InputStream in,
                                  String iniPath,
                                  String newValue,
                                  String expectedOld,
                                  String encodingHint,
                                  List<String> linePrefixes,
                                  List<String[]> blockPrefixes) throws IOException {
        byte[] original = in.readAllBytes();
        FileInfo base = FileInfo.detect(original);
        FileInfo eff;
        if (encodingHint == null || encodingHint.isEmpty()) {
            eff = base;
        } else {
            eff = new FileInfo(Charset.forName(encodingHint), base.eol(), base.originalEols(),
                    base.lastLineNoEol(), base.bom(), base.offset());
        }
        return editIniInternal(original, eff, iniPath, newValue == null ? "" : newValue,
                expectedOld, linePrefixes, blockPrefixes, Op.REPLACE);
    }

    public static byte[] deleteLine(InputStream in,
                                    String iniPath,
                                    String expectedOld,
                                    String encodingHint,
                                    List<String> linePrefixes,
                                    List<String[]> blockPrefixes) throws IOException {
        byte[] original = in.readAllBytes();
        FileInfo base = FileInfo.detect(original);
        FileInfo eff = (encodingHint == null || encodingHint.isEmpty()) ? base :
                new FileInfo(Charset.forName(encodingHint), base.eol(), base.originalEols(),
                        base.lastLineNoEol(), base.bom(), base.offset());
        return editIniInternal(original, eff, iniPath, null, expectedOld,
                linePrefixes, blockPrefixes, Op.DELETE_LINE);
    }
} 