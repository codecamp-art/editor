package org.example.remotefetch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessBuilderConfigCollectorTest {

    @TempDir
    Path tempDir;

    @Test
    void fetchFileAsStringUsesNativeSftp() throws Exception {
        TestCollector collector = new TestCollector(Map.of("/etc/app.conf", "line-1\nline-2"));
        RemoteServer server = RemoteServer.withKerberos(
                "linux",
                "host",
                22,
                "ops",
                "ops@EXAMPLE.COM",
                RemotePlatform.LINUX
        );

        String content = collector.fetchFileAsString(server, "/etc/app.conf");

        assertEquals("line-1\nline-2", content);
        assertTrue(collector.sftpCommands().get(0).contains("sftp"));
        assertTrue(collector.sftpBatchLines().get(0).stream().anyMatch(line -> line.contains("\"/etc/app.conf\"")));
        assertTrue(collector.sftpCommands().get(0).contains("PreferredAuthentications=gssapi-with-mic"));
        collector.shutdown();
    }

    @Test
    void fetchFileToDiskSupportsWindowsCandidatePaths() throws Exception {
        TestCollector collector = new TestCollector(Map.of(
                "/C:/ProgramData/MyApp/app.properties", "name=value"
        ));
        RemoteServer server = RemoteServer.withKerberos(
                "win",
                "host",
                22,
                "ops",
                "ops@EXAMPLE.COM",
                RemotePlatform.WINDOWS
        );

        Path localPath = collector.fetchFileToDisk(server, "C:/ProgramData/MyApp/app.properties", tempDir);

        assertEquals("name=value", Files.readString(localPath));
        assertTrue(collector.sftpBatchLines().get(0).stream().anyMatch(line -> line.contains("\"/c/ProgramData/MyApp/app.properties\"")));
        assertTrue(collector.sftpBatchLines().get(0).stream().anyMatch(line -> line.contains("\"/C:/ProgramData/MyApp/app.properties\"")));
        assertTrue(collector.sftpBatchLines().get(0).stream().anyMatch(line -> line.contains("\"C:/ProgramData/MyApp/app.properties\"")));
        collector.shutdown();
    }

    @Test
    void collectUsesOneSftpSessionPerServerAndServerScopedLocalPaths() throws Exception {
        TestCollector collector = new TestCollector(Map.of(
                "/etc/app.conf", "linux",
                "/C:/ProgramData/MyApp/app.properties", "windows"
        ));

        RemoteFetchRequest linuxRequest = new RemoteFetchRequest(
                RemoteServer.withKerberos("linux-a", "linux-host", 22, "ops", "ops@EXAMPLE.COM", RemotePlatform.LINUX),
                List.of(new RemoteFileTask("/etc/app.conf", FetchMode.SAVE_TO_DISK))
        );
        RemoteFetchRequest windowsRequest = new RemoteFetchRequest(
                RemoteServer.withKerberos("win-a", "win-host", 22, "ops", "ops@EXAMPLE.COM", RemotePlatform.WINDOWS),
                List.of(new RemoteFileTask("C:/ProgramData/MyApp/app.properties", FetchMode.SAVE_TO_DISK))
        );

        Map<String, FetchResult> result = collector.collect(List.of(linuxRequest, windowsRequest), tempDir);

        assertEquals(2, collector.sftpCommands().size());
        assertEquals(
                tempDir.resolve("linux-a").resolve("etc/app.conf"),
                result.get("linux-a").getLocalFiles().get("/etc/app.conf")
        );
        assertEquals(
                tempDir.resolve("win-a").resolve("c/ProgramData/MyApp/app.properties"),
                result.get("win-a").getLocalFiles().get("C:/ProgramData/MyApp/app.properties")
        );
        assertEquals("linux", Files.readString(result.get("linux-a").getLocalFiles().get("/etc/app.conf")));
        assertEquals("windows", Files.readString(result.get("win-a").getLocalFiles().get("C:/ProgramData/MyApp/app.properties")));
        collector.shutdown();
    }

    @Test
    void collectUsesSshOnlyForSearchDiscoveryThenDownloadsViaSftp() throws Exception {
        TestCollector collector = new TestCollector(Map.of(
                "/etc/myapp/app.conf", "content"
        ));
        collector.setSearchOutput("/etc/myapp/app.conf\n/etc/myapp/readme.txt\n");

        RemoteServer server = RemoteServer.withKerberos(
                "linux-a",
                "linux-host",
                22,
                "ops",
                "ops@EXAMPLE.COM",
                RemotePlatform.LINUX
        );

        Map<String, FetchResult> result = collector.collect(
                List.of(server),
                List.of(),
                List.of(new RemoteSearchTask("/etc/myapp", ".*\\.conf$", FetchMode.READ_TO_MEMORY)),
                tempDir
        );

        assertEquals(1, collector.sshCommands().size());
        assertEquals(1, collector.sftpCommands().size());
        assertEquals(
                "content",
                new String(result.get("linux-a").getInMemoryFiles().get("/etc/myapp/app.conf"), StandardCharsets.UTF_8)
        );
        collector.shutdown();
    }

    private static final class TestCollector extends ProcessBuilderConfigCollector {
        private final Map<String, byte[]> remoteFiles = new LinkedHashMap<>();
        private final List<List<String>> sftpCommands = new ArrayList<>();
        private final List<List<String>> sshCommands = new ArrayList<>();
        private final List<List<String>> sftpBatchLines = new ArrayList<>();
        private String searchOutput = "";

        private TestCollector(Map<String, String> remoteFiles) {
            super(2);
            remoteFiles.forEach((path, content) -> this.remoteFiles.put(path, content.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        protected CommandResult runCommand(List<String> command, long timeoutMs) throws IOException {
            if (command.contains("sftp")) {
                sftpCommands.add(command);
                Path batchFile = Path.of(command.get(command.indexOf("-b") + 1));
                List<String> lines = Files.readAllLines(batchFile, StandardCharsets.UTF_8);
                sftpBatchLines.add(lines);
                for (String line : lines) {
                    simulateDownload(line);
                }
                return new CommandResult(0, new byte[0], new byte[0]);
            }

            sshCommands.add(command);
            return new CommandResult(0, searchOutput.getBytes(StandardCharsets.UTF_8), new byte[0]);
        }

        private void setSearchOutput(String searchOutput) {
            this.searchOutput = searchOutput;
        }

        private void simulateDownload(String line) throws IOException {
            String[] parts = extractQuotedTokens(line);
            if (parts.length != 2) {
                return;
            }

            byte[] content = remoteFiles.get(parts[0]);
            if (content == null) {
                return;
            }

            Path localPath = Path.of(parts[1]);
            if (localPath.getParent() != null) {
                Files.createDirectories(localPath.getParent());
            }
            Files.write(localPath, content);
        }

        private String[] extractQuotedTokens(String line) {
            List<String> tokens = new ArrayList<>();
            int cursor = 0;
            while (cursor < line.length()) {
                int start = line.indexOf('"', cursor);
                if (start < 0) {
                    break;
                }
                int end = line.indexOf('"', start + 1);
                if (end < 0) {
                    break;
                }
                tokens.add(line.substring(start + 1, end));
                cursor = end + 1;
            }
            return tokens.toArray(String[]::new);
        }

        private List<List<String>> sftpCommands() {
            return sftpCommands;
        }

        private List<List<String>> sshCommands() {
            return sshCommands;
        }

        private List<List<String>> sftpBatchLines() {
            return sftpBatchLines;
        }
    }
}
