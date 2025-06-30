package org.example;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for {@link JsonFileComparator} covering encoding, BOM, mixed EOLs, exclusions and concurrency.
 */
class JsonFileComparatorTest {

    private static final byte[] UTF8_BOM = {(byte)0xEF,(byte)0xBB,(byte)0xBF};

    /* --------------------------------------------------
     * UTF-8 with BOM, mixed EOLs, comments & empty lines
     * -------------------------------------------------- */
    @Test
    @DisplayName("UTF-8 complex diff including BOM, comments, mixed EOLs & exclusions")
    void utf8ComplexDiff(@TempDir Path tmp) throws Exception {
        String leftJson = "// Application config\r\n"+
                "{\n"+
                "  \"name\": \"MyApp\", // end-line comment\r\n"+
                "  \"version\": \"1.0\",\n"+
                "  \"features\": [ \n"+
                "    { \"id\": 1, \"enabled\": true },\r\n"+
                "    { \"id\": 2, \"enabled\": false }\n"+
                "  ],\n"+
                "  \"nested\": {\r\n"+
                "     \"a\": 1,\r\n\n"+ // extra empty line
                "     \"b\": 2\n"+
                "  }\n"+
                "}\n";
        // Right JSON – change some fields & structure
        String rightJson = "// Application config\r\n"+
                "{\n"+
                "  \"name\": \"MyRenamedApp\",\r\n"+
                "  \"version\": \"2.0\",\n"+
                "  \"features\": [ \n"+
                "    { \"id\": 1, \"enabled\": true },\r\n"+
                "    { \"id\": 2, \"enabled\": true },\n"+
                "    { \"id\": 3, \"enabled\": false }\n"+
                "  ],\n"+
                "  \"nested\": {\r\n"+
                "     \"a\": 10,\n"+
                "     \"c\": 3\n"+
                "  }\n"+
                "}\n";

        // write with BOM
        Path f1 = tmp.resolve("left.json");
        Path f2 = tmp.resolve("right.json");
        Files.write(f1, concat(UTF8_BOM, leftJson.getBytes(StandardCharsets.UTF_8)));
        Files.write(f2, concat(UTF8_BOM, rightJson.getBytes(StandardCharsets.UTF_8)));

        List<List<String>> exclusion = List.of(
                Arrays.asList("features[1].enabled", "false", "true") // ignore that particular change
        );

        List<List<String>> diff = JsonFileComparator.diff(f1.toFile(), f2.toFile(), exclusion, null);

        // verify expected paths
        assertTrue(diff.contains(List.of("name", "MyApp", "MyRenamedApp")));
        assertTrue(diff.contains(List.of("version", "1.0", "2.0")));
        assertTrue(diff.contains(Arrays.asList("features[2]", null, "{\"id\":3,\"enabled\":false}")));
        assertTrue(diff.contains(List.of("nested.a", "1", "10")));
        assertTrue(diff.contains(Arrays.asList("nested.b", "2", null)));
        assertTrue(diff.contains(Arrays.asList("nested.c", null, "3")));

        // ensure exclusion really excluded
        assertFalse(diff.stream().anyMatch(r -> r.get(0).equals("features[1].enabled")));
    }

    /* --------------------
     * GBK encoded content
     * -------------------- */
    @Test
    @DisplayName("GBK diff with Chinese characters & mixed changes")
    void gbkDiff(@TempDir Path tmp) throws Exception {
        Charset gbk = Charset.forName("GBK");
        String left = "{\n  \"名称\": \"应用\", \"版本\": \"1.0\" }";
        String right = "{\n  \"名称\": \"新应用\", \"版本\": \"2.0\", \"描述\": \"测试\" }";
        Path l = tmp.resolve("l.json");
        Path r = tmp.resolve("r.json");
        Files.write(l, left.getBytes(gbk));
        Files.write(r, right.getBytes(gbk));

        List<List<String>> diff = JsonFileComparator.diff(l.toFile(), r.toFile(), null, "GBK");
        assertTrue(diff.contains(List.of("名称", "应用", "新应用")));
        assertTrue(diff.contains(List.of("版本", "1.0", "2.0")));
        assertTrue(diff.contains(Arrays.asList("描述", null, "测试")));
    }

    /* ------------------
     * Concurrency check
     * ------------------ */
    @Test
    @DisplayName("Concurrent comparisons are independent & thread-safe")
    void threadSafety() throws Exception {
        String json1 = "{\"a\":1}";
        String json2 = "{\"a\":2}";
        byte[] b1 = json1.getBytes(StandardCharsets.UTF_8);
        byte[] b2 = json2.getBytes(StandardCharsets.UTF_8);

        List<List<String>> expected = List.of(Arrays.asList("a", "1", "2"));

        CountDownLatch latch = new CountDownLatch(10);
        var exec = Executors.newFixedThreadPool(10);
        List<List<List<String>>> results = Collections.synchronizedList(new ArrayList<>());

        for(int i=0;i<10;i++){
            exec.execute(() -> {
                try{
                    List<List<String>> diff = JsonFileComparator.diff(new ByteArrayInputStream(b1), new ByteArrayInputStream(b2));
                    results.add(diff);
                } catch(IOException ignored){}
                latch.countDown();
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        exec.shutdownNow();

        assertEquals(10, results.size());
        for(List<List<String>> res : results){
            assertEquals(expected, res);
        }
    }

    /* util */
    private static byte[] concat(byte[] a, byte[] b){
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a,0,out,0,a.length);
        System.arraycopy(b,0,out,a.length,b.length);
        return out;
    }
} 