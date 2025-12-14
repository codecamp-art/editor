package org.example;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class XmlInPlaceEditorTest {

    private static final byte[] UTF8_BOM = {(byte)0xEF,(byte)0xBB,(byte)0xBF};

    /* ------------------------------------------------------------------ */
    @Test
    @DisplayName("UTF-8 BOM + mixed EOLs – full mutation")
    void utf8BomMixedEol(@TempDir Path dir) throws IOException {
        String xml = "" +
                "<!-- Global -->\r\n"+
                "<config>\n"+
                "  <server host=\"localhost\" port=\"8080\">\r"+
                "    <timeout>10</timeout> <!-- comment -->\n"+
                "  </server>\n"+
                "  <url host=\"localhost\" port=\"8080\">\r"+
                "    <timeout>10</timeout> <!-- comment -->\n"+
                "  </url>\n"+
                "  <database user=\"admin\" password=\"secret\">removeMe</database>\r\n"+
                "  <paths root=\"/var/www\"/>\n"+
                "</config>\n";

        Path f = dir.resolve("cfg.xml");
        try(OutputStream out = Files.newOutputStream(f)){
            out.write(UTF8_BOM); out.write(xml.getBytes(StandardCharsets.UTF_8)); }

        // mutate
        XmlInPlaceEditor.setValue(f.toFile(), "config/server/@host", null, "example.com");
        XmlInPlaceEditor.setValue(f.toFile(), "config/server/@port", "8080", "9090");
        XmlInPlaceEditor.setValue(f.toFile(), "config/server/timeout", "10", "11");
        XmlInPlaceEditor.deleteTag(f.toFile(), "config/database", null);
        XmlInPlaceEditor.deleteTag(f.toFile(), "config/url", null);

        String expected = "" +
                "<!-- Global -->\r\n"+
                "<config>\n"+
                "  <server host=\"example.com\" port=\"9090\">\r"+
                "    <timeout>11</timeout> <!-- comment -->\n"+
                "  </server>\n"+
                "  <paths root=\"/var/www\"/>\n"+
                "</config>\n";

        byte[] resultBytes = Files.readAllBytes(f);
        assertTrue(startsWith(resultBytes, UTF8_BOM));
        String result = new String(resultBytes, UTF8_BOM.length, resultBytes.length-UTF8_BOM.length, StandardCharsets.UTF_8);
        assertEquals(expected, result);

        assertTrue(XmlInPlaceEditor.search(f.toFile(), "config/server/@host", "example.com"));
        assertFalse(XmlInPlaceEditor.search(f.toFile(), "config/database"));
    }

    /* ------------------------------------------------------------------ */
    @Test
    @DisplayName("GBK + mixed EOLs – Chinese characters")
    void gbkMixed(@TempDir Path dir) throws IOException {
        Charset gbk = Charset.forName("GBK");
        String xml = ""+
                "<!-- 配置文件 -->\r\n"+
                "<配置>\n"+
                "  <服务器 \n" +
                " 地址=\"本地\" 端口=\"8080\">\r"+
                "    <超时>30</超时>\n"+
                "  </服务器>\n"+
                "  <地址 \n" +
                " 地址=\"本地\" 端口=\"8080\">\r"+
                "    <超时>30</超时>\n"+
                "  </地址>\n"+
                "  <路径 根=\"/data\"/>\n"+
                "</配置>\n";
        Path f = dir.resolve("配置.xml");
        Files.write(f, xml.getBytes(gbk));

        XmlInPlaceEditor.setValue(f.toFile(), "配置/服务器/@地址", null, "远程", "GBK");
        XmlInPlaceEditor.setValue(f.toFile(), "配置/服务器/@端口", "8080", "9090", "GBK");
        XmlInPlaceEditor.setValue(f.toFile(), "配置/服务器/超时", "30", "60", "GBK");
        XmlInPlaceEditor.deleteTag(f.toFile(), "配置/地址", null, "GBK");

        String expected = ""+
                "<!-- 配置文件 -->\r\n"+
                "<配置>\n"+
                "  <服务器 \n" +
                " 地址=\"远程\" 端口=\"9090\">\r"+
                "    <超时>60</超时>\n"+
                "  </服务器>\n"+
                "  <路径 根=\"/data\"/>\n"+
                "</配置>\n";
        String res = Files.readString(f, gbk);
        assertEquals(expected, res);
        assertTrue(XmlInPlaceEditor.search(f.toFile(), "配置/服务器/@地址", "远程", "GBK"));
    }

    /* ------------------------------------------------------------------ */
    @Test
    @DisplayName("InputStream mutations – attribute vs text")
    void inputStreamOps() throws Exception {
        String xml = "<root><a x=\"1\">val</a><b>old</b></root>";
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);

        byte[] r1 = XmlInPlaceEditor.setValue(new ByteArrayInputStream(bytes), "root/a/@x", "1", "2", null);
        byte[] r2 = XmlInPlaceEditor.setValue(new ByteArrayInputStream(r1), "root/b", "old", "new", null);

        String expected = "<root><a x=\"2\">val</a><b>new</b></root>";
        assertEquals(expected, new String(r2, StandardCharsets.UTF_8));
        assertTrue(XmlInPlaceEditor.search(new ByteArrayInputStream(r2), "root/a/@x", "2", null));
    }

    /* ------------------------------------------------------------------ */
    @Test
    @DisplayName("Compact one-line XML mutations")
    void compactXml(@TempDir Path dir) throws IOException {
        String xml = "<x><y z=\"1\">a</y><y z=\"2\">b</y></x>";
        Path f = dir.resolve("c.xml"); Files.writeString(f, xml);

        XmlInPlaceEditor.setValue(f.toFile(), "x/y/@z", "1", "10");
        XmlInPlaceEditor.setValue(f.toFile(), "x/y[2]", "b", "bb"); // second y tweak not supported index, expect unchanged
        XmlInPlaceEditor.deleteTag(f.toFile(), "x/y", null); // deletes first y only (first match)

        String res = Files.readString(f);
        assertEquals("<x><y z=\"2\">b</y></x>", res);
    }

    /* ------------------------------------------------------------------ */
    @Test
    @DisplayName("Delete tag spanning multiple lines & multiple tags same line")
    void multiLineAndSameLine(@TempDir Path dir) throws IOException {
        String xml = "<root>\n  <item>\n    <sub>val</sub>\n  </item>\n  <a></a><b></b>\n</root>";
        Path f = dir.resolve("m.xml"); Files.writeString(f, xml);

        // delete multi-line item
        XmlInPlaceEditor.deleteTag(f.toFile(), "root/item", null);
        // delete single <a> that shares line with <b>
        XmlInPlaceEditor.deleteTag(f.toFile(), "root/a", null);

        String expected = "<root>\n  <b></b>\n</root>";
        assertEquals(expected, Files.readString(f));
    }

    /* ------------------------------------------------------------------ */
    private static boolean startsWith(byte[] arr, byte[] prefix){ if(arr.length<prefix.length) return false; for(int i=0;i<prefix.length;i++){ if(arr[i]!=prefix[i]) return false;} return true; }
} 