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
 * Comprehensive tests for {@link TextInPlaceEditor} covering:
 * UTF-8 BOM, GBK, mixed EOLs, InputStream API, thread-safety, full-content assertions.
 */
class TextInPlaceEditorCombinedTest {

    // UTF-8 BOM bytes for convenience
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    @Test
    @DisplayName("UTF-8 BOM + mixed EOLs + replace + removeLine + File API – full content assertion")
    void utf8BomMixedEolReplaceRemove(@TempDir Path tmp) throws IOException {
        // Original complex sample with mixed EOLs and no final EOL
        String original =
                "# Settings\r\n" +           // CRLF
                "user: alice\r" +              // CR
                "password: secret\n" +        // LF
                "# Data\r\n" +               // CRLF
                "path = /var/data\n" +        // LF
                "enable = true\r" +           // CR
                "port = 8080\r\n" +          // CRLF
                "notes: This is a test";       // no EOL

        // Write the file with BOM
        Path file = tmp.resolve("sample.txt");
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(UTF8_BOM);
            out.write(original.getBytes(StandardCharsets.UTF_8));
        }

        // 1) Replace literal (user)
        TextInPlaceEditor.replace(file.toFile(), "user: alice", "user: bob", false);
        // 2) Replace regex (port)
        TextInPlaceEditor.replace(file.toFile(), "port\\s*=\\s*\\d+", "port = 9090", true);
        // 3) Remove line literal (enable)
        TextInPlaceEditor.removeLine(file.toFile(), "enable = true", false);
        // 4) Remove line via regex (password)
        TextInPlaceEditor.removeLine(file.toFile(), "password\\s*:\\s*\\w+", true);

        // Build expected content
        String expected =
                "# Settings\r\n" +
                "user: bob\r" +
                "# Data\r\n" +
                "path = /var/data\n" +
                "port = 9090\r\n" +
                "notes: This is a test";

        byte[] mutated = Files.readAllBytes(file);
        assertTrue(startsWith(mutated, UTF8_BOM), "BOM must be preserved");
        String after = new String(mutated, UTF8_BOM.length, mutated.length - UTF8_BOM.length, StandardCharsets.UTF_8);
        assertEquals(expected, after, "Content must match exactly after edits");
    }

    @Test
    @DisplayName("GBK encoding + mixed EOLs + replace + removeLine + File API – full content assertion")
    void gbkMixedEolReplaceRemove(@TempDir Path tmp) throws IOException {
        Charset gbk = Charset.forName("GBK");
        String original =
                "# 应用配置\r\n" +           // CRLF
                "用户: 张三\r" +                 // CR
                "密码: 123456\n" +              // LF
                "# 系统\r\n" +                 // CRLF
                "路径 = C:/data\n" +            // LF
                "缓存 = 启用\r" +                // CR
                "端口 = 8080\r\n" +             // CRLF
                "备注: 完成";                    // no EOL

        Path file = tmp.resolve("sample-gbk.txt");
        Files.write(file, original.getBytes(gbk));

        // 1) Replace literal (user)
        TextInPlaceEditor.replaceWithEncoding(file.toFile(), "GBK", "张三", "李四", false);
        // 2) Replace regex (port)
        TextInPlaceEditor.replaceWithEncoding(file.toFile(), "GBK", "端口\\s*=\\s*\\d+", "端口 = 9090", true);
        // 3) Remove line literal (缓存)
        TextInPlaceEditor.removeLineWithEncoding(file.toFile(), "GBK", "缓存 = 启用", false);
        // 4) Remove line via regex (密码)
        TextInPlaceEditor.removeLineWithEncoding(file.toFile(), "GBK", "密码\\s*:\\s*.*", true);

        String expected =
                "# 应用配置\r\n" +
                "用户: 李四\r" +
                "# 系统\r\n" +
                "路径 = C:/data\n" +
                "端口 = 9090\r\n" +
                "备注: 完成";

        String after = Files.readString(file, gbk);
        assertEquals(expected, after, "GBK content must match exactly after edits");
    }

    @Test
    @DisplayName("InputStream API – concurrent thread-safety and correctness")
    void inputStreamThreadSafety() throws Exception {
        String original = "alpha=1\nbeta=2\r\ngamma=3\r";
        byte[] bytes = original.getBytes(StandardCharsets.UTF_8);

        // Prepare expected results per mutation
        String expectedAlpha = original.replace("alpha=1", "alpha=A");
        String expectedBeta  = original.replace("beta=2", "beta=B");
        // For gamma removal we must remove its line (including trailing CR)
        String expectedGamma = "alpha=1\nbeta=2"; // gamma line and trailing EOL removed

        List<Runnable> tasks = new ArrayList<>();
        List<String> results = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        tasks.add(() -> {
            try {
                byte[] out = TextInPlaceEditor.replace(bytes, "alpha=1", "alpha=A", false, "UTF-8");
                synchronized (results) { results.add(new String(out, StandardCharsets.UTF_8)); }
            } catch (IOException ignored) {}
            latch.countDown();
        });
        tasks.add(() -> {
            try {
                byte[] out = TextInPlaceEditor.replace(bytes, "beta=2", "beta=B", false, "UTF-8");
                synchronized (results) { results.add(new String(out, StandardCharsets.UTF_8)); }
            } catch (IOException ignored) {}
            latch.countDown();
        });
        tasks.add(() -> {
            try {
                byte[] out = TextInPlaceEditor.removeLine(bytes, "gamma=3", false, "UTF-8");
                synchronized (results) { results.add(new String(out, StandardCharsets.UTF_8)); }
            } catch (IOException ignored) {}
            latch.countDown();
        });

        var exec = Executors.newFixedThreadPool(3);
        tasks.forEach(exec::execute);
        latch.await(5, TimeUnit.SECONDS);
        exec.shutdownNow();

        assertTrue(results.contains(expectedAlpha), "Result of alpha replacement missing");
        assertTrue(results.contains(expectedBeta),  "Result of beta replacement missing");
        assertTrue(results.contains(expectedGamma), "Result of gamma line removal missing");
    }

    // helper
    private static boolean startsWith(byte[] array, byte[] prefix) {
        if (array.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (array[i] != prefix[i]) return false;
        }
        return true;
    }
} 