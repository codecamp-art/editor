package org.example;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Character-by-character YAML in-place editor that preserves all formatting.
 * <p>
 * Features:
 * • Character-level search and replacement by slash-separated path
 * • Boolean search (path or path+value) with conditional replacement
 * • Value removal and line deletion while preserving formatting
 * • Support for complex YAML: nested mappings/sequences, multi-line strings, comments
 * • Different YAML scalar types (string, number, boolean, null)
 * • BOM + UTF-8/UTF-16/GBK detection, mixed EOL, comment preservation
 * • Thread-safe – all public methods are stateless
 * • High performance with minimal memory allocation
 */
public final class YamlInPlaceEditor {

    private YamlInPlaceEditor() {
    }

    // ==================== Byte Array API ====================

    public static byte[] setValue(byte[] bytes, String yamlPath, Object newValue) throws IOException {
        return setValue(bytes, yamlPath, null, newValue, null);
    }

    public static byte[] setValue(byte[] bytes, String yamlPath, Object expectedOld, Object newValue) throws IOException {
        return setValue(bytes, yamlPath, expectedOld, newValue, null);
    }

    public static byte[] setValue(byte[] bytes, String yamlPath, Object expectedOld, Object newValue, String encodingHint) throws IOException {
        EncodingInfo info = detectEncoding(bytes, encodingHint);
        String content = new String(bytes, info.bomLength, bytes.length - info.bomLength, info.charset);

        List<String> path = parsePath(yamlPath);
        Locator locator = new Locator(content);
        Location loc = locator.locate(path);

        if (loc == null) {
            throw new IllegalArgumentException("Path not found: " + yamlPath);
        }

        // Check the expected value if provided
        if (expectedOld != null) {
            String currentVal = content.substring(loc.valueStart, loc.valueEnd);
            if (!valuesMatch(currentVal, expectedOld)) {
                return bytes; // No change
            }
        }

        // Replace value
        String before = content.substring(0, loc.valueStart);
        String after = content.substring(loc.valueEnd);
        String replacement = generateYamlValue(newValue, loc.indentLevel);

        String modified = before + replacement + after;
        return encode(modified, info);
    }

    public static byte[] deleteKey(byte[] bytes, String yamlPath) throws IOException {
        return deleteKey(bytes, yamlPath, null, null);
    }

    public static byte[] deleteKey(byte[] bytes, String yamlPath, Object expectedOld) throws IOException {
        return deleteKey(bytes, yamlPath, expectedOld, null);
    }

    public static byte[] deleteKey(byte[] bytes, String yamlPath, Object expectedOld, String encodingHint) throws IOException {
        EncodingInfo info = detectEncoding(bytes, encodingHint);
        String content = new String(bytes, info.bomLength, bytes.length - info.bomLength, info.charset);

        List<String> path = parsePath(yamlPath);
        Locator locator = new Locator(content);
        Location loc = locator.locate(path);

        if (loc == null) {
            throw new IllegalArgumentException("Path not found: " + yamlPath);
        }

        // Check the expected value if provided
        if (expectedOld != null) {
            String currentVal = content.substring(loc.valueStart, loc.valueEnd);
            if (!valuesMatch(currentVal, expectedOld)) {
                return bytes; // No change
            }
        }

        // Delete the entire line including indentation and EOL
        int lineStart = findLineStart(content, loc.keyStart);
        int keyLineEnd = findLineEnd(content, loc.keyStart);
        
        // Check if the key has nested children by looking at the next line
        boolean hasNestedChildren = false;
        if (keyLineEnd < content.length()) {
            int nextLineStart = keyLineEnd;
            // Skip empty lines and comments
            while (nextLineStart < content.length()) {
                int lineEnd = findLineEnd(content, nextLineStart);
                int lineIndent = 0;
                int temp = nextLineStart;
                boolean isEmptyOrComment = true;
                
                while (temp < lineEnd && temp < content.length()) {
                    char c = content.charAt(temp);
                    if (c == ' ') {
                        lineIndent++;
                        temp++;
                    } else if (c == '\t') {
                        lineIndent += 8;
                        temp++;
                    } else if (c == '\n' || c == '\r') {
                        // Empty line
                        break;
                    } else if (c == '#') {
                        // Comment line
                        break;
                    } else {
                        // Found content
                        isEmptyOrComment = false;
                        if (lineIndent > loc.indentLevel) {
                            // This line has greater indentation, so it's a nested child
                            hasNestedChildren = true;
                        }
                        break;
                    }
                }
                
                if (isEmptyOrComment) {
                    // Skip empty/comment lines and continue
                    nextLineStart = lineEnd;
                } else {
                    break;
                }
            }
        }
        
        int deletionEnd;
        if (hasNestedChildren) {
            // Find where the parent key's block ends (including all nested children)
            deletionEnd = findBlockEnd(content, lineStart, loc.indentLevel);
        } else {
            // Just delete the single line
            deletionEnd = keyLineEnd;
        }

        String before = content.substring(0, lineStart);
        String after = content.substring(deletionEnd);
        String modified = before + after;
        return encode(modified, info);
    }

    public static boolean search(byte[] bytes, String yamlPath) throws IOException {
        return search(bytes, yamlPath, null, null);
    }

    public static boolean search(byte[] bytes, String yamlPath, Object value) throws IOException {
        return search(bytes, yamlPath, value, null);
    }

    public static boolean search(byte[] bytes, String yamlPath, Object value, String encodingHint) throws IOException {
        EncodingInfo info = detectEncoding(bytes, encodingHint);
        String content = new String(bytes, info.bomLength, bytes.length - info.bomLength, info.charset);

        List<String> path = parsePath(yamlPath);
        Locator locator = new Locator(content);
        Location loc = locator.locate(path);

        if (loc == null) {
            return false;
        }

        if (value == null) {
            return true;
        }

        String currentVal = content.substring(loc.valueStart, loc.valueEnd);
        return valuesMatch(currentVal, value);
    }

    // ==================== Internal Classes ====================

    private static class Location {
        int keyStart;
        int valueStart;
        int valueEnd;
        int afterValue;
        int indentLevel;
        boolean isSequenceItem;
    }

    private static class Locator {
        private final String content;
        private int pos = 0;
        private int indentLevel = 0;

        Locator(String content) {
            this.content = content;
        }

        Location locate(List<String> path) {
            skipWhitespaceAndComments(true);
            return locateInDocument(path, 0);
        }

        private Location locateInDocument(List<String> path, int depth) {
            while (pos < content.length()) {
                skipWhitespaceAndComments(true);
                if (pos >= content.length()) break;

                // Handle document start markers
                if (content.startsWith("---", pos)) {
                    pos += 3;
                    skipToNextLine();
                    continue;
                }

                // Handle document end markers
                if (content.startsWith("...", pos)) {
                    pos += 3;
                    skipToNextLine();
                    continue;
                }

                return locateInMapping(path, depth);
            }
            return null;
        }

        private Location locateInMapping(List<String> path, int depth) {
            while (pos < content.length()) {
                skipWhitespaceAndComments(true);
                if (pos >= content.length()) break;

                // Check if we're at the start of a line
                int lineStart = findLineStart(content, pos);
                int lineIndent = 0;
                int temp = lineStart;
                while (temp < content.length() && temp < pos) {
                    if (content.charAt(temp) == ' ') {
                        lineIndent++;
                    } else if (content.charAt(temp) == '\t') {
                        lineIndent += 8;
                    } else {
                        break;
                    }
                    temp++;
                }

                // Parse key
                int keyStart = pos;
                String key = parseKey();
                if (key == null || key.isEmpty()) {
                    skipToNextLine();
                    continue;
                }

                skipWhitespace();
                if (pos >= content.length() || content.charAt(pos) != ':') {
                    skipToNextLine();
                    continue;
                }
                pos++; // Skip ':'
                skipWhitespaceAndComments(false);

                // Check if this matches our path
                if (depth < path.size() && path.get(depth).equals(key)) {
                    if (depth == path.size() - 1) {
                        // Found target
                        Location loc = new Location();
                        loc.keyStart = keyStart;
                        loc.valueStart = pos;
                        loc.indentLevel = lineIndent;

                        // Parse value to get end position
                        parseValue();
                        loc.valueEnd = pos;
                        loc.afterValue = pos;

                        return loc;
                    } else {
                        // Recurse into value
                        char c = pos < content.length() ? content.charAt(pos) : '\0';
                        if (c == '\n' || c == '\r') {
                            // Multi-line value - continue to next line for nested structure
                            skipToNextLine();
                            skipWhitespaceAndComments(true);

                            // Check if the next non-whitespace character indicates a sequence
                            if (pos < content.length() && content.charAt(pos) == '-') {
                                Location result = locateInSequence(path, depth + 1);
                                if (result != null) return result;
                            } else {
                                // Regular mapping
                                Location result = locateInMapping(path, depth + 1);
                                if (result != null) return result;
                            }
                        } else if (c == '[') {
                            // Inline array
                            Location result = locateInSequence(path, depth + 1);
                            if (result != null) return result;
                        } else if (c == '{') {
                            // Inline mapping
                            Location result = locateInInlineMapping(path, depth + 1);
                            if (result != null) return result;
                        } else {
                            // Scalar value, skip
                            parseValue();
                        }
                    }
                } else {
                    // Skip this value
                    parseValue();
                }

                skipToNextLine();
            }

            return null;
        }

        private Location locateInSequence(List<String> path, int depth) {
            if (pos < content.length() && content.charAt(pos) == '[') {
                // Inline sequence
                pos++; // Skip '['
                int index = 0;

                while (pos < content.length()) {
                    skipWhitespaceAndComments(true);
                    if (pos >= content.length() || content.charAt(pos) == ']') break;

                    // Check if this index matches our path
                    if (depth < path.size() && path.get(depth).equals(String.valueOf(index))) {
                        if (depth == path.size() - 1) {
                            // Found target in sequence
                            Location loc = new Location();
                            loc.keyStart = pos;
                            loc.valueStart = pos;
                            loc.isSequenceItem = true;

                            parseValue(true);
                            loc.valueEnd = pos;
                            loc.afterValue = pos;
                            return loc;
                        } else {
                            // Recurse into sequence item
                            char c = pos < content.length() ? content.charAt(pos) : '\0';
                            if (c == '[') {
                                Location result = locateInSequence(path, depth + 1);
                                if (result != null) return result;
                            } else if (c == '{') {
                                Location result = locateInInlineMapping(path, depth + 1);
                                if (result != null) return result;
                            }
                        }
                    }

                    parseValue(true);
                    skipWhitespaceAndComments(true);
                    if (pos < content.length() && content.charAt(pos) == ',') {
                        pos++;
                    }
                    index++;
                }
                if (pos < content.length() && content.charAt(pos) == ']') {
                    pos++;
                }
            } else {
                // Block sequence - find sequence items marked with '-'
                int index = 0;

                while (pos < content.length()) {
                    skipWhitespaceAndComments(true);
                    if (pos >= content.length()) break;

                    // Look for sequence item marker '-' directly at current position or on current line
                    // First check if we're already at a '-' after skipping whitespace
                    boolean foundSequenceMarker = false;

                    if (pos < content.length() && content.charAt(pos) == '-') {
                        // We're positioned directly at a sequence marker
                        pos++; // Skip '-'
                        skipWhitespace();
                        foundSequenceMarker = true;
                    } else {
                        // Look for '-' at the beginning of the current line
                        int lineStart = findLineStart(content, pos);
                        int currentPos = lineStart;

                        // Skip indentation
                        while (currentPos < content.length() && (content.charAt(currentPos) == ' ' || content.charAt(currentPos) == '\t')) {
                            currentPos++;
                        }

                        // Check if this line starts with '-'
                        if (currentPos < content.length() && content.charAt(currentPos) == '-') {
                            // Skip to the dash marker
                            pos = currentPos + 1; // Skip '-'
                            skipWhitespace();
                            foundSequenceMarker = true;
                        }
                    }

                    if (foundSequenceMarker) {

                        // Check if this index matches our path
                        if (depth < path.size() && path.get(depth).equals(String.valueOf(index))) {
                            if (depth == path.size() - 1) {
                                // Found target in sequence
                                Location loc = new Location();
                                loc.keyStart = pos;
                                loc.valueStart = pos;
                                loc.isSequenceItem = true;

                                parseValue();
                                loc.valueEnd = pos;
                                loc.afterValue = pos;
                                return loc;
                            } else {
                                // Recurse into sequence item value
                                char c = pos < content.length() ? content.charAt(pos) : '\0';
                                if (c == '\n' || c == '\r') {
                                    // Multi-line sequence item - continue to next level
                                    skipToNextLine();
                                    Location result = locateInMapping(path, depth + 1);
                                    if (result != null) return result;
                                } else if (c == '{') {
                                    Location result = locateInInlineMapping(path, depth + 1);
                                    if (result != null) return result;
                                } else if (c == '[') {
                                    Location result = locateInSequence(path, depth + 1);
                                    if (result != null) return result;
                                } else {
                                    // Inline sequence item value - parse the rest as a mapping
                                    // Look for key-value pairs within this sequence item
                                    Location result = locateInMapping(path, depth + 1);
                                    if (result != null) return result;
                                }
                            }
                        } else {
                            // Skip this sequence item
                            parseValue();
                        }
                        index++;
                    } else {
                        // No sequence marker found, skip to next line
                        skipToNextLine();
                    }
                }
            }
            return null;
        }

        private Location locateInInlineMapping(List<String> path, int depth) {
            pos++; // Skip '{'

            while (pos < content.length()) {
                skipWhitespaceAndComments(true);
                if (pos >= content.length() || content.charAt(pos) == '}') break;

                // Parse key
                String key = parseKey();
                if (key == null) break;

                skipWhitespaceAndComments(true);
                if (pos >= content.length() || content.charAt(pos) != ':') break;
                pos++; // Skip ':'
                skipWhitespaceAndComments(true);

                // Check if this matches our path
                if (depth < path.size() && path.get(depth).equals(key)) {
                    if (depth == path.size() - 1) {
                        // Found target
                        Location loc = new Location();
                        loc.keyStart = pos;
                        loc.valueStart = pos;

                        parseValue(true);
                        loc.valueEnd = pos;
                        loc.afterValue = pos;
                        return loc;
                    } else {
                        // Recurse into value
                        char c = pos < content.length() ? content.charAt(pos) : '\0';
                        if (c == '{') {
                            Location result = locateInInlineMapping(path, depth + 1);
                            if (result != null) return result;
                        } else if (c == '[') {
                            Location result = locateInSequence(path, depth + 1);
                            if (result != null) return result;
                        }
                    }
                } else {
                    // Skip this value
                    parseValue(true);
                }

                skipWhitespaceAndComments(true);
                if (pos < content.length() && content.charAt(pos) == ',') {
                    pos++;
                }
            }

            if (pos < content.length() && content.charAt(pos) == '}') {
                pos++;
            }
            return null;
        }

        private String parseKey() {
            if (pos >= content.length()) return null;

            char c = content.charAt(pos);
            if (c == '"' || c == '\'') {
                return parseQuotedString();
            } else if (Character.isLetter(c) || c == '_' || Character.isDigit(c)) {
                return parseUnquotedKey();
            }
            return null;
        }

        private String parseQuotedString() {
            char quote = content.charAt(pos);
            pos++; // Skip opening quote
            StringBuilder sb = new StringBuilder();
            boolean escape = false;

            while (pos < content.length()) {
                char c = content.charAt(pos);
                if (escape) {
                    sb.append(c);
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == quote) {
                    pos++; // Skip closing quote
                    break;
                } else {
                    sb.append(c);
                }
                pos++;
            }
            return sb.toString();
        }

        private String parseUnquotedKey() {
            int start = pos;
            while (pos < content.length()) {
                char c = content.charAt(pos);
                if (Character.isWhitespace(c) || c == ':' || c == '#' || c == '[' || c == ']' || c == '{' || c == '}' || c == ',' || c == '\n' || c == '\r') {
                    break;
                }
                pos++;
            }
            return content.substring(start, pos);
        }

        private void parseValue() {
            parseValue(false);
        }

        private void parseValue(boolean inInlineContext) {
            if (pos >= content.length()) return;

            char c = content.charAt(pos);
            if (c == '"' || c == '\'') {
                parseQuotedString();
            } else if (c == '[') {
                parseInlineSequence();
            } else if (c == '{') {
                parseInlineMapping();
            } else if (c == '|' || c == '>') {
                parseMultiLineString();
            } else {
                parseScalar(inInlineContext);
            }
        }

        private void parseInlineSequence() {
            pos++; // Skip '['
            int depth = 1;
            boolean inString = false;
            char stringChar = '\0';
            boolean escape = false;

            while (pos < content.length() && depth > 0) {
                char c = content.charAt(pos);

                if (inString) {
                    if (escape) {
                        escape = false;
                    } else if (c == '\\') {
                        escape = true;
                    } else if (c == stringChar) {
                        inString = false;
                    }
                } else {
                    if (c == '"' || c == '\'') {
                        inString = true;
                        stringChar = c;
                    } else if (c == '[') {
                        depth++;
                    } else if (c == ']') {
                        depth--;
                    }
                }
                pos++;
            }
        }

        private void parseInlineMapping() {
            pos++; // Skip '{'
            int depth = 1;
            boolean inString = false;
            char stringChar = '\0';
            boolean escape = false;

            while (pos < content.length() && depth > 0) {
                char c = content.charAt(pos);

                if (inString) {
                    if (escape) {
                        escape = false;
                    } else if (c == '\\') {
                        escape = true;
                    } else if (c == stringChar) {
                        inString = false;
                    }
                } else {
                    if (c == '"' || c == '\'') {
                        inString = true;
                        stringChar = c;
                    } else if (c == '{') {
                        depth++;
                    } else if (c == '}') {
                        depth--;
                    }
                }
                pos++;
            }
        }

        private void parseMultiLineString() {
            pos++; // Skip '|' or '>'
            skipToNextLine();

            // Simple approach: skip lines until we find one that looks like a YAML key
            // Multi-line content is typically indented, keys are at the left margin or consistently indented
            while (pos < content.length()) {
                int lineStart = pos;

                // Skip any indentation
                while (pos < content.length() && (content.charAt(pos) == ' ' || content.charAt(pos) == '\t')) {
                    pos++;
                }

                // If empty line, continue
                if (pos < content.length() && (content.charAt(pos) == '\n' || content.charAt(pos) == '\r')) {
                    skipToNextLine();
                    continue;
                }

                // Check if this line looks like a YAML key (has a colon followed by space/EOL)
                boolean isKey = false;
                int tempPos = pos;
                while (tempPos < content.length() && content.charAt(tempPos) != '\n' && content.charAt(tempPos) != '\r') {
                    if (content.charAt(tempPos) == ':') {
                        int afterColon = tempPos + 1;
                        if (afterColon >= content.length() ||
                                Character.isWhitespace(content.charAt(afterColon)) ||
                                content.charAt(afterColon) == '\n' ||
                                content.charAt(afterColon) == '\r') {
                            isKey = true;
                            break;
                        }
                    }
                    tempPos++;
                }

                if (isKey) {
                    // This looks like a YAML key, position at the end of the previous line
                    // so that the mapping parser's skipToNextLine() will correctly move to this key
                    pos = lineStart - 1;
                    // Make sure we don't go negative
                    if (pos < 0) pos = 0;
                    break;
                }

                // This line is part of the multi-line content, skip it
                skipToNextLine();
            }
        }

        private void parseScalar(boolean inInlineContext) {
            while (pos < content.length()) {
                char c = content.charAt(pos);
                // Only stop at commas if we're in an inline context (sequence or mapping)
                if (c == '\n' || c == '\r' || c == '#' || c == ']' || c == '}' || (inInlineContext && c == ',')) {
                    break;
                }
                pos++;
            }

            // Trim trailing whitespace from the scalar value
            while (pos > 0) {
                int prevPos = pos - 1;
                if (prevPos >= 0 && prevPos < content.length()) {
                    char prevChar = content.charAt(prevPos);
                    if (prevChar == ' ' || prevChar == '\t') {
                        pos--;
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
        }

        private int getCurrentIndent() {
            int lineStart = findLineStart(content, pos);

            int indent = 0;
            while (lineStart + indent < content.length()) {
                char c = content.charAt(lineStart + indent);
                if (c == ' ') {
                    indent++;
                } else if (c == '\t') {
                    indent += 8; // Tab = 8 spaces
                } else {
                    break;
                }
            }
            return indent;
        }

        private void skipWhitespace() {
            while (pos < content.length() && Character.isWhitespace(content.charAt(pos)) && content.charAt(pos) != '\n' && content.charAt(pos) != '\r') {
                pos++;
            }
        }

        private void skipWhitespaceAndComments(boolean skipEol) {
            while (pos < content.length()) {
                char c = content.charAt(pos);
                if ((skipEol && Character.isWhitespace(c)) || (!skipEol && Character.isWhitespace(c) && c != '\n' && c != '\r')) {
                    pos++;
                } else if (c == '#') {
                    // Skip comment to end of line
                    while (pos < content.length() && content.charAt(pos) != '\n' && content.charAt(pos) != '\r') {
                        pos++;
                    }
                } else {
                    break;
                }
            }
        }

        private void skipToNextLine() {
            while (pos < content.length() && content.charAt(pos) != '\n' && content.charAt(pos) != '\r') {
                pos++;
            }
            if (pos < content.length() && content.charAt(pos) == '\r') {
                pos++;
            }
            if (pos < content.length() && content.charAt(pos) == '\n') {
                pos++;
            }
        }
    }

    // ==================== Encoding Support ====================

    private static class EncodingInfo {
        final Charset charset;
        final int bomLength;

        EncodingInfo(Charset charset, int bomLength) {
            this.charset = charset;
            this.bomLength = bomLength;
        }
    }

    private static EncodingInfo detectEncoding(byte[] bytes, String hint) {
        // Check for BOM
        if (bytes.length >= 3 &&
                bytes[0] == (byte) 0xEF &&
                bytes[1] == (byte) 0xBB &&
                bytes[2] == (byte) 0xBF) {
            return new EncodingInfo(StandardCharsets.UTF_8, 3);
        }

        if (bytes.length >= 2) {
            if (bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) {
                return new EncodingInfo(StandardCharsets.UTF_16BE, 2);
            }
            if (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
                return new EncodingInfo(StandardCharsets.UTF_16LE, 2);
            }
        }

        // Use hint if provided
        if (hint != null && !hint.isEmpty()) {
            return new EncodingInfo(Charset.forName(hint), 0);
        }

        // Default to UTF-8
        return new EncodingInfo(StandardCharsets.UTF_8, 0);
    }

    private static byte[] encode(String content, EncodingInfo info) {
        byte[] contentBytes = content.getBytes(info.charset);

        if (info.bomLength > 0) {
            byte[] result = new byte[info.bomLength + contentBytes.length];

            // Copy BOM
            if (info.charset.equals(StandardCharsets.UTF_8)) {
                result[0] = (byte) 0xEF;
                result[1] = (byte) 0xBB;
                result[2] = (byte) 0xBF;
            } else if (info.charset.equals(StandardCharsets.UTF_16BE)) {
                result[0] = (byte) 0xFE;
                result[1] = (byte) 0xFF;
            } else if (info.charset.equals(StandardCharsets.UTF_16LE)) {
                result[0] = (byte) 0xFF;
                result[1] = (byte) 0xFE;
            }

            System.arraycopy(contentBytes, 0, result, info.bomLength, contentBytes.length);
            return result;
        }

        return contentBytes;
    }

    // ==================== Utility Methods ====================

    private static List<String> parsePath(String yamlPath) {
        if (yamlPath == null || yamlPath.isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.asList(yamlPath.split("/"));
    }

    private static boolean valuesMatch(String current, Object expected) {
        current = current.trim();

        // Handle quoted strings
        if (current.startsWith("\"") && current.endsWith("\"")) {
            if (expected instanceof String) {
                return current.substring(1, current.length() - 1).equals(expected);
            }
            return false;
        } else if (current.startsWith("'") && current.endsWith("'")) {
            if (expected instanceof String) {
                return current.substring(1, current.length() - 1).equals(expected);
            }
            return false;
        }

        // Handle different scalar types
        if (isYamlBoolean(current)) {
            if (!(expected instanceof Boolean) && !isYamlBoolean((String) expected)) {
                return false;
            }
            if (expected instanceof Boolean) {
                return parseYamlBoolean(current) == (Boolean) expected;
            }
            return parseYamlBoolean(current) == parseYamlBoolean((String) expected);

        } else if (isYamlNull(current)) {
            return expected == null;
        } else if (isYamlNumber(current)) {
            if (expected instanceof Number) {
                try {
                    double currentNum = Double.parseDouble(current);
                    double expectedNum = ((Number) expected).doubleValue();
                    return Math.abs(currentNum - expectedNum) < 1e-10;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
            return false;
        } else if (expected instanceof String) {
            return current.equals(expected);
        }

        return current.equals(String.valueOf(expected));
    }

    private static String generateYamlValue(Object value, int indentLevel) {
        if (value == null) {
            return "null";
        } else if (value instanceof Boolean) {
            return value.toString();
        } else if (value instanceof Number) {
            return value.toString();
        } else if (value instanceof String) {
            return "\"" + escapeYamlString((String) value) + "\"";
        }
        return "\"" + escapeYamlString(String.valueOf(value)) + "\"";
    }

    private static boolean needsQuoting(String str) {
        if (str.trim().isEmpty()) return true;
        if (isYamlBoolean(str) || isYamlNull(str) || isYamlNumber(str)) return true;
        if (str.contains(":") || str.contains("#") || str.contains("\"") || str.contains("'")) return true;
        if (str.startsWith(" ") || str.endsWith(" ")) return true;
        return false;
    }

    private static String escapeYamlString(String str) {
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static boolean isYamlBoolean(String s) {
        s = s.trim().toLowerCase();
        return "true".equals(s) || "false".equals(s) || "yes".equals(s) || "no".equals(s) ||
                "on".equals(s) || "off".equals(s);
    }

    private static boolean parseYamlBoolean(String s) {
        s = s.trim().toLowerCase();
        return "true".equals(s) || "yes".equals(s) || "on".equals(s);
    }

    private static boolean isYamlNull(String s) {
        s = s.trim().toLowerCase();
        return "null".equals(s) || "~".equals(s) || s.isEmpty();
    }

    private static boolean isYamlNumber(String s) {
        s = s.trim();
        if (s.isEmpty()) return false;
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            // Check for special YAML number formats
            return s.matches("^[+-]?\\d+$") || // Integer
                    s.matches("^[+-]?\\d*\\.\\d+$") || // Decimal
                    s.matches("^[+-]?\\d+(?:\\.\\d*)?[eE][+-]?\\d+$"); // Scientific
        }
    }

    private static int findLineStart(String content, int pos) {
        while (pos > 0 && content.charAt(pos - 1) != '\n' && content.charAt(pos - 1) != '\r') {
            pos--;
        }
        return pos;
    }

    private static int findLineEnd(String content, int pos) {
        while (pos < content.length() && content.charAt(pos) != '\n' && content.charAt(pos) != '\r') {
            pos++;
        }
        // Include the line ending
        if (pos < content.length() && content.charAt(pos) == '\r') {
            pos++;
        }
        if (pos < content.length() && content.charAt(pos) == '\n') {
            pos++;
        }
        return pos;
    }

    /**
     * Finds the end of a parent key's block by looking for the next line with same or less indentation.
     * This is used when deleting parent keys to remove all nested children.
     */
    private static int findBlockEnd(String content, int keyLineStart, int keyIndent) {
        int pos = findLineEnd(content, keyLineStart);
        
        // Look for the next line with same or less indentation (not empty/comment)
        while (pos < content.length()) {
            int lineStart = pos;
            int lineEnd = findLineEnd(content, pos);
            
            // Calculate indentation of this line
            int lineIndent = 0;
            int temp = lineStart;
            boolean isEmptyOrComment = true;
            
            while (temp < lineEnd && temp < content.length()) {
                char c = content.charAt(temp);
                if (c == ' ') {
                    lineIndent++;
                    temp++;
                } else if (c == '\t') {
                    lineIndent += 8;
                    temp++;
                } else if (c == '\n' || c == '\r') {
                    // Empty line
                    isEmptyOrComment = true;
                    break;
                } else if (c == '#') {
                    // Comment line
                    isEmptyOrComment = true;
                    break;
                } else {
                    // Found non-whitespace content
                    isEmptyOrComment = false;
                    if (lineIndent <= keyIndent) {
                        // This line has same or less indentation, block ends here
                        return lineStart;
                    }
                    // This line is still part of the block (greater indentation), continue to next line
                    break;
                }
            }
            
            // Move to next line
            pos = lineEnd;
        }
        
        // Reached end of content
        return content.length();
    }
} 