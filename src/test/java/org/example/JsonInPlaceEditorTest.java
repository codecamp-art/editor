package org.example;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for JsonInPlaceEditor covering:
 * - UTF-8 with BOM
 * - GBK encoding with Chinese characters
 * - Mixed line endings (CRLF, CR, LF)
 * - Comments preservation
 * - Nested paths and arrays
 * - Multiple key-value pairs on same line
 * - Full content comparison
 */
class JsonInPlaceEditorTest {

    private static final byte[] UTF8_BOM = {(byte)0xEF, (byte)0xBB, (byte)0xBF};

    @Test
    @DisplayName("UTF-8 BOM with mixed EOLs - Complex nested structure")
    void testUtf8BomMixedEol(@TempDir Path tempDir) throws IOException {
        String original = "// Configuration file\r\n" +
                "{\n" +
                "  \"server\": {\r" +
                "    \"host\": \"localhost\", // server host\n" +
                "    \"port\": 8080, /* port number */\r\n" +
                "    \"ssl\": true, \"timeout\": 30\r\n" +
                "  },\n" +
                "  \"database\": {\r\n" +
                "    \"url\": \"jdbc:mysql://localhost:3306/db\",\n" +
                "    \"user\": \"admin\", \"password\": \"secret\"\r" +
                "  },\n" +
                "  \"features\": [\r\n" +
                "    {\"name\": \"auth\", \"enabled\": true},\n" +
                "    {\"name\": \"logging\", \"enabled\": false}\r" +
                "  ],\n" +
                "  \"version\": \"1.0\"\r\n" +
                "}\n";

        Path file = tempDir.resolve("config.json");
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(UTF8_BOM);
            out.write(original.getBytes(StandardCharsets.UTF_8));
        }

        // Test setValue operations
        byte[] content = Files.readAllBytes(file);
        content = JsonInPlaceEditor.setValue(content, "server/host", "example.com");
        content = JsonInPlaceEditor.setValue(content, "server/port", 8080, 9090);
        content = JsonInPlaceEditor.setValue(content, "database/password", "secret", null); // Clear value
        content = JsonInPlaceEditor.setValue(content, "features/0/enabled", true, false);
        
        // Test deleteLine
        content = JsonInPlaceEditor.deleteKey(content, "server/ssl", true);
        Files.write(file, content);

        String expected = "// Configuration file\r\n" +
                "{\n" +
                "  \"server\": {\r" +
                "    \"host\": \"example.com\", // server host\n" +
                "    \"port\": 9090, /* port number */\r\n" +
                "    \"timeout\": 30\r\n" +
                "  },\n" +
                "  \"database\": {\r\n" +
                "    \"url\": \"jdbc:mysql://localhost:3306/db\",\n" +
                "    \"user\": \"admin\", \"password\": null\r" +
                "  },\n" +
                "  \"features\": [\r\n" +
                "    {\"name\": \"auth\", \"enabled\": false},\n" +
                "    {\"name\": \"logging\", \"enabled\": false}\r" +
                "  ],\n" +
                "  \"version\": \"1.0\"\r\n" +
                "}\n";

        byte[] result = Files.readAllBytes(file);
        assertTrue(startsWith(result, UTF8_BOM));
        String actualContent = new String(result, UTF8_BOM.length, result.length - UTF8_BOM.length, StandardCharsets.UTF_8);
        assertEquals(expected, actualContent);

        // Test search operations
        byte[] finalContent = Files.readAllBytes(file);
        assertTrue(JsonInPlaceEditor.search(finalContent, "server/host", "example.com"));
        assertFalse(JsonInPlaceEditor.search(finalContent, "server/host", "localhost"));
        assertTrue(JsonInPlaceEditor.search(finalContent, "database/user"));
        assertFalse(JsonInPlaceEditor.search(finalContent, "server/ssl")); // Deleted
        assertTrue(JsonInPlaceEditor.search(finalContent, "features/1/name", "logging"));
    }

    @Test
    @DisplayName("GBK encoding with Chinese characters and mixed EOLs")
    void testGbkChineseMixedEol(@TempDir Path tempDir) throws IOException {
        Charset gbk = Charset.forName("GBK");
        String original = "// 配置文件\r\n" +
                "{\n" +
                "  \"服务器\": {\r" +
                "    \"主机\": \"本地主机\", // 服务器地址\n" +
                "    \"端口\": 8080, /* 端口号 */\r\n" +
                "    \"启用SSL\": true, \"超时\": 30\r\n" +
                "  },\n" +
                "  \"数据库\": {\r\n" +
                "    \"连接\": \"jdbc:mysql://localhost:3306/数据库\",\n" +
                "    \"用户名\": \"管理员\", \"密码\": \"机密\"\r" +
                "  },\n" +
                "  \"功能列表\": [\r\n" +
                "    {\"名称\": \"认证\", \"启用\": true},\n" +
                "    {\"名称\": \"日志\", \"启用\": false}\r" +
                "  ],\n" +
                "  \"版本\": \"1.0\"\r\n" +
                "}\n";

        Path file = tempDir.resolve("配置.json");
        Files.write(file, original.getBytes(gbk));

        // Test operations with GBK encoding
        byte[] content = Files.readAllBytes(file);
        content = JsonInPlaceEditor.setValue(content, "服务器/主机", null, "远程服务器", "GBK");
        content = JsonInPlaceEditor.setValue(content, "服务器/端口", 8080, 9090, "GBK");
        content = JsonInPlaceEditor.setValue(content, "数据库/密码", "机密", "新密码", "GBK");
        content = JsonInPlaceEditor.setValue(content, "功能列表/0/启用", true, false, "GBK");
        content = JsonInPlaceEditor.deleteKey(content, "服务器/启用SSL", null, "GBK");
        Files.write(file, content);

        String expected = "// 配置文件\r\n" +
                "{\n" +
                "  \"服务器\": {\r" +
                "    \"主机\": \"远程服务器\", // 服务器地址\n" +
                "    \"端口\": 9090, /* 端口号 */\r\n" +
                "    \"超时\": 30\r\n" +
                "  },\n" +
                "  \"数据库\": {\r\n" +
                "    \"连接\": \"jdbc:mysql://localhost:3306/数据库\",\n" +
                "    \"用户名\": \"管理员\", \"密码\": \"新密码\"\r" +
                "  },\n" +
                "  \"功能列表\": [\r\n" +
                "    {\"名称\": \"认证\", \"启用\": false},\n" +
                "    {\"名称\": \"日志\", \"启用\": false}\r" +
                "  ],\n" +
                "  \"版本\": \"1.0\"\r\n" +
                "}\n";

        String actual = Files.readString(file, gbk);
        assertEquals(expected, actual);

        // Test search with GBK
        byte[] finalContent = Files.readAllBytes(file);
        assertTrue(JsonInPlaceEditor.search(finalContent, "服务器/主机", "远程服务器", "GBK"));
        assertTrue(JsonInPlaceEditor.search(finalContent, "功能列表/1/名称", "日志", "GBK"));
        assertFalse(JsonInPlaceEditor.search(finalContent, "服务器/启用SSL", null, "GBK"));
    }

    @Test
    @DisplayName("InputStream operations with complex JSON")
    void testInputStreamOperations() throws IOException {
        String original = "{\n" +
                "  \"app\": {\n" +
                "    \"name\": \"MyApp\",\n" +
                "    \"settings\": {\n" +
                "      \"theme\": \"dark\",\n" +
                "      \"language\": \"en\"\n" +
                "    }\n" +
                "  },\n" +
                "  \"users\": [\n" +
                "    {\"id\": 1, \"name\": \"Alice\"},\n" +
                "    {\"id\": 2, \"name\": \"Bob\"}\n" +
                "  ]\n" +
                "}";

        byte[] bytes = original.getBytes(StandardCharsets.UTF_8);

        // Test setValue via byte array
        byte[] result1 = JsonInPlaceEditor.setValue(
            bytes, 
            "app/settings/theme", 
            "dark", 
            "light"
        );
        
        String modified1 = new String(result1, StandardCharsets.UTF_8);
        assertTrue(modified1.contains("\"theme\": \"light\""));
        assertFalse(modified1.contains("\"theme\": \"dark\""));

        // Test array element modification
        byte[] result2 = JsonInPlaceEditor.setValue(
            result1,
            "users/1/name",
            "Bob",
            "Charlie"
        );

        String modified2 = new String(result2, StandardCharsets.UTF_8);
        assertTrue(modified2.contains("\"name\": \"Charlie\""));
        assertFalse(modified2.contains("\"name\": \"Bob\""));

        // Test search via byte array
        assertTrue(JsonInPlaceEditor.search(
            result2,
            "app/name",
            "MyApp"
        ));
        
        assertTrue(JsonInPlaceEditor.search(
            result2,
            "users/0/id",
            1
        ));
    }

    @Test
    @DisplayName("Non-formatted JSON with multiple values per line")
    void testNonFormattedJson(@TempDir Path tempDir) throws IOException {
        String original = "{\"a\":1,\"b\":{\"c\":2,\"d\":3},\"e\":[{\"f\":4},{\"g\":5}],\"h\":6}";
        
        Path file = tempDir.resolve("compact.json");
        Files.writeString(file, original);

        // Modify nested values
        byte[] content = Files.readAllBytes(file);
        content = JsonInPlaceEditor.setValue(content, "b/c", 2, 20);
        content = JsonInPlaceEditor.setValue(content, "e/1/g", 5, 50);
        Files.write(file, content);
        
        String expected = "{\"a\":1,\"b\":{\"c\":20,\"d\":3},\"e\":[{\"f\":4},{\"g\":50}],\"h\":6}";
        String actual = Files.readString(file);
        assertEquals(expected, actual);

        // Test search on compact JSON
        byte[] finalContent = Files.readAllBytes(file);
        assertTrue(JsonInPlaceEditor.search(finalContent, "b/d", 3));
        assertTrue(JsonInPlaceEditor.search(finalContent, "e/0/f", 4));
    }

    @Test
    @DisplayName("Comments preservation with various formats")
    void testCommentsPreservation(@TempDir Path tempDir) throws IOException {
        String original = "{\n" +
                "  // Line comment\n" +
                "  \"key1\": \"value1\", // Inline comment\n" +
                "  /* Block comment */\n" +
                "  \"key2\": \"value2\",\n" +
                "  /* Multi-line\n" +
                "     block comment */\n" +
                "  \"key3\": \"value3\" /* Another inline */\n" +
                "}";

        Path file = tempDir.resolve("comments.json");
        Files.writeString(file, original);

        byte[] content = Files.readAllBytes(file);
        content = JsonInPlaceEditor.setValue(content, "key2", "value2", "newValue2");
        Files.write(file, content);

        String result = Files.readString(file);
        
        // Verify all comments are preserved
        assertTrue(result.contains("// Line comment"));
        assertTrue(result.contains("// Inline comment"));
        assertTrue(result.contains("/* Block comment */"));
        assertTrue(result.contains("/* Multi-line\n     block comment */"));
        assertTrue(result.contains("/* Another inline */"));
        
        // Verify the value was changed
        assertTrue(result.contains("\"key2\": \"newValue2\""));
    }

    @Test
    @DisplayName("Conditional updates and deletions")
    void testConditionalOperations(@TempDir Path tempDir) throws IOException {
        String original = "{\n" +
                "  \"status\": \"active\",\n" +
                "  \"count\": 10,\n" +
                "  \"enabled\": true\n" +
                "}";

        Path file = tempDir.resolve("conditional.json");
        Files.writeString(file, original);

        // Conditional update - should succeed
        byte[] content = Files.readAllBytes(file);
        content = JsonInPlaceEditor.setValue(content, "status", "active", "inactive");
        Files.write(file, content);
        String result1 = Files.readString(file);
        assertTrue(result1.contains("\"status\": \"inactive\""));

        // Conditional update - should fail (wrong expected value)
        content = Files.readAllBytes(file);
        content = JsonInPlaceEditor.setValue(content, "count", "5", "20");
        Files.write(file, content);
        String result2 = Files.readString(file);
        assertTrue(result2.contains("\"count\": 10")); // Unchanged

        // Conditional delete - should succeed
        content = Files.readAllBytes(file);
        content = JsonInPlaceEditor.deleteKey(content, "enabled", true);
        Files.write(file, content);
        String result3 = Files.readString(file);
        assertFalse(result3.contains("\"enabled\""));
    }

    // Helper method
    private static boolean startsWith(byte[] array, byte[] prefix) {
        if (array.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (array[i] != prefix[i]) return false;
        }
        return true;
    }
} 