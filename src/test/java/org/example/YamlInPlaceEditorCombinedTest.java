package org.example;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Consolidated YAML in-place editor tests.
 * <p>
 * Only two test methods are exposed to JUnit:
 *  1. {@link #utf8Scenarios()} – exercises every scenario using UTF-8 input (with and without BOM, mixed EOLs, etc.)
 *  2. {@link #gbkScenarios()}  – exercises the GBK-encoded scenarios.
 * <p>
 * Each scenario is exactly the same as in the former per-feature tests; they are just executed sequentially inside the
 * two methods so the total test count is reduced while coverage stays identical.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class YamlInPlaceEditorCombinedTest {

    @TempDir
    Path tempDir;

    /* --------------------------------------------------
     * Utilities
     * -------------------------------------------------- */
    private File createFile(String name, byte[] content) throws IOException {
        File f = tempDir.resolve(name).toFile();
        Files.write(f.toPath(), content);
        return f;
    }

    private String readAsUtf8(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /* --------------------------------------------------
     * UTF-8 single complex sample covering all scenarios
     * -------------------------------------------------- */
    @Test
    @Order(1)
    @DisplayName("UTF-8 complex sample – edits, deletes, clears, BOM, mixed EOLs, sequences & thread-safety")
    void utf8ComplexSample() throws Exception {
        // Build one big YAML with mixed EOLs and various structures
        String yaml = "# App configuration\r\n" +
                "app:\n" +
                "  name: MyApp\r\n" +
                "  version: 1.0.0\n" +
                "  debug: true\n" +
                "  timeout: 30\r\n" +
                "  database:\n" +
                "    host: localhost\n" +
                "    port: 5432\r\n" +
                "    password: secret\n" +
                "  features:\n" +
                "    - name: auth\n" +
                "      enabled: true\n" +
                "    - name: cache\r\n" +
                "      ttl: 3600\n" +
                "service:\n" +
                "  port: 8080 # to be changed\n";

        // Prepend UTF-8 BOM to the same sample to exercise BOM preservation
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] bytes = yaml.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[bom.length + bytes.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(bytes, 0, withBom, bom.length, bytes.length);

        File file = createFile("complex_utf8.yml", withBom);

        /* Perform a suite of operations */
        // Replace scalar
        YamlInPlaceEditor.setValue(file, "app/version", "2.0.0");
        // Conditional replace
        YamlInPlaceEditor.setValue(file, "app/debug", "true", "false");
        // Clear value
        YamlInPlaceEditor.setValue(file, "app/database/password", "");
        // Replace inside sequence
        YamlInPlaceEditor.setValue(file, "app/features/1/ttl", "7200");
        // Delete a line
        YamlInPlaceEditor.deleteLine(file, "app/features/0/enabled");
        // Replace service port
        YamlInPlaceEditor.setValue(file, "service/port", "9090");

        // Apply sequential edits that were previously concurrent
        YamlInPlaceEditor.setValue(file, "app/timeout", "60");
        YamlInPlaceEditor.setValue(file, "app/name", "MyRenamedApp");

        // Validate
        byte[] result = Files.readAllBytes(file.toPath());
        assertArrayEquals(bom, new byte[]{result[0], result[1], result[2]});
        String expectedUtf8 = "# App configuration\r\n" +
                "app:\n" +
                "  name: MyRenamedApp\r\n" +
                "  version: 2.0.0\n" +
                "  debug: false\n" +
                "  timeout: 60\r\n" +
                "  database:\n" +
                "    host: localhost\n" +
                "    port: 5432\r\n" +
                "    password: \n" +
                "  features:\n" +
                "    - name: auth\n" +
                "    - name: cache\r\n" +
                "      ttl: 7200\n" +
                "service:\n" +
                "  port: 9090 # to be changed\n";

        String txt = new String(result, 3, result.length - 3, StandardCharsets.UTF_8);
        assertEquals(expectedUtf8, txt);
    }

    /* --------------------------------------------------
     * GBK single complex sample mirroring the UTF-8 one
     * -------------------------------------------------- */
    @Test
    @Order(2)
    @DisplayName("GBK complex sample – same edits using encoding hint")
    void gbkComplexSample() throws Exception {
        Charset gbk = Charset.forName("GBK");

        String yaml = "# 应用配置\r\n" +
                "app:\n" +
                "  name: 我的应用\r\n" +
                "  version: 1.0.0\n" +
                "  debug: true\n" +
                "  timeout: 30\r\n" +
                "  database:\n" +
                "    host: 本地主机\n" +
                "    port: 5432\r\n" +
                "    password: 密码\n" +
                "  features:\n" +
                "    - name: 认证\n" +
                "      enabled: true\n" +
                "    - name: 缓存\r\n" +
                "      ttl: 3600\n" +
                "service:\n" +
                "  port: 8080 # 待修改\n";

        File file = createFile("complex_gbk.yml", yaml.getBytes(gbk));

        // Perform the same suite of operations as UTF-8 version, using encoding hint
        YamlInPlaceEditor.setValue(file, "app/version", null, "2.0.0", "GBK");
        YamlInPlaceEditor.setValue(file, "app/debug", "true", "false", "GBK");
        YamlInPlaceEditor.setValue(file, "app/database/password", null, "", "GBK");
        YamlInPlaceEditor.setValue(file, "app/features/1/ttl", null, "7200", "GBK");
        YamlInPlaceEditor.deleteLine(file, "app/features/0/enabled", null, "GBK");
        YamlInPlaceEditor.setValue(file, "service/port", null, "9090", "GBK");

        // Apply sequential edits that were previously concurrent
        YamlInPlaceEditor.setValue(file, "app/timeout", null, "60", "GBK");
        YamlInPlaceEditor.setValue(file, "app/name", null, "新名字应用", "GBK");

        String expectedGbk = "# 应用配置\r\n" +
                "app:\n" +
                "  name: 新名字应用\r\n" +
                "  version: 2.0.0\n" +
                "  debug: false\n" +
                "  timeout: 60\r\n" +
                "  database:\n" +
                "    host: 本地主机\n" +
                "    port: 5432\r\n" +
                "    password: \n" +
                "  features:\n" +
                "    - name: 认证\n" +
                "    - name: 缓存\r\n" +
                "      ttl: 7200\n" +
                "service:\n" +
                "  port: 9090 # 待修改\n";

        String txt = new String(Files.readAllBytes(file.toPath()), gbk);
        assertEquals(expectedGbk, txt);
    }

    /* --------------------------------------------------
     * Thread-safety check using InputStream API (stateless)
     * -------------------------------------------------- */
    @Test
    @Order(3)
    @DisplayName("InputStream API thread safety – concurrent edits on same bytes")
    void inputStreamThreadSafety() throws Exception {
        String baseYaml = "root:\n  a: 1\n  b: 2\n  c: 3\n";
        byte[] original = baseYaml.getBytes(StandardCharsets.UTF_8);

        // Each thread works on its own ByteArrayInputStream of the SAME original bytes
        RunnableTask t1 = new RunnableTask(original, "root/a", "10");
        RunnableTask t2 = new RunnableTask(original, "root/b", "20");
        RunnableTask t3 = new RunnableTask(original, "root/c", "30");

        Thread th1 = new Thread(t1);
        Thread th2 = new Thread(t2);
        Thread th3 = new Thread(t3);
        th1.start(); th2.start(); th3.start();
        th1.join(); th2.join(); th3.join();

        // Verify each thread's result independently contains its expected change
        assertTrue(t1.result.contains("a: 10"));
        assertTrue(t2.result.contains("b: 20"));
        assertTrue(t3.result.contains("c: 30"));
    }

    private static class RunnableTask implements Runnable {
        private final byte[] src;
        private final String path;
        private final String value;
        String result;

        RunnableTask(byte[] src, String path, String value) {
            this.src = src; this.path = path; this.value = value;
        }

        @Override public void run() {
            try {
                byte[] modified = YamlInPlaceEditor.setValue(new ByteArrayInputStream(src), path, value);
                this.result = new String(modified, StandardCharsets.UTF_8);
            } catch (IOException e) {
                result = "";
            }
        }
    }
} 