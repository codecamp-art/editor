package org.example;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Consolidated test-suite for {@link IniInPlaceEditor}.  It merges the earlier individual
 * classes (IniInPlaceEditorTest, IniInPlaceEditorComprehensiveTest, IniInPlaceEditorEverythingTest)
 * into one file so the project keeps a leaner test directory while still validating:
 *   – UTF-8 + BOM handling with mixed EOLs
 *   – GBK files
 *   – custom line + block comments
 *   – multi-operation edits in a single method
 *   – InputStream variants
 *   – full byte-for-byte equality of the resulting file content
 */
class IniInPlaceEditorAllTest {

    /* ---------------------------------------------------------------------
     * UTF-8 +BOM, mixed EOLs  – multi-operation scenario
     * --------------------------------------------------------------------- */
    @Test
    @DisplayName("UTF-8 BOM file – multiple edits preserve full formatting & BOM")
    void utf8BomMultiOps(@TempDir Path tmp) throws IOException {
        byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

        String body = "" +
                "; Global comment\r\n" +
                "[server]\r\n" +
                "host = localhost ; inline\r\n" +
                "port = 8080    # cmt\n" +
                "\r\n" +
                "[database]\n" +
                "user = admin\n" +
                "password = secret\n" +
                "obsolete = remove\n" +
                "\n" +
                "[paths]\r\n" +
                "root = /var/www\r\n";

        Path f = tmp.resolve("utf8.ini");
        try (OutputStream os = Files.newOutputStream(f)) {
            os.write(BOM);
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        assertTrue(IniInPlaceEditor.pathAndValueExists(f.toFile(), "server/host", "localhost"));
        assertTrue(IniInPlaceEditor.pathAndValueExists(f.toFile(), "server/port", "8080"));
        assertTrue(IniInPlaceEditor.pathAndValueExists(f.toFile(), "database/password", "secret"));
        assertTrue(IniInPlaceEditor.pathAndValueExists(f.toFile(), "database/obsolete"));

        IniInPlaceEditor.setValue(f.toFile(), "server/host", "example.com");
        IniInPlaceEditor.setValue(f.toFile(), "server/port", "9090", "8080", null, null, null);
        IniInPlaceEditor.setValue(f.toFile(), "database/password", "", "secret", null, null, null);
        IniInPlaceEditor.deleteLine(f.toFile(), "database/obsolete", "remove", null, null, null);

        byte[] after4 = Files.readAllBytes(f);
        byte[] after5 = IniInPlaceEditor.setValue(new ByteArrayInputStream(after4), "paths/root", "", null, null, null, null);
        Files.write(f, after5);

        String expected = "" +
                "; Global comment\r\n" +
                "[server]\r\n" +
                "host = example.com ; inline\r\n" +
                "port = 9090    # cmt\n" +
                "\r\n" +
                "[database]\n" +
                "user = admin\n" +
                "password = \n" +
                "\n" +
                "[paths]\r\n" +
                "root = \r\n";

        byte[] bytes = Files.readAllBytes(f);
        assertTrue(startsWith(bytes, BOM));
        String finalBody = new String(bytes, BOM.length, bytes.length - BOM.length, StandardCharsets.UTF_8);
        assertTrue(IniInPlaceEditor.pathAndValueExists(f.toFile(), "server/host", "example.com"));
        assertFalse(IniInPlaceEditor.pathAndValueExists(f.toFile(), "server/host", "localhost"));
        assertEquals(expected, finalBody);
    }

    /* ---------------------------------------------------------------------
     * GBK file  – multi-operation scenario
     * --------------------------------------------------------------------- */
    @Test
    @DisplayName("GBK file – multiple edits preserve formatting")
    void gbkMultiOps(@TempDir Path tmp) throws IOException {
        List<String> lineCmt = List.of("#", "//");
        List<String[]> blk = Collections.singletonList(new String[]{"/*", "*/"});
        Charset gbk = Charset.forName("GBK");
        String body = "// Global comment 名称 = 张三\r\n" +
                "/* blk */\r\n" +
                "/* comment start\n" +
                "comment end */\r\n" +
                "[信息]\r\n" +
                "名称 = 张三 // 名称 = 张三\r\n" +
                "年龄 = 30    # cmt\n" +
                "年龄1=30    # cmt\n" +
                "地址 = 上海\r\n" +
                "地址1=上海\r\n" +
                "空值 = 删除\n";

        Path f = tmp.resolve("gbk.ini");
        Files.write(f, body.getBytes(gbk));

        assertTrue(IniInPlaceEditor.pathAndValueExists(f.toFile(), "信息/名称", "张三", "GBK", lineCmt, blk));
        assertTrue(IniInPlaceEditor.pathAndValueExists(f.toFile(), "信息/年龄", "30", "GBK", lineCmt, blk));
        assertTrue(IniInPlaceEditor.pathAndValueExists(f.toFile(), "信息/地址", "上海", "GBK", lineCmt, blk));
        assertTrue(IniInPlaceEditor.pathAndValueExists(f.toFile(), "信息/地址1", "上海", "GBK", lineCmt, blk));
        assertTrue(IniInPlaceEditor.pathAndValueExists(f.toFile(), "信息/空值", null, "GBK", lineCmt, blk));

        IniInPlaceEditor.setValue(f.toFile(), "信息/名称", "李四", null, "GBK", lineCmt, blk);
        IniInPlaceEditor.setValue(f.toFile(), "信息/年龄", "", "30", "GBK", lineCmt, blk);
        IniInPlaceEditor.setValue(f.toFile(), "信息/年龄1", "", "30", "GBK", lineCmt, blk);      
        IniInPlaceEditor.setValue(f.toFile(), "信息/地址", "北京", "上海", "GBK", lineCmt, blk);
        IniInPlaceEditor.setValue(f.toFile(), "信息/地址1", "北京", "上海", "GBK", lineCmt, blk);
        IniInPlaceEditor.deleteLine(f.toFile(), "信息/空值", null, "GBK", lineCmt, blk);

        String expected = "" +
                "// Global comment 名称 = 张三\r\n" +
                "/* blk */\r\n" +
                "/* comment start\n" +
                "comment end */\r\n" +
                "[信息]\r\n" +
                "名称 = 李四 // 名称 = 张三\r\n" +
                "年龄 =     # cmt\n" + 
                "年龄1=    # cmt\n" + 
                "地址 = 北京\r\n" + 
                "地址1=北京\r\n";

        String content = Files.readString(f, gbk);
        assertTrue(IniInPlaceEditor.pathAndValueExists(f.toFile(), "信息/地址", "北京", "GBK", lineCmt, blk));
        assertEquals(expected, content);
    }

    /* -------------------------------------------------- helpers -------------------------------------------------- */
    private static boolean startsWith(byte[] arr, byte[] prefix) {
        if (arr.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (arr[i] != prefix[i]) return false;
        return true;
    }
} 