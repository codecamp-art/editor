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
 * ProcessBuilder-based collector that invokes local ssh/sshpass binaries.
 * Supports Linux and Windows remote hosts and can fetch to disk or in-memory.
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
        try {
            Files.createDirectories(localRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create local root: " + localRoot, e);
        }

        List<Future<FetchResult>> futures = new ArrayList<>();
        for (RemoteServer server : servers) {
            futures.add(executorService.submit(() -> collectForServer(server, fileTasks, searchTasks, localRoot)));
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
        byte[] bytes = fetchFileAsBytes(server, remotePath);
        Path localPath = RemotePathMapper.toLocalPath(localRoot, server.platform(), remotePath);
        if (localPath.getParent() != null) {
            Files.createDirectories(localPath.getParent());
        }
        Files.write(localPath, bytes);
        return localPath;
    }

    private FetchResult collectForServer(
            RemoteServer server,
            List<RemoteFileTask> fileTasks,
            List<RemoteSearchTask> searchTasks,
            Path localRoot
    ) throws IOException, InterruptedException {
        FetchResult result = new FetchResult(server.id());

        for (RemoteFileTask task : fileTasks) {
            fetchFile(server, task.remoteAbsolutePath(), task.fetchMode(), localRoot, result);
        }

        for (RemoteSearchTask task : searchTasks) {
            List<String> paths = listFilesRecursively(server, task.searchRootAbsolutePath(), Pattern.compile(task.filenamePattern()));
            for (String path : paths) {
                fetchFile(server, path, task.fetchMode(), localRoot, result);
            }
        }

        return result;
    }

    private void fetchFile(
            RemoteServer server,
            String remotePath,
            FetchMode fetchMode,
            Path localRoot,
            FetchResult result
    ) throws IOException, InterruptedException {
        byte[] content = fetchFileAsBytes(server, remotePath);

        if (fetchMode == FetchMode.READ_TO_MEMORY) {
            result.addInMemoryFile(remotePath, content);
            return;
        }

        Path localPath = RemotePathMapper.toLocalPath(localRoot, server.platform(), remotePath);
        if (localPath.getParent() != null) {
            Files.createDirectories(localPath.getParent());
        }
        Files.write(localPath, content);
        result.addLocalFile(remotePath, localPath);
    }

    private byte[] fetchFileAsBytes(RemoteServer server, String remotePath) throws IOException, InterruptedException {
        String remoteCommand = buildReadCommand(server.platform(), remotePath);
        CommandResult result = runCommand(buildSshCommand(server, remoteCommand), COMMAND_TIMEOUT_MS);
        if (result.exitCode != 0) {
            throw new IOException("Failed to fetch remote file " + remotePath + ": " + result.stderrText());
        }
        return result.stdout;
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

    private String buildReadCommand(RemotePlatform platform, String remotePath) {
        if (platform == RemotePlatform.WINDOWS) {
            return "powershell -NoProfile -NonInteractive -Command \"$p='" + psQuote(remotePath)
                    + "'; if (Test-Path -LiteralPath $p -PathType Leaf) { Get-Content -LiteralPath $p -Raw } else { exit 2 }\"";
        }

        return "cat " + shellQuote(remotePath);
    }

    private String buildRecursiveListCommand(RemotePlatform platform, String searchRoot) {
        if (platform == RemotePlatform.WINDOWS) {
            return "powershell -NoProfile -NonInteractive -Command \"$p='" + psQuote(searchRoot)
                    + "'; if (Test-Path -LiteralPath $p -PathType Container) { Get-ChildItem -LiteralPath $p -File -Recurse | ForEach-Object { $_.FullName } }\"";
        }

        return "if [ -d " + shellQuote(searchRoot) + " ]; then find " + shellQuote(searchRoot) + " -type f -print; fi";
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

        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Command timed out: " + String.join(" ", command));
        }

        int exitCode = process.exitValue();
        byte[] stdout = process.getInputStream().readAllBytes();
        byte[] stderr = process.getErrorStream().readAllBytes();
        return new CommandResult(exitCode, stdout, stderr);
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private String psQuote(String value) {
        return value.replace("'", "''");
    }

    public void shutdown() {
        executorService.shutdown();
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
            return new String(stderr, StandardCharsets.UTF_8).trim();
        }
    }
}
