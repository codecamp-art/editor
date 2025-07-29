package org.example;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * A character-by-character JSON in-place editor that preserves all formatting.
 * Thread-safe, supports UTF-8/GBK with BOM, handles comments and mixed line endings.
 */
public final class JsonInPlaceEditor {
    
    private JsonInPlaceEditor() {}
    
    // ==================== File API ====================
    
    public static void setValue(File file, String jsonPath, String newValue) throws IOException {
        setValue(file, jsonPath, null, newValue, null);
    }
    
    public static void setValue(File file, String jsonPath, String expectedOld, String newValue) throws IOException {
        setValue(file, jsonPath, expectedOld, newValue, null);
    }
    
    public static void setValue(File file, String jsonPath, String expectedOld, String newValue, String encodingHint) throws IOException {
        byte[] original = Files.readAllBytes(file.toPath());
        byte[] modified = setValue(new ByteArrayInputStream(original), jsonPath, expectedOld, newValue, encodingHint);
        Files.write(file.toPath(), modified);
    }
    
    public static void deleteLine(File file, String jsonPath) throws IOException {
        deleteLine(file, jsonPath, null, null);
    }
    
    public static void deleteLine(File file, String jsonPath, String expectedOld) throws IOException {
        deleteLine(file, jsonPath, expectedOld, null);
    }
    
    public static void deleteLine(File file, String jsonPath, String expectedOld, String encodingHint) throws IOException {
        byte[] original = Files.readAllBytes(file.toPath());
        byte[] modified = deleteLine(new ByteArrayInputStream(original), jsonPath, expectedOld, encodingHint);
        Files.write(file.toPath(), modified);
    }
    
    public static boolean search(File file, String jsonPath) throws IOException {
        return search(file, jsonPath, null, null);
    }
    
    public static boolean search(File file, String jsonPath, String value) throws IOException {
        return search(file, jsonPath, value, null);
    }
    
    public static boolean search(File file, String jsonPath, String value, String encodingHint) throws IOException {
        byte[] original = Files.readAllBytes(file.toPath());
        return search(new ByteArrayInputStream(original), jsonPath, value, encodingHint);
    }
    
    // ==================== InputStream API ====================
    
    public static byte[] setValue(InputStream in, String jsonPath, String newValue) throws IOException {
        return setValue(in, jsonPath, null, newValue, null);
    }
    
    public static byte[] setValue(InputStream in, String jsonPath, String expectedOld, String newValue) throws IOException {
        return setValue(in, jsonPath, expectedOld, newValue, null);
    }
    
    public static byte[] setValue(InputStream in, String jsonPath, String expectedOld, String newValue, String encodingHint) throws IOException {
        byte[] bytes = in.readAllBytes();
        EncodingInfo info = detectEncoding(bytes, encodingHint);
        String content = new String(bytes, info.bomLength, bytes.length - info.bomLength, info.charset);
        
        List<String> path = Arrays.asList(jsonPath.split("/"));
        Locator locator = new Locator(content);
        Location loc = locator.locate(path);
        
        if (loc == null) {
            throw new IllegalArgumentException("Path not found: " + jsonPath);
        }
        
        // Check expected value if provided
        if (expectedOld != null) {
            String currentVal = content.substring(loc.valueStart, loc.valueEnd).trim();
            if (!valuesMatch(currentVal, expectedOld)) {
                return bytes; // No change
            }
        }
        
        // Replace value
        String before = content.substring(0, loc.valueStart);
        String after = content.substring(loc.valueEnd);
        String replacement = (newValue == null || newValue.isEmpty()) ? "\"\"" : newValue;
        
        String modified = before + replacement + after;
        return encode(modified, info);
    }
    
    public static byte[] deleteLine(InputStream in, String jsonPath) throws IOException {
        return deleteLine(in, jsonPath, null, null);
    }
    
    public static byte[] deleteLine(InputStream in, String jsonPath, String expectedOld) throws IOException {
        return deleteLine(in, jsonPath, expectedOld, null);
    }
    
    public static byte[] deleteLine(InputStream in, String jsonPath, String expectedOld, String encodingHint) throws IOException {
        byte[] bytes = in.readAllBytes();
        EncodingInfo info = detectEncoding(bytes, encodingHint);
        String content = new String(bytes, info.bomLength, bytes.length - info.bomLength, info.charset);
        
        List<String> path = Arrays.asList(jsonPath.split("/"));
        Locator locator = new Locator(content);
        Location loc = locator.locate(path);
        
        if (loc == null) {
            throw new IllegalArgumentException("Path not found: " + jsonPath);
        }
        
        // Check expected value if provided
        if (expectedOld != null) {
            String currentVal = content.substring(loc.valueStart, loc.valueEnd).trim();
            if (!valuesMatch(currentVal, expectedOld)) {
                return bytes; // No change
            }
        }
        
        // Find extent to delete
        int deleteStart = loc.keyStart;
        int deleteEnd = loc.afterValue;
        
        // Check if we should delete the entire line or just the key-value pair
        // Look backwards from keyStart to see if there's only whitespace to line start
        int lineStart = deleteStart;
        boolean hasOtherContent = false;
        while (lineStart > 0 && content.charAt(lineStart - 1) != '\n' && content.charAt(lineStart - 1) != '\r') {
            if (!Character.isWhitespace(content.charAt(lineStart - 1))) {
                hasOtherContent = true;
                break;
            }
            lineStart--;
        }
        
        // Look forward from afterValue to see if there's more content on this line
        int lineEnd = deleteEnd;
        while (lineEnd < content.length() && content.charAt(lineEnd) != '\n' && content.charAt(lineEnd) != '\r') {
            if (!Character.isWhitespace(content.charAt(lineEnd))) {
                hasOtherContent = true;
                break;
            }
            lineEnd++;
        }
        
        // If the line only contains this key-value pair, delete the whole line
        if (!hasOtherContent) {
            deleteStart = lineStart;
            // Include the line ending
            if (lineEnd < content.length() && content.charAt(lineEnd) == '\r') {
                lineEnd++;
            }
            if (lineEnd < content.length() && content.charAt(lineEnd) == '\n') {
                lineEnd++;
            }
            deleteEnd = lineEnd;
        } else {
            // Otherwise, just delete the key-value pair
            // If we need to preserve leading whitespace for the remaining content,
            // check if we're deleting from the beginning of meaningful content
            if (deleteEnd < content.length() && content.charAt(deleteEnd) == ' ') {
                // Skip one space to maintain separation
                deleteEnd++;
            }
        }
        
        // Handle trailing comma
        String before = content.substring(0, deleteStart);
        String after = content.substring(deleteEnd);
        
        // Remove trailing comma from previous element if this was the last one
        if (before.trim().endsWith(",") && (after.trim().startsWith("}") || after.trim().startsWith("]"))) {
            int commaPos = before.lastIndexOf(',');
            before = before.substring(0, commaPos) + before.substring(commaPos + 1);
        }
        
        String modified = before + after;
        return encode(modified, info);
    }
    
    public static boolean search(InputStream in, String jsonPath) throws IOException {
        return search(in, jsonPath, null, null);
    }
    
    public static boolean search(InputStream in, String jsonPath, String value) throws IOException {
        return search(in, jsonPath, value, null);
    }
    
    public static boolean search(InputStream in, String jsonPath, String value, String encodingHint) throws IOException {
        byte[] bytes = in.readAllBytes();
        EncodingInfo info = detectEncoding(bytes, encodingHint);
        String content = new String(bytes, info.bomLength, bytes.length - info.bomLength, info.charset);
        
        List<String> path = Arrays.asList(jsonPath.split("/"));
        Locator locator = new Locator(content);
        Location loc = locator.locate(path);
        
        if (loc == null) {
            return false;
        }
        
        if (value == null) {
            return true;
        }
        
        String currentVal = content.substring(loc.valueStart, loc.valueEnd).trim();
        return valuesMatch(currentVal, value);
    }
    
    // ==================== Internal Classes ====================
    
    private static class Location {
        int keyStart;
        int valueStart;
        int valueEnd;
        int afterValue; // Position after value including any trailing comma
    }
    
    private static class Locator {
        private final String content;
        private int pos = 0;
        
        Locator(String content) {
            this.content = content;
        }
        
        Location locate(List<String> path) {
            skipWhitespaceAndComments();
            if (pos >= content.length()) return null;
            
            if (content.charAt(pos) == '{') {
                return locateInObject(path, 0);
            } else if (content.charAt(pos) == '[') {
                return locateInArray(path, 0);
            }
            return null;
        }
        
        private Location locateInObject(List<String> path, int depth) {
            pos++; // Skip '{'
            
            while (pos < content.length()) {
                skipWhitespaceAndComments();
                if (pos >= content.length()) break;
                
                char c = content.charAt(pos);
                if (c == '}') {
                    pos++;
                    return null;
                }
                
                if (c != '"') {
                    pos++;
                    continue;
                }
                
                // Parse key
                int keyStart = pos;
                String key = parseString();
                skipWhitespaceAndComments();
                
                if (pos >= content.length() || content.charAt(pos) != ':') {
                    continue;
                }
                pos++; // Skip ':'
                skipWhitespaceAndComments();
                
                // Check if this matches our path
                if (depth < path.size() && path.get(depth).equals(key)) {
                    if (depth == path.size() - 1) {
                        // Found target
                        Location loc = new Location();
                        loc.keyStart = keyStart;
                        loc.valueStart = pos;
                        
                        // Parse value to get end position
                        parseValueToken();
                        loc.valueEnd = pos;
                        
                        // Skip any trailing comma
                        skipWhitespaceAndComments();
                        if (pos < content.length() && content.charAt(pos) == ',') {
                            pos++;
                        }
                        loc.afterValue = pos;
                        
                        return loc;
                    } else {
                        // Recurse into value
                        if (pos < content.length()) {
                            char vc = content.charAt(pos);
                            if (vc == '{') {
                                Location result = locateInObject(path, depth + 1);
                                if (result != null) return result;
                            } else if (vc == '[') {
                                Location result = locateInArray(path, depth + 1);
                                if (result != null) return result;
                            } else {
                                // Not an object/array, skip
                                parseValueToken();
                            }
                        }
                    }
                } else {
                    // Skip this value
                    parseValueToken();
                }
                
                // Skip comma if present
                skipWhitespaceAndComments();
                if (pos < content.length() && content.charAt(pos) == ',') {
                    pos++;
                }
            }
            
            return null;
        }
        
        private Location locateInArray(List<String> path, int depth) {
            pos++; // Skip '['
            int index = 0;
            
            while (pos < content.length()) {
                skipWhitespaceAndComments();
                if (pos >= content.length()) break;
                
                char c = content.charAt(pos);
                if (c == ']') {
                    pos++;
                    return null;
                }
                
                // Check if this index matches our path
                if (depth < path.size() && path.get(depth).equals(String.valueOf(index))) {
                    // Recurse into value
                    if (c == '{') {
                        Location result = locateInObject(path, depth + 1);
                        if (result != null) return result;
                    } else if (c == '[') {
                        Location result = locateInArray(path, depth + 1);
                        if (result != null) return result;
                    } else {
                        // Skip scalar
                        parseValueToken();
                    }
                } else {
                    // Skip this value
                    parseValueToken();
                }
                
                // Handle comma
                skipWhitespaceAndComments();
                if (pos < content.length() && content.charAt(pos) == ',') {
                    pos++;
                }
                index++;
            }
            
            return null;
        }
        
        private void parseValueToken() {
            if (pos >= content.length()) return;
            
            char c = content.charAt(pos);
            if (c == '"') {
                parseString();
            } else if (c == '{') {
                parseObject();
            } else if (c == '[') {
                parseArray();
            } else {
                // Parse primitive (number, boolean, null)
                while (pos < content.length()) {
                    c = content.charAt(pos);
                    if (c == ',' || c == '}' || c == ']' || Character.isWhitespace(c) || c == '/') {
                        break;
                    }
                    pos++;
                }
            }
        }
        
        private void parseObject() {
            pos++; // Skip '{'
            int depth = 1;
            boolean inString = false;
            boolean escape = false;
            
            while (pos < content.length() && depth > 0) {
                char c = content.charAt(pos);
                
                if (inString) {
                    if (escape) {
                        escape = false;
                    } else if (c == '\\') {
                        escape = true;
                    } else if (c == '"') {
                        inString = false;
                    }
                } else {
                    if (c == '"') {
                        inString = true;
                    } else if (c == '{') {
                        depth++;
                    } else if (c == '}') {
                        depth--;
                    } else if (c == '/' && pos + 1 < content.length()) {
                        char next = content.charAt(pos + 1);
                        if (next == '/') {
                            // Skip line comment
                            pos += 2;
                            while (pos < content.length() && content.charAt(pos) != '\n') pos++;
                            continue;
                        } else if (next == '*') {
                            // Skip block comment
                            pos += 2;
                            while (pos + 1 < content.length()) {
                                if (content.charAt(pos) == '*' && content.charAt(pos + 1) == '/') {
                                    pos += 2;
                                    break;
                                }
                                pos++;
                            }
                            continue;
                        }
                    }
                }
                pos++;
            }
        }
        
        private void parseArray() {
            pos++; // Skip '['
            int depth = 1;
            boolean inString = false;
            boolean escape = false;
            
            while (pos < content.length() && depth > 0) {
                char c = content.charAt(pos);
                
                if (inString) {
                    if (escape) {
                        escape = false;
                    } else if (c == '\\') {
                        escape = true;
                    } else if (c == '"') {
                        inString = false;
                    }
                } else {
                    if (c == '"') {
                        inString = true;
                    } else if (c == '[') {
                        depth++;
                    } else if (c == ']') {
                        depth--;
                    } else if (c == '/' && pos + 1 < content.length()) {
                        char next = content.charAt(pos + 1);
                        if (next == '/') {
                            // Skip line comment
                            pos += 2;
                            while (pos < content.length() && content.charAt(pos) != '\n') pos++;
                            continue;
                        } else if (next == '*') {
                            // Skip block comment
                            pos += 2;
                            while (pos + 1 < content.length()) {
                                if (content.charAt(pos) == '*' && content.charAt(pos + 1) == '/') {
                                    pos += 2;
                                    break;
                                }
                                pos++;
                            }
                            continue;
                        }
                    }
                }
                pos++;
            }
        }
        
        private String parseString() {
            if (pos >= content.length() || content.charAt(pos) != '"') {
                return "";
            }
            
            StringBuilder sb = new StringBuilder();
            pos++; // Skip opening quote
            boolean escape = false;
            
            while (pos < content.length()) {
                char c = content.charAt(pos);
                if (escape) {
                    sb.append(c);
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    pos++; // Skip closing quote
                    break;
                } else {
                    sb.append(c);
                }
                pos++;
            }
            
            return sb.toString();
        }
        
        private void skipWhitespaceAndComments() {
            while (pos < content.length()) {
                char c = content.charAt(pos);
                
                if (Character.isWhitespace(c)) {
                    pos++;
                } else if (c == '/' && pos + 1 < content.length()) {
                    char next = content.charAt(pos + 1);
                    if (next == '/') {
                        // Line comment
                        pos += 2;
                        while (pos < content.length() && content.charAt(pos) != '\n' && content.charAt(pos) != '\r') {
                            pos++;
                        }
                    } else if (next == '*') {
                        // Block comment
                        pos += 2;
                        while (pos + 1 < content.length()) {
                            if (content.charAt(pos) == '*' && content.charAt(pos + 1) == '/') {
                                pos += 2;
                                break;
                            }
                            pos++;
                        }
                    } else {
                        break;
                    }
                } else {
                    break;
                }
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
            bytes[0] == (byte)0xEF && 
            bytes[1] == (byte)0xBB && 
            bytes[2] == (byte)0xBF) {
            return new EncodingInfo(StandardCharsets.UTF_8, 3);
        }
        
        if (bytes.length >= 2) {
            if (bytes[0] == (byte)0xFE && bytes[1] == (byte)0xFF) {
                return new EncodingInfo(StandardCharsets.UTF_16BE, 2);
            }
            if (bytes[0] == (byte)0xFF && bytes[1] == (byte)0xFE) {
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
                result[0] = (byte)0xEF;
                result[1] = (byte)0xBB;
                result[2] = (byte)0xBF;
            } else if (info.charset.equals(StandardCharsets.UTF_16BE)) {
                result[0] = (byte)0xFE;
                result[1] = (byte)0xFF;
            } else if (info.charset.equals(StandardCharsets.UTF_16LE)) {
                result[0] = (byte)0xFF;
                result[1] = (byte)0xFE;
            }
            
            System.arraycopy(contentBytes, 0, result, info.bomLength, contentBytes.length);
            return result;
        }
        
        return contentBytes;
    }
    
    private static boolean valuesMatch(String current, String expected) {
        // Remove quotes if present
        current = current.trim();
        expected = expected.trim();
        
        if (current.startsWith("\"") && current.endsWith("\"")) {
            current = current.substring(1, current.length() - 1);
        }
        if (expected.startsWith("\"") && expected.endsWith("\"")) {
            expected = expected.substring(1, expected.length() - 1);
        }
        
        return current.equals(expected);
    }
} 