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
                "  },\n" +
                "\n" +
                "  \"database\" : {\r\n" +
                "    \"user\" : \"admin\",\n" +
                "    \"password\" : \"secret\",\r" +
                "    \"obsolete\" : \"remove\"\n" +
                "  },\n" +
                "\n" +
                "  \"paths\" : {\r\n" +
                "    \"root\" : \"/var/www\"\r\n" +
                "  }\n" +
                "}\n";

        Path f = tmp.resolve("sample.json");
        try(OutputStream out = Files.newOutputStream(f)){
            out.write(UTF8_BOM);
            out.write(original.getBytes(StandardCharsets.UTF_8));
        }

        // mutations
        JsonInPlaceEditor.setValue(f.toFile(), "server/host", "\"example.com\"");
        JsonInPlaceEditor.setValue(f.toFile(), "server/port", "8080", "9090", null);
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
                "  },\n" +
                "\n" +
                "  \"database\" : {\r\n" +
                "    \"user\" : \"admin\",\n" +
                "    \"password\" : \"\",\r" +
                "  },\n" +
                "\n" +
                "  \"paths\" : {\r\n" +
                "    \"root\" : \"\"\r\n" +
                "  }\n" +
                "}\n";

        byte[] mutated = Files.readAllBytes(f);
        assertTrue(startsWith(mutated, UTF8_BOM));
        String after = new String(mutated, UTF8_BOM.length, mutated.length-UTF8_BOM.length, StandardCharsets.UTF_8);
        assertEquals(expected, after);
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
                "  },\n" +
                "  \"删除\" : \"移除\",\n" +
                "  \"路径\" : {\r\n" +
                "    \"根\" : \"/var/www\"\r\n" +
                "  }\n" +
                "}\n";
        Path f = tmp.resolve("cn.json");
        Files.write(f, original.getBytes(gbk));

        // Replace address to "远程" (unconditional)
        JsonInPlaceEditor.setValue(f.toFile(), "服务器/地址", null, "\"远程\"", "GBK");
        JsonInPlaceEditor.setValue(f.toFile(), "服务器/端口", "8080", "9090", "GBK");
        JsonInPlaceEditor.deleteLine(f.toFile(), "删除", "移除", "GBK");
        JsonInPlaceEditor.setValue(f.toFile(), "路径/根", null, null, "GBK");

        String expected = "" +
                "// 配置\r\n" +
                "{\n" +
                "  \"服务器\" : {\r" +
                "    \"地址\" : \"远程\", // 注释\n" +
                "    \"端口\" : 9090, // 注释\r\n" +
                "  },\n" +
                "  \"路径\" : {\r\n" +
                "    \"根\" : \"\"\r\n" +
                "  }\n" +
                "}\n";

        String after = Files.readString(f, gbk);
        assertEquals(expected, after);
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
                "  \"services\" : [\r" +
                "    { // first\n" +
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
                "  \"services\" : [\r" +
                "    { // first\n" +
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
    }

    /* ------------------------------------------------ helpers ------------------------------------------------ */
    private static boolean startsWith(byte[] arr, byte[] prefix){ if(arr.length<prefix.length) return false; for(int i=0;i<prefix.length;i++){ if(arr[i]!=prefix[i]) return false;} return true; }
} 