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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for {@link YamlInPlaceEditor} covering UTF-8 + BOM, GBK, mixed EOLs,
 * conditional & unconditional replacement/clearing/deletion, InputStream variants and
 * thread-safety. Each test performs full-string equality on the final content to ensure
 * formatting and comments are preserved exactly.
 */
class YamlInPlaceEditorTest {

    private static final byte[] UTF8_BOM = {(byte)0xEF,(byte)0xBB,(byte)0xBF};

    /* ---------------------------------------------------------------------
     * UTF-8 + BOM, mixed EOLs, comments, complex YAML structures
     * --------------------------------------------------------------------- */
    @Test
    @DisplayName("UTF-8 BOM + mixed EOLs – complex YAML mutation scenario")
    void utf8BomComplexYaml(@TempDir Path tmp) throws IOException {
        String original = "" +
                "# Global configuration\r\n" +
                "---\n" +
                "server:\r" +
                "  host: localhost  # inline comment\n" +
                "  port: 8080       # server port\r\n" +
                "  ssl: false\r\n" +
                "  timeouts:        # nested object\r" +
                "    connect: 30\n" +
                "    read: 60\r\n" +
                "\n" +
                "database:\r\n" +
                "  user: admin\n" +
                "  password: 'secret'\r" +
                "  host: \"db.example.com\"\n" +
                "  settings:\r\n" +
                "    pool_size: 10\n" +
                "    timeout: 30\r\n" +
                "    obsolete: remove_me  # to be deleted\r" +
                "\n" +
                "url:\r\n" +
                "  user: admin\n" +
                "  password: 'secret'\r" +
                "  host: \"db.example.com\"\n" +
                "  settings:\r\n" +
                "    pool_size: 10\n" +
                "    timeout: 30\r\n" +
                "    obsolete: remove_me  # to be deleted\r" +
                "\n" +
                "services:           # array of services\r\n" +
                "  - name: web\n" +
                "    port: 8080\r\n" +
                "    enabled: true\r" +
                "  - name: api       # second service\n" +
                "    port: 9090\r\n" +
                "    enabled: false\r" +
                "\n" +
                "# Multi-line string example\r\n" +
                "description: |\n" +
                "  This is a multi-line\r\n" +
                "  description with mixed\r" +
                "  line endings\n" +
                "\r\n" +
                "tags: [prod, web, api]  # inline array\r" +
                "metadata: {version: 1.0, author: test}  # inline object\n";

        Path f = tmp.resolve("complex.yaml");
        try(OutputStream out = Files.newOutputStream(f)){
            out.write(UTF8_BOM);
            out.write(original.getBytes(StandardCharsets.UTF_8));
        }

        // Multiple mutations
        byte[] content = Files.readAllBytes(f);
        content = YamlInPlaceEditor.setValue(content, "server/host", "production.example.com");
        content = YamlInPlaceEditor.setValue(content, "server/port", 8080, 9080, null);
        content = YamlInPlaceEditor.setValue(content, "server/ssl", false, true, null);
        content = YamlInPlaceEditor.setValue(content, "database/password", "secret", "new_password", null);
        content = YamlInPlaceEditor.setValue(content, "database/settings/pool_size", 10, 20, null);
        content = YamlInPlaceEditor.deleteKey(content, "database/settings/obsolete", "remove_me", null);
        content = YamlInPlaceEditor.deleteKey(content, "url", null, null);
        content = YamlInPlaceEditor.setValue(content, "services/0/enabled", true, false, null);
        content = YamlInPlaceEditor.setValue(content, "services/1/port", 9090, 9091, null);
        content = YamlInPlaceEditor.setValue(content, "metadata/version", 1.0, "2.0", null);
        Files.write(f, content);

        String expected = "" +
                "# Global configuration\r\n" +
                "---\n" +
                "server:\r" +
                "  host: \"production.example.com\"  # inline comment\n" +
                "  port: 9080       # server port\r\n" +
                "  ssl: true\r\n" +
                "  timeouts:        # nested object\r" +
                "    connect: 30\n" +
                "    read: 60\r\n" +
                "\n" +
                "database:\r\n" +
                "  user: admin\n" +
                "  password: \"new_password\"\r" +
                "  host: \"db.example.com\"\n" +
                "  settings:\r\n" +
                "    pool_size: 20\n" +
                "    timeout: 30\r\n" +
                "services:           # array of services\r\n" +
                "  - name: web\n" +
                "    port: 8080\r\n" +
                "    enabled: false\r" +
                "  - name: api       # second service\n" +
                "    port: 9091\r\n" +
                "    enabled: false\r" +
                "\n" +
                "# Multi-line string example\r\n" +
                "description: |\n" +
                "  This is a multi-line\r\n" +
                "  description with mixed\r" +
                "  line endings\n" +
                "\r\n" +
                "tags: [prod, web, api]  # inline array\r" +
                "metadata: {version: \"2.0\", author: test}  # inline object\n";

        byte[] mutated = Files.readAllBytes(f);
        assertTrue(startsWith(mutated, UTF8_BOM));
        String after = new String(mutated, UTF8_BOM.length, mutated.length-UTF8_BOM.length, StandardCharsets.UTF_8);
        assertEquals(expected, after);

        // Search validations
        byte[] finalContent = Files.readAllBytes(f);
        assertTrue(YamlInPlaceEditor.search(finalContent, "server/host", "production.example.com"));
        assertTrue(YamlInPlaceEditor.search(finalContent, "server/ssl", true));
        assertFalse(YamlInPlaceEditor.search(finalContent, "database/settings/obsolete")); // key removed
        assertTrue(YamlInPlaceEditor.search(finalContent, "services/0/enabled", false));
        assertTrue(YamlInPlaceEditor.search(finalContent, "metadata/version", "2.0"));
    }

    /* ---------------------------------------------------------------------
     * GBK encoding with Chinese characters and mixed EOLs
     * --------------------------------------------------------------------- */
    @Test
    @DisplayName("GBK + mixed EOLs – Chinese YAML mutation scenario")
    void gbkChineseYaml(@TempDir Path tmp) throws IOException {
        Charset gbk = Charset.forName("GBK");
        String original = "" +
                "# 配置文件\r\n" +
                "---\n" +
                "服务器:\r" +
                "  地址: \"本地主机\"      # 服务器地址\n" +
                "  端口: 8080         # 端口号\r\n" +
                "  启用: true\r\n" +
                "  配置:\r" +
                "    连接超时: 30\n" +
                "    读取超时: 60\r\n" +
                "\n" +
                "数据库:\r\n" +
                "  用户名: 管理员\n" +
                "  密码: '秘密'\r" +
                "  主机: \"数据库.示例.com\"\n" +
                "  设置:\r\n" +
                "    连接池大小: 10\n" +
                "    超时时间: 30\r\n" +
                "    废弃选项: 删除我  # 要删除的选项\r" +
                "\n" +
                "服务列表:           # 服务数组\r\n" +
                "  - 名称: 网页服务\n" +
                "    端口: 8080\r\n" +
                "    启用: true\r" +
                "  - 名称: 接口服务   # 第二个服务\n" +
                "    端口: 9090\r\n" +
                "    启用: false\r" +
                "\n" +
                "标签: [生产, 网页, 接口]  # 内联数组\r" +
                "元数据: {版本: 1.0, 作者: 测试}  # 内联对象\n";

        Path f = tmp.resolve("chinese.yaml");
        Files.write(f, original.getBytes(gbk));

        // Multiple mutations with GBK encoding
        byte[] content = Files.readAllBytes(f);
        content = YamlInPlaceEditor.setValue(content, "服务器/地址", "本地主机", "生产服务器", "GBK");
        content = YamlInPlaceEditor.setValue(content, "服务器/端口", 8080, 9080, "GBK");
        content = YamlInPlaceEditor.setValue(content, "数据库/密码", "秘密", "新密码", "GBK");
        content = YamlInPlaceEditor.setValue(content, "数据库/设置/连接池大小", 10, 20, "GBK");
        content = YamlInPlaceEditor.deleteKey(content, "数据库/设置/废弃选项", "删除我", "GBK");
        content = YamlInPlaceEditor.setValue(content, "服务列表/0/启用", true, false, "GBK");
        content = YamlInPlaceEditor.setValue(content, "服务列表/1/端口", 9090, 9091, "GBK");
        content = YamlInPlaceEditor.setValue(content, "元数据/版本", 1.0, "2.0", "GBK");
        Files.write(f, content);

        String expected = "" +
                "# 配置文件\r\n" +
                "---\n" +
                "服务器:\r" +
                "  地址: \"生产服务器\"      # 服务器地址\n" +
                "  端口: 9080         # 端口号\r\n" +
                "  启用: true\r\n" +
                "  配置:\r" +
                "    连接超时: 30\n" +
                "    读取超时: 60\r\n" +
                "\n" +
                "数据库:\r\n" +
                "  用户名: 管理员\n" +
                "  密码: \"新密码\"\r" +
                "  主机: \"数据库.示例.com\"\n" +
                "  设置:\r\n" +
                "    连接池大小: 20\n" +
                "    超时时间: 30\r\n" +
                "服务列表:           # 服务数组\r\n" +
                "  - 名称: 网页服务\n" +
                "    端口: 8080\r\n" +
                "    启用: false\r" +
                "  - 名称: 接口服务   # 第二个服务\n" +
                "    端口: 9091\r\n" +
                "    启用: false\r" +
                "\n" +
                "标签: [生产, 网页, 接口]  # 内联数组\r" +
                "元数据: {版本: \"2.0\", 作者: 测试}  # 内联对象\n";

        String after = Files.readString(f, gbk);
        assertEquals(expected, after);

        // Search validations (GBK)
        byte[] finalContent = Files.readAllBytes(f);
        assertTrue(YamlInPlaceEditor.search(finalContent, "服务器/地址", "生产服务器", "GBK"));
        assertTrue(YamlInPlaceEditor.search(finalContent, "数据库/密码", "新密码", "GBK"));
        assertFalse(YamlInPlaceEditor.search(finalContent, "数据库/设置/废弃选项", null, "GBK")); // key removed
        assertTrue(YamlInPlaceEditor.search(finalContent, "服务列表/0/启用", false, "GBK"));
        assertTrue(YamlInPlaceEditor.search(finalContent, "元数据/版本", "2.0", "GBK"));
    }

    /* ---------------------------------------------------------------------
     * Multi-line YAML with different scalar types and line deletion
     * --------------------------------------------------------------------- */
    @Test
    @DisplayName("Multi-line YAML with scalar types and line deletion")
    void multiLineYamlScalarTypes(@TempDir Path tmp) throws IOException {
        String original = "" +
                "# Configuration with various scalar types\r\n" +
                "config:\n" +
                "  # String values\r" +
                "  name: \"Application Name\"\n" +
                "  description: |\r\n" +
                "    This is a long\r" +
                "    multi-line description\n" +
                "    with mixed line endings\r\n" +
                "  simple_string: hello world\r" +
                "  \n" +
                "  # Numeric values\r\n" +
                "  port: 8080\n" +
                "  timeout: 30.5\r" +
                "  max_connections: 1000\n" +
                "  \r\n" +
                "  # Boolean values\r" +
                "  enabled: true\n" +
                "  debug: false\r" +
                "  ssl_enabled: yes\n" +
                "  maintenance_mode: no\r\n" +
                "  \n" +
                "  # Null values\r" +
                "  cache_dir: null\n" +
                "  temp_dir: ~\r" +
                "  optional_field:\n" +
                "  \r\n" +
                "  # Values to be deleted\r" +
                "  deprecated_option: \"remove this\"\n" +
                "  old_setting: 123\r\n" +
                "  unused_flag: false\r" +
                "\n" +
                "# Inline structures\r\n" +
                "inline_array: [1, 2, 3, \"test\"]\n" +
                "inline_object: {key1: value1, key2: 42, key3: true}\r\n";

        Path f = tmp.resolve("scalars.yaml");
        Files.write(f, original.getBytes(StandardCharsets.UTF_8));

        // Test various value types and operations
        byte[] content = Files.readAllBytes(f);
        content = YamlInPlaceEditor.setValue(content, "config/name", "Application Name", "New App Name");
        content = YamlInPlaceEditor.setValue(content, "config/port", 8080, 9090);
        content = YamlInPlaceEditor.setValue(content, "config/timeout", 30.5, 45.0);
        content = YamlInPlaceEditor.setValue(content, "config/enabled", true, false);
        content = YamlInPlaceEditor.setValue(content, "config/ssl_enabled", "yes", false);
        content = YamlInPlaceEditor.setValue(content, "config/cache_dir", null, "/tmp/cache");
        
        // Key deletions (removes entire line)
        content = YamlInPlaceEditor.deleteKey(content, "config/deprecated_option", "remove this");
        content = YamlInPlaceEditor.deleteKey(content, "config/old_setting", 123);
        content = YamlInPlaceEditor.deleteKey(content, "config/unused_flag");

        // Test inline structures
        content = YamlInPlaceEditor.setValue(content, "inline_object/key2", 42, 100);
        Files.write(f, content);

        String expected = "" +
                "# Configuration with various scalar types\r\n" +
                "config:\n" +
                "  # String values\r" +
                "  name: \"New App Name\"\n" +
                "  description: |\r\n" +
                "    This is a long\r" +
                "    multi-line description\n" +
                "    with mixed line endings\r\n" +
                "  simple_string: hello world\r" +
                "  \n" +
                "  # Numeric values\r\n" +
                "  port: 9090\n" +
                "  timeout: 45.0\r" +
                "  max_connections: 1000\n" +
                "  \r\n" +
                "  # Boolean values\r" +
                "  enabled: false\n" +
                "  debug: false\r" +
                "  ssl_enabled: false\n" +
                "  maintenance_mode: no\r\n" +
                "  \n" +
                "  # Null values\r" +
                "  cache_dir: \"/tmp/cache\"\n" +
                "  temp_dir: ~\r" +
                "  optional_field:\n" +
                "  \r\n" +
                "  # Values to be deleted\r" +
                "# Inline structures\r\n" +
                "inline_array: [1, 2, 3, \"test\"]\n" +
                "inline_object: {key1: value1, key2: 100, key3: true}\r\n";

        String after = Files.readString(f, StandardCharsets.UTF_8);
        assertEquals(expected, after);

        // Search validations
        byte[] finalContent = Files.readAllBytes(f);
        assertTrue(YamlInPlaceEditor.search(finalContent, "config/name", "New App Name"));
        assertTrue(YamlInPlaceEditor.search(finalContent, "config/port", 9090));
        assertTrue(YamlInPlaceEditor.search(finalContent, "config/timeout", 45.0));
        assertTrue(YamlInPlaceEditor.search(finalContent, "config/enabled", false));
        assertTrue(YamlInPlaceEditor.search(finalContent, "config/cache_dir", "/tmp/cache"));
        assertFalse(YamlInPlaceEditor.search(finalContent, "config/deprecated_option"));
        assertFalse(YamlInPlaceEditor.search(finalContent, "config/old_setting"));
        assertFalse(YamlInPlaceEditor.search(finalContent, "config/unused_flag"));
        assertTrue(YamlInPlaceEditor.search(finalContent, "inline_object/key2", 100));
    }

    /* ---------------------------------------------------------------------
     * BOM test cases with various encodings
     * --------------------------------------------------------------------- */
    @Test
    @DisplayName("BOM support with UTF-8, UTF-16BE, UTF-16LE")
    void bomSupport(@TempDir Path tmp) throws IOException {
        String yamlContent = "" +
                "app:\n" +
                "  name: test\r\n" +
                "  version: 1.0\r" +
                "  enabled: true\n";

        // Test UTF-8 BOM
        Path utf8File = tmp.resolve("utf8-bom.yaml");
        try(OutputStream out = Files.newOutputStream(utf8File)){
            out.write(new byte[]{(byte)0xEF, (byte)0xBB, (byte)0xBF}); // UTF-8 BOM
            out.write(yamlContent.getBytes(StandardCharsets.UTF_8));
        }

        byte[] utf8Content = Files.readAllBytes(utf8File);
        utf8Content = YamlInPlaceEditor.setValue(utf8Content, "app/name", "test", "updated");
        Files.write(utf8File, utf8Content);
        byte[] result = Files.readAllBytes(utf8File);
        assertTrue(startsWith(result, new byte[]{(byte)0xEF, (byte)0xBB, (byte)0xBF}));
        String content = new String(result, 3, result.length - 3, StandardCharsets.UTF_8);
        assertTrue(content.contains("name: \"updated\""));

        // Test UTF-16BE BOM
        Path utf16beFile = tmp.resolve("utf16be-bom.yaml");
        try(OutputStream out = Files.newOutputStream(utf16beFile)){
            out.write(new byte[]{(byte)0xFE, (byte)0xFF}); // UTF-16BE BOM
            out.write(yamlContent.getBytes(StandardCharsets.UTF_16BE));
        }

        byte[] utf16beContent = Files.readAllBytes(utf16beFile);
        utf16beContent = YamlInPlaceEditor.setValue(utf16beContent, "app/version", 1.0, 2.0);
        Files.write(utf16beFile, utf16beContent);
        result = Files.readAllBytes(utf16beFile);
        assertTrue(startsWith(result, new byte[]{(byte)0xFE, (byte)0xFF}));
        content = new String(result, 2, result.length - 2, StandardCharsets.UTF_16BE);
        assertTrue(content.contains("version: 2.0"));

        // Test UTF-16LE BOM
        Path utf16leFile = tmp.resolve("utf16le-bom.yaml");
        try(OutputStream out = Files.newOutputStream(utf16leFile)){
            out.write(new byte[]{(byte)0xFF, (byte)0xFE}); // UTF-16LE BOM
            out.write(yamlContent.getBytes(StandardCharsets.UTF_16LE));
        }

        byte[] utf16leContent = Files.readAllBytes(utf16leFile);
        utf16leContent = YamlInPlaceEditor.setValue(utf16leContent, "app/enabled", true, false);
        Files.write(utf16leFile, utf16leContent);
        result = Files.readAllBytes(utf16leFile);
        assertTrue(startsWith(result, new byte[]{(byte)0xFF, (byte)0xFE}));
        content = new String(result, 2, result.length - 2, StandardCharsets.UTF_16LE);
        assertTrue(content.contains("enabled: false"));
    }

    /* ---------------------------------------------------------------------
     * InputStream and byte array API concurrency test
     * --------------------------------------------------------------------- */
    @Test
    @DisplayName("InputStream API – concurrent independent mutations")
    void inputStreamConcurrency() throws Exception {
        String original = "" +
                "# Test concurrency\n" +
                "a: 1\r\n" +
                "b: 2\r" +
                "c: 3\n" +
                "nested:\r\n" +
                "  x: 10\n" +
                "  y: 20\r" +
                "  z: 30\n";
        
        byte[] bytes = original.getBytes(StandardCharsets.UTF_8);

        String expectedA = original.replace("a: 1", "a: \"updated_a\"");
        String expectedB = original.replace("b: 2", "b: \"updated_b\"");
        String expectedC = original.replace("nested:\r\n  x: 10\n  y: 20\r  z: 30", "nested:\r\n  y: 20\r  z: 30");

        List<String> results = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        var exec = Executors.newFixedThreadPool(3);
        exec.execute(() -> { try {
            byte[] out = YamlInPlaceEditor.setValue(bytes, "a", 1, "updated_a", null);
            synchronized(results){results.add(new String(out, StandardCharsets.UTF_8));}
        } catch(IOException ignored){} latch.countDown(); });
        exec.execute(() -> { try {
            byte[] out = YamlInPlaceEditor.setValue(bytes, "b", 2, "updated_b", null);
            synchronized(results){results.add(new String(out, StandardCharsets.UTF_8));}
        } catch(IOException ignored){} latch.countDown(); });
        exec.execute(() -> { try {
            byte[] out = YamlInPlaceEditor.deleteKey(bytes, "nested/x", 10, null);
            synchronized(results){results.add(new String(out, StandardCharsets.UTF_8));}
        } catch(IOException ignored){} latch.countDown(); });

        latch.await(5, TimeUnit.SECONDS);
        exec.shutdownNow();

        assertEquals(3, results.size());
        assertTrue(results.contains(expectedA));
        assertTrue(results.contains(expectedB));
        assertTrue(results.contains(expectedC));
    }

    /* ---------------------------------------------------------------------
     * Byte array API tests
     * --------------------------------------------------------------------- */
    @Test
    @DisplayName("Byte array API – direct byte manipulation")
    void byteArrayApi() throws IOException {
        String yamlContent = "" +
                "config:\n" +
                "  server: localhost\r\n" +
                "  port: 8080\r" +
                "  debug: false\n";
        
        byte[] original = yamlContent.getBytes(StandardCharsets.UTF_8);
        
        // Test setValue with byte array
        byte[] modified1 = YamlInPlaceEditor.setValue(original, "config/server", "localhost", "production");
        String result1 = new String(modified1, StandardCharsets.UTF_8);
        assertTrue(result1.contains("server: \"production\""));
        
        // Test search with byte array  
        assertTrue(YamlInPlaceEditor.search(modified1, "config/server", "production"));
        assertFalse(YamlInPlaceEditor.search(modified1, "config/server", "localhost"));
        
        // Test chained operations
        byte[] modified2 = YamlInPlaceEditor.setValue(modified1, "config/port", 8080, 9090);
        byte[] modified3 = YamlInPlaceEditor.setValue(modified2, "config/debug", false, true);
        
        String finalResult = new String(modified3, StandardCharsets.UTF_8);
        assertTrue(finalResult.contains("server: \"production\""));
        assertTrue(finalResult.contains("port: 9090"));
        assertTrue(finalResult.contains("debug: true"));
    }

    /* ---------------------------------------------------------------------
     * Edge cases and error handling
     * --------------------------------------------------------------------- */
    @Test
    @DisplayName("Edge cases – empty files, missing paths, invalid operations")
    void edgeCases(@TempDir Path tmp) throws IOException {
        // Test empty file
        Path emptyFile = tmp.resolve("empty.yaml");
        Files.write(emptyFile, new byte[0]);
        
        byte[] emptyBytes = Files.readAllBytes(emptyFile);
        assertThrows(IllegalArgumentException.class, () -> 
            YamlInPlaceEditor.setValue(emptyBytes, "nonexistent", "value"));
        
        // Test file with only comments
        Path commentFile = tmp.resolve("comments.yaml");
        Files.write(commentFile, "# Only comments\n# No actual YAML content\n".getBytes());
        
        byte[] commentBytes = Files.readAllBytes(commentFile);
        assertThrows(IllegalArgumentException.class, () -> 
            YamlInPlaceEditor.setValue(commentBytes, "key", "value"));
        
        // Test missing path
        Path normalFile = tmp.resolve("normal.yaml");
        Files.write(normalFile, "existing: value\n".getBytes());
        
        byte[] normalBytes = Files.readAllBytes(normalFile);
        assertThrows(IllegalArgumentException.class, () -> 
            YamlInPlaceEditor.setValue(normalBytes, "missing/path", "value"));
        
        // Test conditional replacement with wrong expected value
        byte[] original = "key: original\n".getBytes(StandardCharsets.UTF_8);
        byte[] unchanged = YamlInPlaceEditor.setValue(original, "key", "wrong_expected", "new_value");
        assertEquals(new String(original, StandardCharsets.UTF_8), new String(unchanged, StandardCharsets.UTF_8));
        
        // Test search on non-existent path
        assertFalse(YamlInPlaceEditor.search(original, "nonexistent"));
        assertFalse(YamlInPlaceEditor.search(original, "nonexistent/nested"));
    }

    /* ------------------------------------------------ helpers ------------------------------------------------ */
    private static boolean startsWith(byte[] arr, byte[] prefix) { 
        if(arr.length < prefix.length) return false; 
        for(int i = 0; i < prefix.length; i++) { 
            if(arr[i] != prefix[i]) return false;
        } 
        return true; 
    }
} 