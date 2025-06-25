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

class XmlInPlaceEditorCombinedTest {
    private static final byte[] UTF8_BOM = {(byte)0xEF,(byte)0xBB,(byte)0xBF};

    @Test
    @DisplayName("UTF-8 BOM + mixed EOLs + replace/clear/remove – full content assertion")
    void utf8BomMixedEol(@TempDir Path tmp) throws IOException {
        String original =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"+
                "<!-- Comment -->\r\n"+
                "<config>\r\n"+
                "  <server>\r\n"+
                "    <host>localhost</host>\n"+
                "    <port>8080</port>\r"+
                "    <credentials user=\"admin\" pass=\"123\"/>\r\n"+
                "  </server>\r\n"+
                "  <features enabled=\"true\">\n"+
                "    <cache>enabled</cache>\r"+
                "    <debug>true</debug>\r\n"+
                "  </features>\n"+
                "</config>";

        Path f = tmp.resolve("sample.xml");
        try(OutputStream out = Files.newOutputStream(f)){
            out.write(UTF8_BOM); out.write(original.getBytes(StandardCharsets.UTF_8)); }

        // Replace host text
        XmlInPlaceEditor.setValue(f.toFile(), "/config/server/host", "localhost", "prod", null);
        // Replace attribute value
        XmlInPlaceEditor.setValue(f.toFile(), "/config/server/credentials/@pass", "123", "secret", null);
        // Clear cache value
        XmlInPlaceEditor.setValue(f.toFile(), "/config/features/cache", "enabled", null, null);
        // Remove entire debug line
        XmlInPlaceEditor.removeTag(f.toFile(), "/config/features/debug", "true", null);

        String expected =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"+
                "<!-- Comment -->\r\n"+
                "<config>\r\n"+
                "  <server>\r\n"+
                "    <host>prod</host>\n"+
                "    <port>8080</port>\r"+
                "    <credentials user=\"admin\" pass=\"secret\"/>\r\n"+
                "  </server>\r\n"+
                "  <features enabled=\"true\">\n"+
                "    <cache></cache>\r"+
                "  </features>\n"+
                "</config>";

        byte[] mutated = Files.readAllBytes(f);
        assertTrue(startsWith(mutated, UTF8_BOM));
        String after = new String(mutated, UTF8_BOM.length, mutated.length-UTF8_BOM.length, StandardCharsets.UTF_8);
        assertEquals(expected, after);
    }

    @Test
    @DisplayName("GBK + mixed EOLs + replace/clear/remove – full content assertion")
    void gbkMixedEol(@TempDir Path tmp) throws IOException {
        Charset gbk = Charset.forName("GBK");
        String original =
                "<?xml version=\"1.0\" encoding=\"GBK\"?>\r\n"+
                "<!-- 配置 -->\r\n"+
                "<配置>\r\n"+
                "  <服务器>本地</服务器>\n"+
                "  <端口>8080</端口>\r"+
                "  <凭据 用户=\"admin\" 密码=\"123\"/>\r\n"+
                "  <功能 开启=\"是\">\n"+
                "    <缓存>启用</缓存>\r"+
                "    <调试>真</调试>\r\n"+
                "  </功能>\n"+
                "</配置>";
        Path f = tmp.resolve("sample-gbk.xml");
        Files.write(f, original.getBytes(gbk));

        XmlInPlaceEditor.setValue(f.toFile(), "/配置/服务器", "本地", "远程", "GBK");
        XmlInPlaceEditor.setValue(f.toFile(), "/配置/凭据/@密码", "123", "321", "GBK");
        XmlInPlaceEditor.setValue(f.toFile(), "/配置/功能/缓存", "启用", null, "GBK");
        XmlInPlaceEditor.removeTag(f.toFile(), "/配置/功能/调试", "真", "GBK");

        String expected =
                "<?xml version=\"1.0\" encoding=\"GBK\"?>\r\n"+
                "<!-- 配置 -->\r\n"+
                "<配置>\r\n"+
                "  <服务器>远程</服务器>\n"+
                "  <端口>8080</端口>\r"+
                "  <凭据 用户=\"admin\" 密码=\"321\"/>\r\n"+
                "  <功能 开启=\"是\">\n"+
                "    <缓存></缓存>\r"+
                "  </功能>\n"+
                "</配置>";

        String after = Files.readString(f, gbk);
        assertEquals(expected, after);
    }

    @Test
    @DisplayName("InputStream API concurrency – independent edits")
    void inputStreamConcurrency() throws Exception {
        String xml = "<root>\n  <a>1</a>\r\n  <b>2</b>\r  <c>ignored</c>\n</root>";
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);

        String expA = xml.replace("<a>1</a>", "<a>A</a>");
        String expB = xml.replace("<b>2</b>", "<b>B</b>");

        List<String> results = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);
        var exec = Executors.newFixedThreadPool(3);
        exec.execute(() -> { try { byte[] out = XmlInPlaceEditor.setValue(new ByteArrayInputStream(bytes), "/root/a", "1", "A", null); synchronized(results){results.add(new String(out, StandardCharsets.UTF_8));}} catch(IOException ex){} latch.countDown();});
        exec.execute(() -> { try { byte[] out = XmlInPlaceEditor.setValue(new ByteArrayInputStream(bytes), "/root/b", "2", "B", null); synchronized(results){results.add(new String(out, StandardCharsets.UTF_8));}} catch(IOException ex){} latch.countDown();});
        exec.execute(() -> { try { byte[] out = XmlInPlaceEditor.removeTag(new ByteArrayInputStream(bytes), "/root/a", "1", null); synchronized(results){results.add(new String(out, StandardCharsets.UTF_8));}} catch(IOException ex){} latch.countDown();});
        latch.await(5, TimeUnit.SECONDS);
        exec.shutdownNow();

        assertTrue(results.stream().anyMatch(s -> s.equals(expA)));
        assertTrue(results.stream().anyMatch(s -> s.equals(expB)));
        assertTrue(results.stream().anyMatch(s -> !s.contains("<a>1</a>")));
    }

    private static boolean startsWith(byte[] a, byte[] p){ if(a.length<p.length) return false; for(int i=0;i<p.length;i++){ if(a[i]!=p[i]) return false;} return true; }
} 