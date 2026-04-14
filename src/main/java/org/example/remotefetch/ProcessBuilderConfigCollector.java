package org.example.remotefetch;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * ProcessBuilder-based collector that uses native sftp/ssh binaries.
 * Opens at most one SFTP session per server and batches all downloads for that host.
 */
public class ProcessBuilderConfigCollector {

    private static final long COMMAND_TIMEOUT_MS = 20_000;

    private final ExecutorService executorService;

    public ProcessBuilderConfigCollector(int workerThreads) {
        if (workerThreads < 1) {
            throw new IllegalArgumentException("workerThreads must be >= 1");
        }
        this.executorService = Executors.newFixedThreadPool(workerThreads);
    }

    public Map<String, FetchResult> collect(
            List<RemoteServer> servers,
            List<RemoteFileTask> fileTasks,
            List<RemoteSearchTask> searchTasks,
            Path localRoot
    ) {
        List<RemoteFetchRequest> requests = servers.stream()
                .map(server -> new RemoteFetchRequest(server, fileTasks, searchTasks))
                .toList();
        return collect(requests, localRoot);
    }

    public Map<String, FetchResult> collect(List<RemoteFetchRequest> requests, Path localRoot) {
        try {
            Files.createDirectories(localRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create local root: " + localRoot, e);
        }

        List<Future<FetchResult>> futures = new ArrayList<>();
        for (RemoteFetchRequest request : requests) {
            futures.add(executorService.submit(() -> collectForRequest(request, localRoot)));
        }

        Map<String, FetchResult> results = new ConcurrentHashMap<>();
        for (Future<FetchResult> future : futures) {
            try {
                FetchResult result = future.get();
                results.put(result.getServerId(), result);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while collecting files", e);
            } catch (ExecutionException e) {
                throw new RuntimeException("Failed collecting files", e.getCause());
            }
        }
        return results;
    }

    public InputStream fetchFileAsInputStream(RemoteServer server, String remotePath) throws IOException, InterruptedException {
        return new ByteArrayInputStream(fetchFileAsBytes(server, remotePath));
    }

    public String fetchFileAsString(RemoteServer server, String remotePath) throws IOException, InterruptedException {
        return new String(fetchFileAsBytes(server, remotePath), StandardCharsets.UTF_8);
    }

    public Path fetchFileToDisk(RemoteServer server, String remotePath, Path localRoot) throws IOException, InterruptedException {
        Path localPath = RemotePathMapper.toLocalPath(localRoot, server.platform(), remotePath);
        executeSftpBatch(server, List.of(plannedDiskDownload(server, remotePath, localPath)));
        return localPath;
    }

    public byte[] fetchFileAsBytes(RemoteServer server, String remotePath) throws IOException, InterruptedException {
        Path stagingRoot = Files.createTempDirectory("pb-sftp-single-");
        try {
            Path stagingPath = RemotePathMapper.toLocalPath(stagingRoot, server.platform(), remotePath);
            PlannedDownload download = plannedMemoryDownload(server, remotePath, stagingPath, stagingRoot);
            executeSftpBatch(server, List.of(download));
            byte[] content = Files.readAllBytes(stagingPath);
            Files.deleteIfExists(stagingPath);
            return content;
        } finally {
            deleteRecursively(stagingRoot);
        }
    }

    private FetchResult collectForRequest(RemoteFetchRequest request, Path localRoot) throws IOException, InterruptedException {
        RemoteServer server = request.server();
        FetchResult result = new FetchResult(server.id());
        List<PlannedDownload> downloads = new ArrayList<>();

        for (RemoteFileTask task : request.fileTasks()) {
            downloads.add(buildPlannedDownload(server, task.remoteAbsolutePath(), task.fetchMode(), localRoot));
        }

        for (RemoteSearchTask task : request.searchTasks()) {
            List<String> paths = listFilesRecursively(server, task.searchRootAbsolutePath(), Pattern.compile(task.filenamePattern()));
            for (String path : paths) {
                downloads.add(buildPlannedDownload(server, path, task.fetchMode(), localRoot));
            }
        }

        if (downloads.isEmpty()) {
            return result;
        }

        try {
            executeSftpBatch(server, downloads);

            for (PlannedDownload download : downloads) {
                if (download.fetchMode() == FetchMode.READ_TO_MEMORY) {
                    result.addInMemoryFile(download.originalRemotePath(), Files.readAllBytes(download.localPath()));
                    Files.deleteIfExists(download.localPath());
                } else {
                    result.addLocalFile(download.originalRemotePath(), download.localPath());
                }
            }
            return result;
        } finally {
            cleanupMemoryDownloads(downloads);
        }
    }

    private void cleanupMemoryDownloads(List<PlannedDownload> downloads) {
        for (PlannedDownload download : downloads) {
            if (download.fetchMode() != FetchMode.READ_TO_MEMORY) {
                continue;
            }

            try {
                deleteRecursively(download.temporaryRoot());
            } catch (IOException ignored) {
                // Best-effort cleanup for transient staging files.
            }
        }
    }

    private PlannedDownload buildPlannedDownload(
            RemoteServer server,
            String remotePath,
            FetchMode fetchMode,
            Path localRoot
    ) throws IOException {
        if (fetchMode == FetchMode.SAVE_TO_DISK) {
            Path localPath = RemotePathMapper.toServerLocalPath(localRoot, server.id(), server.platform(), remotePath);
            return plannedDiskDownload(server, remotePath, localPath);
        }

        Path tempRoot = Files.createTempDirectory("pb-sftp-memory-" + server.id() + "-");
        Path localPath = RemotePathMapper.toLocalPath(tempRoot, server.platform(), remotePath);
        return plannedMemoryDownload(server, remotePath, localPath, tempRoot);
    }

    private PlannedDownload plannedDiskDownload(RemoteServer server, String remotePath, Path localPath) {
        return new PlannedDownload(
                remotePath,
                remotePathCandidates(server.platform(), remotePath),
                FetchMode.SAVE_TO_DISK,
                localPath,
                null
        );
    }

    private PlannedDownload plannedMemoryDownload(RemoteServer server, String remotePath, Path localPath, Path temporaryRoot) {
        return new PlannedDownload(
                remotePath,
                remotePathCandidates(server.platform(), remotePath),
                FetchMode.READ_TO_MEMORY,
                localPath,
                temporaryRoot
        );
    }

    private void executeSftpBatch(RemoteServer server, List<PlannedDownload> downloads) throws IOException, InterruptedException {
        Path batchFile = Files.createTempFile("pb-sftp-", ".batch");
        try {
            List<String> batchLines = new ArrayList<>();
            for (PlannedDownload download : downloads) {
                if (download.localPath().getParent() != null) {
                    Files.createDirectories(download.localPath().getParent());
                }
                Files.deleteIfExists(download.localPath());
                for (String candidate : download.remoteCandidates()) {
                    batchLines.add("-get " + sftpQuote(candidate) + " " + sftpQuote(localPathForSftp(download.localPath())));
                }
            }

            Files.write(batchFile, batchLines, StandardCharsets.UTF_8);
            CommandResult result = runCommand(buildSftpCommand(server, batchFile), COMMAND_TIMEOUT_MS);

            List<String> missing = downloads.stream()
                    .filter(download -> !Files.exists(download.localPath()))
                    .map(PlannedDownload::originalRemotePath)
                    .toList();

            if (!missing.isEmpty()) {
                throw new IOException("Failed to download remote files from " + server.id()
                        + ": " + missing + ". " + result.stderrText());
            }
        } finally {
            Files.deleteIfExists(batchFile);
        }
    }

    private List<String> listFilesRecursively(RemoteServer server, String searchRoot, Pattern fileNamePattern)
            throws IOException, InterruptedException {
        String remoteCommand = buildRecursiveListCommand(server.platform(), searchRoot);
        CommandResult result = runCommand(buildSshCommand(server, remoteCommand), COMMAND_TIMEOUT_MS);
        if (result.exitCode != 0) {
            throw new IOException("Failed to list remote files under " + searchRoot + ": " + result.stderrText());
        }

        String output = result.stdoutText();
        if (output.isBlank()) {
            return List.of();
        }

        List<String> matched = new ArrayList<>();
        for (String line : output.lines().toList()) {
            String path = line.trim();
            if (path.isEmpty()) {
                continue;
            }
            String name = extractFileName(server.platform(), path);
            if (fileNamePattern.matcher(name).matches()) {
                matched.add(path);
            }
        }
        return matched;
    }

    private String extractFileName(RemotePlatform platform, String remotePath) {
        if (platform == RemotePlatform.WINDOWS) {
            String normalized = remotePath.replace('/', '\\');
            int idx = normalized.lastIndexOf('\\');
            return idx >= 0 ? normalized.substring(idx + 1) : normalized;
        }

        int idx = remotePath.lastIndexOf('/');
        return idx >= 0 ? remotePath.substring(idx + 1) : remotePath;
    }

    private String buildRecursiveListCommand(RemotePlatform platform, String searchRoot) {
        if (platform == RemotePlatform.WINDOWS) {
            return "powershell -NoProfile -NonInteractive -Command \"$p='" + psQuote(searchRoot)
                    + "'; if (Test-Path -LiteralPath $p -PathType Container) { Get-ChildItem -LiteralPath $p -File -Recurse | ForEach-Object { $_.FullName } }\"";
        }

        return "if [ -d " + shellQuote(searchRoot) + " ]; then find " + shellQuote(searchRoot) + " -type f -print; fi";
    }

    private List<String> buildSftpCommand(RemoteServer server, Path batchFile) {
        List<String> command = new ArrayList<>();

        if (server.authMode() == RemoteAuthMode.PASSWORD) {
            command.add("sshpass");
            command.add("-p");
            command.add(server.password());
        }

        command.add("sftp");
        command.add("-b");
        command.add(batchFile.toString());
        command.add("-P");
        command.add(String.valueOf(server.port()));
        command.add("-o");
        command.add("StrictHostKeyChecking=no");
        command.add("-o");
        command.add("UserKnownHostsFile=/dev/null");
        command.add("-o");
        command.add("ConnectTimeout=15");

        if (server.authMode() == RemoteAuthMode.PRIVATE_KEY) {
            command.add("-i");
            command.add(server.privateKeyPath());
        }

        if (server.authMode() == RemoteAuthMode.KERBEROS) {
            command.add("-o");
            command.add("PreferredAuthentications=gssapi-with-mic");
            command.add("-o");
            command.add("GSSAPIAuthentication=yes");
        }

        command.add(server.username() + "@" + server.host());
        return command;
    }

    private List<String> buildSshCommand(RemoteServer server, String remoteCommand) {
        List<String> command = new ArrayList<>();

        if (server.authMode() == RemoteAuthMode.PASSWORD) {
            command.add("sshpass");
            command.add("-p");
            command.add(server.password());
        }

        command.add("ssh");
        command.add("-p");
        command.add(String.valueOf(server.port()));
        command.add("-o");
        command.add("StrictHostKeyChecking=no");
        command.add("-o");
        command.add("UserKnownHostsFile=/dev/null");
        command.add("-o");
        command.add("ConnectTimeout=15");

        if (server.authMode() == RemoteAuthMode.PRIVATE_KEY) {
            command.add("-i");
            command.add(server.privateKeyPath());
        }

        if (server.authMode() == RemoteAuthMode.KERBEROS) {
            command.add("-o");
            command.add("PreferredAuthentications=gssapi-with-mic");
            command.add("-o");
            command.add("GSSAPIAuthentication=yes");
        }

        command.add(server.username() + "@" + server.host());
        command.add(remoteCommand);
        return command;
    }

    protected CommandResult runCommand(List<String> command, long timeoutMs) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).start();

        StreamCollector stdoutCollector = new StreamCollector(process.getInputStream());
        StreamCollector stderrCollector = new StreamCollector(process.getErrorStream());
        stdoutCollector.start();
        stderrCollector.start();

        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Command timed out: " + String.join(" ", command));
        }

        stdoutCollector.await();
        stderrCollector.await();

        return new CommandResult(process.exitValue(), stdoutCollector.bytes(), stderrCollector.bytes());
    }

    private List<String> remotePathCandidates(RemotePlatform platform, String remotePath) {
        String normalizedPath = RemotePathMapper.normalizeRemoteAbsolutePath(platform, remotePath);
        if (platform != RemotePlatform.WINDOWS) {
            return List.of(normalizedPath);
        }

        if (!normalizedPath.matches("/[a-zA-Z]/.*")) {
            return List.of(normalizedPath);
        }

        String drive = normalizedPath.substring(1, 2).toUpperCase();
        String rest = normalizedPath.substring(2);

        return List.of(
                normalizedPath,
                "/" + drive + ":" + rest,
                drive + ":" + rest
        );
    }

    private String localPathForSftp(Path localPath) {
        return localPath.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private String sftpQuote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private String psQuote(String value) {
        return value.replace("'", "''");
    }

    private void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }

        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public void shutdown() {
        executorService.shutdown();
    }

    private record PlannedDownload(
            String originalRemotePath,
            List<String> remoteCandidates,
            FetchMode fetchMode,
            Path localPath,
            Path temporaryRoot
    ) {
    }

    private static final class StreamCollector {
        private final InputStream inputStream;
        private final java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
        private final Thread thread;
        private volatile IOException failure;

        private StreamCollector(InputStream inputStream) {
            this.inputStream = inputStream;
            this.thread = new Thread(this::collect);
            this.thread.setDaemon(true);
        }

        private void start() {
            thread.start();
        }

        private void await() throws IOException, InterruptedException {
            thread.join();
            if (failure != null) {
                throw failure;
            }
        }

        private byte[] bytes() {
            return outputStream.toByteArray();
        }

        private void collect() {
            try (InputStream in = inputStream) {
                byte[] buffer = new byte[8_192];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    outputStream.write(buffer, 0, read);
                }
            } catch (IOException e) {
                failure = e;
            }
        }
    }

    protected static final class CommandResult {
        private final int exitCode;
        private final byte[] stdout;
        private final byte[] stderr;

        protected CommandResult(int exitCode, byte[] stdout, byte[] stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        private String stdoutText() {
            return new String(stdout, StandardCharsets.UTF_8);
        }

        private String stderrText() {
            String stderrText = new String(stderr, StandardCharsets.UTF_8).trim();
            if (!stderrText.isEmpty()) {
                return stderrText;
            }
            return new String(stdout, StandardCharsets.UTF_8).trim();
        }
    }
}
