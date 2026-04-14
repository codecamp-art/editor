package org.example.remotefetch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessBuilderRemoteFileFetcherTest {

    @TempDir
    Path tempDir;

    @Test
    void fetchAsStringUsesSftpBatch() throws Exception {
        TestFetcher fetcher = new TestFetcher(Map.of("/etc/myapp/app.conf", "line-1\nline-2"));
        RemoteServer server = RemoteServer.withKerberos(
                "linux",
                "host",
                22,
                "ops",
                "ops@EXAMPLE.COM",
                RemotePlatform.LINUX
        );

        String content = fetcher.fetchAsString(server, "/etc/myapp/app.conf");

        assertEquals("line-1\nline-2", content);
        assertTrue(fetcher.lastCommand().contains("sftp"));
        assertTrue(fetcher.batchLines().stream().anyMatch(line -> line.contains("\"/etc/myapp/app.conf\"")));
        fetcher.shutdown();
    }

    @Test
    void fetchToDiskUsesWindowsPathMapping() throws Exception {
        TestFetcher fetcher = new TestFetcher(Map.of("/C:/ProgramData/MyApp/app.properties", "name=value"));
        RemoteServer server = RemoteServer.withKerberos(
                "win",
                "host",
                22,
                "ops",
                "ops@EXAMPLE.COM",
                RemotePlatform.WINDOWS
        );

        Path localPath = fetcher.fetchToDisk(server, "C:/ProgramData/MyApp/app.properties", tempDir);

        assertEquals("name=value", Files.readString(localPath));
        assertTrue(fetcher.batchLines().stream().anyMatch(line -> line.contains("\"/C:/ProgramData/MyApp/app.properties\"")));
        fetcher.shutdown();
    }

    private static final class TestFetcher extends ProcessBuilderRemoteFileFetcher {
        private final Map<String, byte[]> remoteFiles;
        private List<String> lastCommand = List.of();
        private List<String> batchLines = List.of();

        private TestFetcher(Map<String, String> remoteFiles) {
            this.remoteFiles = remoteFiles.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue().getBytes(StandardCharsets.UTF_8)
                    ));
        }

        @Override
        protected CommandResult runCommand(List<String> command, long timeoutMs) throws IOException {
            this.lastCommand = command;
            Path batchFile = Path.of(command.get(command.indexOf("-b") + 1));
            this.batchLines = Files.readAllLines(batchFile, StandardCharsets.UTF_8);
            for (String line : batchLines) {
                simulateDownload(line);
            }
            return new CommandResult(0, new byte[0], new byte[0]);
        }

        private void simulateDownload(String line) throws IOException {
            List<String> quoted = new ArrayList<>();
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
                quoted.add(line.substring(start + 1, end));
                cursor = end + 1;
            }

            if (quoted.size() != 2) {
                return;
            }

            byte[] content = remoteFiles.get(quoted.get(0));
            if (content == null) {
                return;
            }

            Path localPath = Path.of(quoted.get(1));
            if (localPath.getParent() != null) {
                Files.createDirectories(localPath.getParent());
            }
            Files.write(localPath, content);
        }

        private String lastCommand() {
            return String.join(" ", lastCommand);
        }

        private List<String> batchLines() {
            return batchLines;
        }
    }
}
