package org.example.remotefetch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessBuilderConfigCollectorTest {

    @TempDir
    Path tempDir;

    @Test
    void fetchFileAsStringUsesLinuxCatCommand() throws Exception {
        TestCollector collector = new TestCollector("line-1\nline-2", "");
        RemoteServer server = new RemoteServer("linux", "host", 22, "ops", "secret", RemotePlatform.LINUX);

        String content = collector.fetchFileAsString(server, "/etc/app.conf");

        assertEquals("line-1\nline-2", content);
        assertTrue(collector.lastCommand().contains("cat '/etc/app.conf'"));
        collector.shutdown();
    }

    @Test
    void fetchFileToDiskSupportsWindowsPowerShellRead() throws Exception {
        TestCollector collector = new TestCollector("name=value", "");
        RemoteServer server = new RemoteServer("win", "host", 22, "ops", "secret", RemotePlatform.WINDOWS);

        Path localPath = collector.fetchFileToDisk(server, "C:/ProgramData/MyApp/app.properties", tempDir);

        assertEquals("name=value", Files.readString(localPath));
        assertTrue(collector.lastCommand().contains("powershell -NoProfile -NonInteractive -Command"));
        collector.shutdown();
    }

    @Test
    void listFilesCommandUsesWindowsRecursiveGetChildItem() {
        TestCollector collector = new TestCollector(
                "C:\\ProgramData\\MyApp\\a.conf\nC:\\ProgramData\\MyApp\\notes.txt\n",
                ""
        );
        RemoteServer server = new RemoteServer("win", "host", 22, "ops", "secret", RemotePlatform.WINDOWS);

        List<String> matched;
        try {
            matched = collector.collect(
                    List.of(server),
                    List.of(),
                    List.of(new RemoteSearchTask("C:/ProgramData/MyApp", ".*\\.conf$", FetchMode.READ_TO_MEMORY)),
                    tempDir
            ).get("win").getInMemoryFiles().keySet().stream().toList();
        } finally {
            collector.shutdown();
        }

        assertEquals(1, matched.size());
        assertTrue(matched.get(0).endsWith("a.conf"));
        assertTrue(collector.lastCommand().contains("Get-ChildItem"));
    }

    private static final class TestCollector extends ProcessBuilderConfigCollector {
        private final byte[] stdout;
        private final byte[] stderr;
        private List<String> lastCommand;

        private TestCollector(String stdout, String stderr) {
            super(1);
            this.stdout = stdout.getBytes(StandardCharsets.UTF_8);
            this.stderr = stderr.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        protected CommandResult runCommand(List<String> command, long timeoutMs) throws IOException {
            this.lastCommand = command;
            return new CommandResult(0, stdout, stderr);
        }

        private String lastCommand() {
            return String.join(" ", lastCommand);
        }
    }
}
