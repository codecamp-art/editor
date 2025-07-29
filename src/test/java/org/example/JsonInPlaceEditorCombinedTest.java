package org.example;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
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
 * Comprehensive tests for {@link JsonInPlaceEditor} covering UTF-8 + BOM, GBK, mixed EOLs,
 * conditional & unconditional replacement/clearing/deletion, InputStream variants and
 * thread-safety.  Each test performs full-string equality on the final content to ensure
 * formatting and comments are preserved exactly.
 */
class JsonInPlaceEditorCombinedTest {

    private static final byte[] UTF8_BOM = {(byte)0xEF,(byte)0xBB,(byte)0xBF};

    /* ---------------------------------------------------------------------
     * UTF-8 + BOM, mixed EOLs, comments
     * --------------------------------------------------------------------- */
    @Test
    @DisplayName("UTF-8 BOM + mixed EOLs – full mutation scenario")
    void utf8BomMixedEol(@TempDir Path tmp) throws IOException {
        String original = "" +
                "// Global comment\r\n" +
                "{\n" +
                "  \"server\" : {\r" +
                "    \"host\" : \"localhost\", // inline\n" +
                "    \"port\" : 8080, // cmt\r\n" +
                "    \"time\" : \"10\", \"address\": \"192.168.1.1\"\r\n" +
                "  },\n" +
                "\n" +
                "  \"database\" : {\r\n" +
                "    \"user\" : \"admin\",\n" +
                "    \"password\" : \"secret\", \"obsolete\" : \"remove\"\n" +
                "  },\n" +
                "\n" +
                "  \"paths\" : { \"root\" : \"/var/www\"}\n" +
                "}\n";

        Path f = tmp.resolve("sample.json");
        try(OutputStream out = Files.newOutputStream(f)){
            out.write(UTF8_BOM);
            out.write(original.getBytes(StandardCharsets.UTF_8));
        }

        // mutations
        JsonInPlaceEditor.setValue(f.toFile(), "server/host", "\"example.com\"");
        JsonInPlaceEditor.setValue(f.toFile(), "server/port", "8080", "9090", null);
        JsonInPlaceEditor.setValue(f.toFile(), "server/time", "10", "11", null);
        JsonInPlaceEditor.setValue(f.toFile(), "database/password", "secret", null, null);
        JsonInPlaceEditor.deleteLine(f.toFile(), "database/obsolete", "remove", null);

        byte[] after4 = Files.readAllBytes(f);
        byte[] after5 = JsonInPlaceEditor.setValue(new ByteArrayInputStream(after4), "paths/root", null, null, null);
        Files.write(f, after5);

        String expected = "" +
                "// Global comment\r\n" +
                "{\n" +
                "  \"server\" : {\r" +
                "    \"host\" : \"example.com\", // inline\n" +
                "    \"port\" : 9090, // cmt\r\n" +
                "    \"time\" : \"11\", \"address\": \"192.168.1.1\"\r\n" +
                "  },\n" +
                "\n" +
                "  \"database\" : {\r\n" +
                "    \"user\" : \"admin\",\n" +
                "    \"password\" : \"\",\n" +
                "  },\n" +
                "\n" +
                "  \"paths\" : { \"root\" : \"\"}\n" +
                "}\n";

        byte[] mutated = Files.readAllBytes(f);
        assertTrue(startsWith(mutated, UTF8_BOM));
        String after = new String(mutated, UTF8_BOM.length, mutated.length-UTF8_BOM.length, StandardCharsets.UTF_8);
        assertEquals(expected, after);

        // Search validations
        assertTrue(JsonInPlaceEditor.search(f.toFile(), "server/host", "example.com"));
        assertFalse(JsonInPlaceEditor.search(f.toFile(), "server/host", "localhost"));
        assertFalse(JsonInPlaceEditor.search(f.toFile(), "database/obsolete")); // key removed
    }

    /* ---------------------------------------------------------------------
     * GBK, mixed EOLs, Chinese
     * --------------------------------------------------------------------- */
    @Test
    @DisplayName("GBK + mixed EOLs – full mutation scenario")
    void gbkMixedEol(@TempDir Path tmp) throws IOException {
        Charset gbk = Charset.forName("GBK");
        String original = "" +
                "// 配置\r\n" +
                "{\n" +
                "  \"服务器\" : {\r" +
                "    \"地址\" : \"本地主机\", // 注释\n" +
                "    \"端口\" : 8080, // 注释\r\n" +
                "    \"时间\" : \"10点\", \"地址\": \"192.168.1.1\"\r\n" +
                "  },\n" +
                "  \"删除\" : \"移除\",\n" +
                "  \"路径\" : { \"根\" : \"/var/www\"\r\n" +
                "  }\n" +
                "}\n";
        Path f = tmp.resolve("cn.json");
        Files.write(f, original.getBytes(gbk));

        // Replace address to "远程" (unconditional)
        JsonInPlaceEditor.setValue(f.toFile(), "服务器/地址", null, "\"远程\"", "GBK");
        JsonInPlaceEditor.setValue(f.toFile(), "服务器/端口", "8080", "9090", "GBK");
        JsonInPlaceEditor.setValue(f.toFile(), "服务器/时间", "10点", "11点", "GBK");
        JsonInPlaceEditor.deleteLine(f.toFile(), "删除", "移除", "GBK");
        JsonInPlaceEditor.setValue(f.toFile(), "路径/根", "/var/www", "/var", "GBK");

        String expected = "" +
                "// 配置\r\n" +
                "{\n" +
                "  \"服务器\" : {\r" +
                "    \"地址\" : \"远程\", // 注释\n" +
                "    \"端口\" : 9090, // 注释\r\n" +
                "    \"时间\" : \"11点\", \"地址\": \"192.168.1.1\"\r\n" +
                "  },\n" +
                "  \"路径\" : { \"根\" : \"/var\"\r\n" +
                "  }\n" +
                "}\n";

        String after = Files.readString(f, gbk);
        assertEquals(expected, after);

        // Search validations (GBK)
        assertTrue(JsonInPlaceEditor.search(f.toFile(), "服务器/地址", "远程", "GBK"));
        assertFalse(JsonInPlaceEditor.search(f.toFile(), "删除", null, "GBK")); // key removed
    }

    /* ---------------------------------------------------------------------
     * InputStream concurrency – thread-safety
     * --------------------------------------------------------------------- */
    @Test
    @DisplayName("InputStream API – concurrent independent mutations")
    void inputStreamConcurrency() throws Exception {
        String original = "{\n  \"a\":1,\n  \"b\":2,\r\n  \"c\":3\r}";
        byte[] bytes = original.getBytes(StandardCharsets.UTF_8);

        String expectedA = original.replace("1", "A");
        String expectedB = original.replace("2", "B");
        String expectedCRemoved = "{\n  \"a\":1,\n  \"b\":2\r\n}"; // trailing comma removed from b after deleting last entry

        List<String> results = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        var exec = Executors.newFixedThreadPool(3);
        exec.execute(() -> { try {
            byte[] out = JsonInPlaceEditor.setValue(new ByteArrayInputStream(bytes), "a", "1", "A", null);
            synchronized(results){results.add(new String(out, StandardCharsets.UTF_8));}
        } catch(IOException ignored){} latch.countDown(); });
        exec.execute(() -> { try {
            byte[] out = JsonInPlaceEditor.setValue(new ByteArrayInputStream(bytes), "b", "2", "B", null);
            synchronized(results){results.add(new String(out, StandardCharsets.UTF_8));}
        } catch(IOException ignored){} latch.countDown(); });
        exec.execute(() -> { try {
            byte[] out = JsonInPlaceEditor.deleteLine(new ByteArrayInputStream(bytes), "c", "3", null);
            synchronized(results){results.add(new String(out, StandardCharsets.UTF_8));}
        } catch(IOException ignored){} latch.countDown(); });

        latch.await(5, TimeUnit.SECONDS);
        exec.shutdownNow();

        assertTrue(results.contains(expectedA));
        assertTrue(results.contains(expectedB));
        assertTrue(results.contains(expectedCRemoved));
    }

    /* ---------------------------------------------------------------------
     * Array path support – UTF-8 BOM with mixed EOLs
     * --------------------------------------------------------------------- */
    @Test
    @DisplayName("UTF-8 BOM – array path nested mutations")
    void arrayPathSupport(@TempDir Path tmp) throws IOException {
        String src = "" +
                "// services list\r\n" +
                "{\n" +
                "  \"services\" : [{ // first\n" +
                "      \"name\" : \"svc1\",\n" +
                "      \"host\" : \"localhost\"\r\n" +
                "    },\n" +
                "    { // second\r\n" +
                "      \"name\" : \"svc2\",\n" +
                "      \"host\" : \"example.com\"\r" +
                "    }\n" +
                "  ],\r\n" +
                "  \"dummy\" : 1\n" +
                "}\n";

        Path f = tmp.resolve("array.json");
        try(OutputStream out = Files.newOutputStream(f)){
            out.write(UTF8_BOM);
            out.write(src.getBytes(StandardCharsets.UTF_8));
        }

        // Replace second service host
        JsonInPlaceEditor.setValue(f.toFile(), "services/1/host", "\"example.com\"", "\"remote.com\"");
        // Clear first service name (unconditional)
        JsonInPlaceEditor.setValue(f.toFile(), "services/0/name", null, null);
        // Delete dummy key entirely
        JsonInPlaceEditor.deleteLine(f.toFile(), "dummy", null, null);

        String expected = "" +
                "// services list\r\n" +
                "{\n" +
                "  \"services\" : [{ // first\n" +
                "      \"name\" : \"\",\n" +
                "      \"host\" : \"localhost\"\r\n" +
                "    },\n" +
                "    { // second\r\n" +
                "      \"name\" : \"svc2\",\n" +
                "      \"host\" : \"remote.com\"\r" +
                "    }\n" +
                "  ]\r\n" +
                "}\n";

        byte[] mutated = Files.readAllBytes(f);
        assertTrue(startsWith(mutated, UTF8_BOM));
        String after = new String(mutated, UTF8_BOM.length, mutated.length-UTF8_BOM.length, StandardCharsets.UTF_8);
        assertEquals(expected, after);

        // Search validations – nested array paths
        assertTrue(JsonInPlaceEditor.search(f.toFile(), "services/1/host", "remote.com"));
        assertTrue(JsonInPlaceEditor.search(f.toFile(), "services/0/name")); // path exists (value cleared)
        assertFalse(JsonInPlaceEditor.search(f.toFile(), "dummy"));
    }

    /* ------------------------------------------------ helpers ------------------------------------------------ */
    private static boolean startsWith(byte[] arr, byte[] prefix){ if(arr.length<prefix.length) return false; for(int i=0;i<prefix.length;i++){ if(arr[i]!=prefix[i]) return false;} return true; }
} 