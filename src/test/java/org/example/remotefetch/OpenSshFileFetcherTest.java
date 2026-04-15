package org.example.remotefetch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenSshFileFetcherTest {

    @TempDir
    Path tempDir;

    @Test
    void searchFilesByGlob_linux_returnsMatchedPaths() throws Exception {
        RecordingExecutor executor = new RecordingExecutor((command, timeout) -> {
            assertEquals("ssh", command.get(0));
            String remoteCommand = command.get(command.size() - 1);
            assertTrue(remoteCommand.contains("find"));
            assertTrue(remoteCommand.contains("*.xml"));

            String stdout = "/opt/app/a.xml\n/opt/app/b.xml\n";
            return new ExecResult(0, stdout.getBytes(StandardCharsets.UTF_8), new byte[0]);
        });

        OpenSshFileFetcher fetcher = new OpenSshFileFetcher(
                tempDir.resolve("mux").toString(),
                Duration.ofSeconds(10),
                false,
                executor
        );

        SshTarget target = new SshTarget("linux1.example.com", 22, "user", RemoteOs.LINUX);

        List<String> result = fetcher.searchFilesByGlob(target, "/opt/app", "*.xml");

        assertEquals(List.of("/opt/app/a.xml", "/opt/app/b.xml"), result);
        assertEquals(1, executor.commands.size());
    }

    @Test
    void searchFilesByGlob_windows_returnsMatchedPaths() throws Exception {
        RecordingExecutor executor = new RecordingExecutor((command, timeout) -> {
            assertEquals("ssh", command.get(0));
            String remoteCommand = command.get(command.size() - 1);
            assertTrue(remoteCommand.contains("powershell"));
            assertTrue(remoteCommand.contains("EncodedCommand"));

            String stdout = "/d:/应用/配置/a.xml\n/d:/应用/配置/b.xml\n";
            return new ExecResult(0, stdout.getBytes(StandardCharsets.UTF_8), new byte[0]);
        });

        OpenSshFileFetcher fetcher = new OpenSshFileFetcher(
                tempDir.resolve("mux").toString(),
                Duration.ofSeconds(10),
                false,
                executor
        );

        SshTarget target = new SshTarget("server.ab.cd.com", 22, "ABC\\user", RemoteOs.WINDOWS_OPENSSH);

        List<String> result = fetcher.searchFilesByGlob(target, "D:\\应用\\配置", "*.xml");

        assertEquals(List.of("/d:/应用/配置/a.xml", "/d:/应用/配置/b.xml"), result);
        assertEquals(1, executor.commands.size());
    }

    @Test
    void downloadFiles_whenAllExistAndSftpSucceeds_returnsDownloadedFiles() throws Exception {
        Path localRoot = tempDir.resolve("download");

        RecordingExecutor executor = new RecordingExecutor((command, timeout) -> {
            if ("ssh".equals(command.get(0))) {
                // existence check
                String stdout =
                        "EXISTS\t/opt/app/a.properties\n" +
                        "EXISTS\t/opt/app/b.xml\n";
                return new ExecResult(0, stdout.getBytes(StandardCharsets.UTF_8), new byte[0]);
            }

            if ("sftp".equals(command.get(0))) {
                // simulate successful download by creating expected files locally
                Path a = localRoot.resolve("opt/app/a.properties");
                Path b = localRoot.resolve("opt/app/b.xml");
                Files.createDirectories(a.getParent());
                Files.createDirectories(b.getParent());
                Files.writeString(a, "a");
                Files.writeString(b, "b");
                return new ExecResult(0, new byte[0], new byte[0]);
            }

            fail("Unexpected command: " + command);
            return null;
        });

        OpenSshFileFetcher fetcher = new OpenSshFileFetcher(
                tempDir.resolve("mux").toString(),
                Duration.ofSeconds(10),
                false,
                executor
        );

        SshTarget target = new SshTarget("linux1.example.com", 22, "user", RemoteOs.LINUX);

        DownloadResult result = fetcher.downloadFiles(
                target,
                List.of("/opt/app/a.properties", "/opt/app/b.xml"),
                localRoot
        );

        assertTrue(result.missing().isEmpty());
        assertTrue(result.failed().isEmpty());
        assertEquals(2, result.downloaded().size());
        assertTrue(Files.exists(localRoot.resolve("opt/app/a.properties")));
        assertTrue(Files.exists(localRoot.resolve("opt/app/b.xml")));

        assertEquals(2, executor.commands.size());
        assertEquals("ssh", executor.commands.get(0).get(0));
        assertEquals("sftp", executor.commands.get(1).get(0));
    }

    @Test
    void downloadFiles_whenSomePathsMissing_shouldNotSendMissingOnesToSftp() throws Exception {
        Path localRoot = tempDir.resolve("download");

        RecordingExecutor executor = new RecordingExecutor((command, timeout) -> {
            if ("ssh".equals(command.get(0))) {
                String stdout =
                        "EXISTS\t/opt/app/a.properties\n" +
                        "MISSING\t/opt/app/missing.xml\n";
                return new ExecResult(0, stdout.getBytes(StandardCharsets.UTF_8), new byte[0]);
            }

            if ("sftp".equals(command.get(0))) {
                Path downloaded = localRoot.resolve("opt/app/a.properties");
                Files.createDirectories(downloaded.getParent());
                Files.writeString(downloaded, "ok");
                return new ExecResult(0, new byte[0], new byte[0]);
            }

            fail("Unexpected command: " + command);
            return null;
        });

        OpenSshFileFetcher fetcher = new OpenSshFileFetcher(
                tempDir.resolve("mux").toString(),
                Duration.ofSeconds(10),
                false,
                executor
        );

        SshTarget target = new SshTarget("linux1.example.com", 22, "user", RemoteOs.LINUX);

        DownloadResult result = fetcher.downloadFiles(
                target,
                List.of("/opt/app/a.properties", "/opt/app/missing.xml"),
                localRoot
        );

        assertEquals(List.of("/opt/app/missing.xml"), result.missing());
        assertTrue(result.failed().isEmpty());
        assertEquals(1, result.downloaded().size());
        assertTrue(result.downloaded().containsKey("/opt/app/a.properties"));

        assertEquals(2, executor.commands.size());
        List<String> sftpCommand = executor.commands.get(1);
        assertEquals("sftp", sftpCommand.get(0));

        Path batchFile = Path.of(sftpCommand.get(sftpCommand.indexOf("-b") + 1));
        String batchContent = Files.readString(batchFile, StandardCharsets.UTF_8);
        assertTrue(batchContent.contains("/opt/app/a.properties"));
        assertFalse(batchContent.contains("/opt/app/missing.xml"));
    }

    @Test
    void downloadFiles_whenSftpFailsAndFileNotCreated_marksFailed() throws Exception {
        Path localRoot = tempDir.resolve("download");

        RecordingExecutor executor = new RecordingExecutor((command, timeout) -> {
            if ("ssh".equals(command.get(0))) {
                String stdout = "EXISTS\t/opt/app/a.properties\n";
                return new ExecResult(0, stdout.getBytes(StandardCharsets.UTF_8), new byte[0]);
            }

            if ("sftp".equals(command.get(0))) {
                // simulate failure and do not create local file
                return new ExecResult(1, new byte[0], "download failed".getBytes(StandardCharsets.UTF_8));
            }

            fail("Unexpected command: " + command);
            return null;
        });

        OpenSshFileFetcher fetcher = new OpenSshFileFetcher(
                tempDir.resolve("mux").toString(),
                Duration.ofSeconds(10),
                false,
                executor
        );

        SshTarget target = new SshTarget("linux1.example.com", 22, "user", RemoteOs.LINUX);

        DownloadResult result = fetcher.downloadFiles(
                target,
                List.of("/opt/app/a.properties"),
                localRoot
        );

        assertTrue(result.downloaded().isEmpty());
        assertTrue(result.missing().isEmpty());
        assertEquals(List.of("/opt/app/a.properties"), result.failed());
    }

    @Test
    void downloadFiles_windows_normalizesRemotePathForSftp() throws Exception {
        Path localRoot = tempDir.resolve("download");

        RecordingExecutor executor = new RecordingExecutor((command, timeout) -> {
            if ("ssh".equals(command.get(0))) {
                String stdout = "EXISTS\tD:/应用/配置/server.properties\n";
                return new ExecResult(0, stdout.getBytes(StandardCharsets.UTF_8), new byte[0]);
            }

            if ("sftp".equals(command.get(0))) {
                Path batchFile = Path.of(command.get(command.indexOf("-b") + 1));
                String batchContent = Files.readString(batchFile, StandardCharsets.UTF_8);

                assertTrue(batchContent.contains("\"/d:/应用/配置/server.properties\""));

                Path downloaded = localRoot.resolve("d/应用/配置/server.properties");
                Files.createDirectories(downloaded.getParent());
                Files.writeString(downloaded, "ok");
                return new ExecResult(0, new byte[0], new byte[0]);
            }

            fail("Unexpected command: " + command);
            return null;
        });

        OpenSshFileFetcher fetcher = new OpenSshFileFetcher(
                tempDir.resolve("mux").toString(),
                Duration.ofSeconds(10),
                false,
                executor
        );

        SshTarget target = new SshTarget("server.ab.cd.com", 22, "ABC\\user", RemoteOs.WINDOWS_OPENSSH);

        DownloadResult result = fetcher.downloadFiles(
                target,
                List.of("D:/应用/配置/server.properties"),
                localRoot
        );

        assertTrue(result.missing().isEmpty());
        assertTrue(result.failed().isEmpty());
        assertEquals(1, result.downloaded().size());
        assertTrue(Files.exists(localRoot.resolve("d/应用/配置/server.properties")));
    }

    private static final class RecordingExecutor implements CommandExecutor {
        private final List<List<String>> commands = new ArrayList<>();
        private final Delegate delegate;

        private RecordingExecutor(Delegate delegate) {
            this.delegate = delegate;
        }

        @Override
        public ExecResult run(List<String> command, Duration timeout) throws IOException {
            commands.add(new ArrayList<>(command));
            return delegate.run(command, timeout);
        }
    }

    @FunctionalInterface
    private interface Delegate {
        ExecResult run(List<String> command, Duration timeout) throws IOException;
    }
}