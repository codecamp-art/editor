package org.example;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileMaskerTest {

    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("masker-test-");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted((a, b) -> b.compareTo(a)) // delete children first
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    @Test
    void testIniMasking() throws IOException {
        String iniContent = "[db]\npassword=secret\nuser=root\n";
        Path iniFile = tempDir.resolve("config.ini");
        Files.writeString(iniFile, iniContent, StandardCharsets.UTF_8);

        FileMasker.mask(iniFile.toFile(), "ini", List.of("db/password"));

        String masked = Files.readString(iniFile, StandardCharsets.UTF_8);
        assertTrue(masked.contains("password=" + FileMasker.MASK), "Password should be masked");
        assertFalse(masked.contains("secret"), "Original secret should be removed");
    }

    @Test
    void testYamlMasking() throws IOException {
        String yamlContent = "root:\n  child:\n    key: secretValue\n";
        Path yamlFile = tempDir.resolve("config.yaml");
        Files.writeString(yamlFile, yamlContent, StandardCharsets.UTF_8);

        FileMasker.mask(yamlFile.toFile(), "yaml", List.of("root/child/key"));

        String masked = Files.readString(yamlFile, StandardCharsets.UTF_8);
        assertTrue(masked.contains("key: \"" + FileMasker.MASK + "\""));
        assertFalse(masked.contains("secretValue"));
    }

    @Test
    void testPlainTextRegexMasking() throws IOException {
        String plainContent = "token=abc123\npass=secret\n";
        Path txtFile = tempDir.resolve("plain.txt");
        Files.writeString(txtFile, plainContent, StandardCharsets.UTF_8);

        // regex for token value and pass value
        List<String> regexes = List.of("token=\\w+", "pass=\\w+");
        FileMasker.mask(txtFile.toFile(), "txt", regexes);

        String masked = Files.readString(txtFile, StandardCharsets.UTF_8);
        assertTrue(masked.contains("token=" + FileMasker.MASK));
        assertTrue(masked.contains("pass=" + FileMasker.MASK));
        assertFalse(masked.contains("abc123"));
        assertFalse(masked.contains("secret"));
    }
} 