package org.example.remotefetch;

import java.io.IOException;
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
import java.util.stream.Collectors;

/**
 * High-throughput collector for explicit files and recursive filename search.
 * Uses native ssh shell commands via ProcessBuilder instead of JSch.
 */
public class SftpConfigCollector {

    private final ExecutorService executorService;

    public SftpConfigCollector(int workerThreads) {
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

    private FetchResult collectForServer(
            RemoteServer server,
            List<RemoteFileTask> fileTasks,
            List<RemoteSearchTask> searchTasks,
            Path localRoot
    ) throws IOException, InterruptedException {
        FetchResult result = new FetchResult(server.id());

        for (RemoteFileTask task : fileTasks) {
            fetchFile(server, server.platform(), task.remoteAbsolutePath(), task.fetchMode(), localRoot, result);
        }

        for (RemoteSearchTask task : searchTasks) {
            Pattern pattern = Pattern.compile(task.filenamePattern());
            List<String> matched = walkAndCollectMatches(server, task.searchRootAbsolutePath(), pattern);
            for (String remotePath : matched) {
                fetchFile(server, server.platform(), remotePath, task.fetchMode(), localRoot, result);
            }
        }

        return result;
    }

    private void fetchFile(
            RemoteServer server,
            RemotePlatform platform,
            String remotePath,
            FetchMode fetchMode,
            Path localRoot,
            FetchResult result
    ) throws IOException, InterruptedException {
        byte[] content = readBytes(server, remotePath);

        if (fetchMode == FetchMode.READ_TO_MEMORY) {
            result.addInMemoryFile(remotePath, content);
            return;
        }

        Path localPath = RemotePathMapper.toLocalPath(localRoot, platform, remotePath);
        if (localPath.getParent() != null) {
            Files.createDirectories(localPath.getParent());
        }

        Files.write(localPath, content);
        result.addLocalFile(remotePath, localPath);
    }

    private byte[] readBytes(RemoteServer server, String remotePath) throws IOException, InterruptedException {
        String output = runSshCommand(server, "cat " + shellQuote(remotePath));
        return output.getBytes(StandardCharsets.UTF_8);
    }

    private List<String> walkAndCollectMatches(RemoteServer server, String root, Pattern filenamePattern)
            throws IOException, InterruptedException {
        String cmd = "if [ -d " + shellQuote(root) + " ]; then find " + shellQuote(root) + " -type f -printf '%p\\n'; fi";
        String output = runSshCommand(server, cmd);
        if (output.isBlank()) {
            return List.of();
        }
        return output.lines()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .filter(path -> {
                    String name = path.substring(path.lastIndexOf('/') + 1);
                    return filenamePattern.matcher(name).matches();
                })
                .collect(Collectors.toList());
    }

    private String runSshCommand(RemoteServer server, String remoteCommand) throws IOException, InterruptedException {
        List<String> command = buildSshCommand(server, remoteCommand);
        return runCommand(command, 15_000);
    }

    private String runCommand(List<String> command, long timeoutMs) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();

        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Command timed out: " + String.join(" ", command));
        }

        int exitCode = process.exitValue();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        if (exitCode != 0) {
            throw new IOException("Command failed (exit=" + exitCode + "): " + stderr.trim());
        }

        return stdout;
    }

    private List<String> buildSshCommand(RemoteServer server, String remoteCommand) {
        List<String> command = new ArrayList<>();
        if (server.password() != null && !server.password().isBlank()) {
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
        command.add(server.username() + "@" + server.host());
        command.add(remoteCommand);
        return command;
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    public void shutdown() {
        executorService.shutdown();
    }
}
