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

class ProcessBuilderRemoteFileFetcherTest {

    @TempDir
    Path tempDir;

    @Test
    void fetchAsStringUsesLinuxCatCommand() throws Exception {
        TestFetcher fetcher = new TestFetcher("line-1\nline-2", "");
        RemoteServer server = new RemoteServer("linux", "host", 22, "ops", "secret", RemotePlatform.LINUX);

        String content = fetcher.fetchAsString(server, "/etc/myapp/app.conf");

        assertEquals("line-1\nline-2", content);
        assertTrue(fetcher.lastCommand().contains("cat '/etc/myapp/app.conf'"));
    }

    @Test
    void fetchToDiskUsesWindowsPowershellRead() throws Exception {
        TestFetcher fetcher = new TestFetcher("name=value", "");
        RemoteServer server = new RemoteServer("win", "host", 22, "ops", "secret", RemotePlatform.WINDOWS);

        Path localPath = fetcher.fetchToDisk(server, "C:/ProgramData/MyApp/app.properties", tempDir);

        assertEquals("name=value", Files.readString(localPath));
        assertTrue(fetcher.lastCommand().contains("powershell -NoProfile -NonInteractive -Command"));
    }

    private static final class TestFetcher extends ProcessBuilderRemoteFileFetcher {
        private final byte[] stdout;
        private final byte[] stderr;
        private List<String> lastCommand;

        private TestFetcher(String stdout, String stderr) {
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
