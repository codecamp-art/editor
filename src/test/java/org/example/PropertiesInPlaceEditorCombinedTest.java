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
 * Comprehensive tests for {@link PropertiesInPlaceEditor}.
 */
class PropertiesInPlaceEditorCombinedTest {

    private static final byte[] UTF8_BOM = {(byte)0xEF,(byte)0xBB,(byte)0xBF};

    @Test
    @DisplayName("UTF-8 BOM + mixed EOLs + all mutation APIs – full content assertion")
    void utf8BomMixedEol(@TempDir Path tmp) throws IOException {
        String original =
                "# Global comment\r\n" +
                " username = admin\r" +
                "password=123   # pass\n" +
                "desc=Line1 \\\n" +
                " Line2\n" +
                "desc2=test1\\\n"+
                " test2 \r\n" +
                "! system comment\r\n" +
                "url:https://example.com\n" +
                "timeout=30  \r" +
                "timeout2=30  \r" +
                "cache=enabled\r\n" +
                "debug=true"; // no EOL

        Path f = tmp.resolve("complex.properties");
        try(OutputStream out = Files.newOutputStream(f)){
            out.write(UTF8_BOM); out.write(original.getBytes(StandardCharsets.UTF_8)); }

        // 1. Replace by key only
        byte[] content = Files.readAllBytes(f);
        content = PropertiesInPlaceEditor.setValue(content, "username", null, "root", null);
        // 2. Replace with expected old value
        content = PropertiesInPlaceEditor.setValue(content, "timeout", "30", "60", null);
        content = PropertiesInPlaceEditor.setValue(content, "timeout2", "regex:.*", "60", null);
        // 3. Clear (remove value) preserving key & line
        content = PropertiesInPlaceEditor.setValue(content, "cache", "enabled", null, null);
        // 4. Remove entire line
        content = PropertiesInPlaceEditor.deleteKey(content, "url", "https://example.com");
        // 5. Replace a multi-line property value
        content = PropertiesInPlaceEditor.setValue(content, "desc", "Line1 Line2", "New1\nNew2", null);
        content = PropertiesInPlaceEditor.setValue(content, "desc2", "test1test2", "test3", null);
        Files.write(f, content);

        String expected =
                "# Global comment\r\n" +
                " username = root\r" +
                "password=123   # pass\n" +
                "desc=New1 \\\n" +
                " New2\n" +
                "desc2=test3\r\n"+
                "! system comment\r\n" +
                "timeout=60  \r" +
                "timeout2=60  \r" +
                "cache=\r\n" +
                "debug=true";

        byte[] mutated = Files.readAllBytes(f);
        assertTrue(startsWith(mutated, UTF8_BOM));
        String after = new String(mutated, UTF8_BOM.length, mutated.length-UTF8_BOM.length, StandardCharsets.UTF_8);
        assertEquals(expected, after);
    }

    @Test
    @DisplayName("GBK + mixed EOLs + all mutation APIs – full content assertion")
    void gbkMixedEol(@TempDir Path tmp) throws IOException {
        Charset gbk = Charset.forName("GBK");
        String original =
                "# 配置说明\r\n" +
                "用户名=张三\r" +
                "密码 : 123 # 密码\n" +
                "描述=第一行 \\\n" +
                " 第二行\n" +
                "! 注释\r\n" +
                "地址=北京\r" +
                "超时 = 30\r\n" +
                "缓存=启用\n" +
                "调试=true";

        Path f = tmp.resolve("complex-cn.properties");
        Files.write(f, original.getBytes(gbk));

        // mutations
        byte[] content = Files.readAllBytes(f);
        content = PropertiesInPlaceEditor.setValue(content, "用户名", null, "李四", "GBK");
        content = PropertiesInPlaceEditor.setValue(content, "超时", "30", "60", "GBK");
        content = PropertiesInPlaceEditor.setValue(content, "缓存", "启用", null, "GBK");
        content = PropertiesInPlaceEditor.deleteKey(content, "GBK", "地址", "北京");
        // 5. Replace multi-line property in GBK file
        content = PropertiesInPlaceEditor.setValue(content, "描述", null, "新1\n新2", "GBK");
        Files.write(f, content);

        String expected =
                "# 配置说明\r\n" +
                "用户名=李四\r" +
                "密码 : 123 # 密码\n" +
                "描述=新1 \\\n" +
                " 新2\n" +
                "! 注释\r\n" +
                "超时 = 60\r\n" +
                "缓存=\n" +
                "调试=true";

        String after = Files.readString(f, gbk);
        assertEquals(expected, after);
    }

    @Test
    @DisplayName("InputStream API – concurrent mutations are independent and thread-safe")
    void inputStreamConcurrency() throws Exception {
        String original = "a=1\nb=2\rc=3\r\n";
        byte[] bytes = original.getBytes(StandardCharsets.UTF_8);

        String expectedA = original.replace("a=1", "a=A");
        String expectedB = original.replace("b=2", "b=B");
        String expectedCRemoved = "a=1\nb=2\r"; // entire line c removed (no final CRLF)

        List<String> results = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        var exec = Executors.newFixedThreadPool(3);
        exec.execute(() -> { try { byte[] out = PropertiesInPlaceEditor.setValue(bytes, "a", null, "A", null);
            synchronized(results){results.add(new String(out, StandardCharsets.UTF_8));}} catch(IOException ignored){} latch.countDown(); });
        exec.execute(() -> { try { byte[] out = PropertiesInPlaceEditor.setValue(bytes, "b", "2", "B", null);
            synchronized(results){results.add(new String(out, StandardCharsets.UTF_8));}} catch(IOException ignored){} latch.countDown(); });
        exec.execute(() -> { try { byte[] out = PropertiesInPlaceEditor.deleteKey(bytes, "c", "3");
            synchronized(results){results.add(new String(out, StandardCharsets.UTF_8));}} catch(IOException ignored){} latch.countDown(); });

        latch.await(5, TimeUnit.SECONDS);
        exec.shutdownNow();

        assertTrue(results.contains(expectedA));
        assertTrue(results.contains(expectedB));
        assertTrue(results.contains(expectedCRemoved));
    }

    private static boolean startsWith(byte[] arr, byte[] prefix){ if(arr.length<prefix.length) return false; for(int i=0;i<prefix.length;i++){ if(arr[i]!=prefix[i]) return false;} return true; }
} 