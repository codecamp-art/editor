package org.example.remotefetch;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class OpenSshFileFetcher {
    private static final Charset UTF_8 = StandardCharsets.UTF_8;
    private static final int DEFAULT_EXISTENCE_CHECK_BATCH_SIZE = 100;

    private final String controlPathDir;
    private final Duration commandTimeout;
    private final boolean strictHostKeyChecking;

    public OpenSshFileFetcher(String controlPathDir, Duration commandTimeout, boolean strictHostKeyChecking) {
        this.controlPathDir = Objects.requireNonNull(controlPathDir, "controlPathDir");
        this.commandTimeout = commandTimeout == null ? Duration.ofMinutes(10) : commandTimeout;
        this.strictHostKeyChecking = strictHostKeyChecking;

        try {
            Files.createDirectories(Path.of(controlPathDir));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create control path dir: " + controlPathDir, e);
        }
    }

    /**
     * Search exact file name recursively under remoteFolder.
     */
    public List<String> searchFilesByExactName(SshTarget target, String remoteFolder, String fileName) throws IOException {
        Objects.requireNonNull(target, "target");
        requireNonBlank(remoteFolder, "remoteFolder");
        requireNonBlank(fileName, "fileName");

        return switch (target.remoteOs()) {
            case LINUX -> searchLinux(target, remoteFolder, fileName);
            case WINDOWS_OPENSSH -> searchWindows(target, remoteFolder, fileName);
        };
    }

    /**
     * Search by glob recursively under remoteFolder.
     * Example: *.xml, app*.properties
     */
    public List<String> searchFilesByGlob(SshTarget target, String remoteFolder, String globPattern) throws IOException {
        Objects.requireNonNull(target, "target");
        requireNonBlank(remoteFolder, "remoteFolder");
        requireNonBlank(globPattern, "globPattern");

        return switch (target.remoteOs()) {
            case LINUX -> searchLinux(target, remoteFolder, globPattern);
            case WINDOWS_OPENSSH -> searchWindows(target, remoteFolder, globPattern);
        };
    }

    /**
     * Check existence first, then batch download only existing files.
     */
    public DownloadResult downloadFiles(SshTarget target, List<String> remotePaths, Path localRoot) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(remotePaths, "remotePaths");
        Objects.requireNonNull(localRoot, "localRoot");

        if (remotePaths.isEmpty()) {
            return new DownloadResult(Collections.emptyMap(), Collections.emptyList(), Collections.emptyList());
        }

        Files.createDirectories(localRoot);

        Set<String> uniquePaths = new LinkedHashSet<>(remotePaths);
        Map<String, Boolean> existence = checkFilesExist(target, new ArrayList<>(uniquePaths));

        List<String> existing = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (String path : uniquePaths) {
            if (Boolean.TRUE.equals(existence.get(path))) {
                existing.add(path);
            } else {
                missing.add(path);
            }
        }

        if (existing.isEmpty()) {
            return new DownloadResult(Collections.emptyMap(), missing, Collections.emptyList());
        }

        Map<String, Path> mapping = buildLocalMapping(target, existing, localRoot);
        List<String> failed = batchDownloadExisting(target, mapping);

        Map<String, Path> downloaded = new LinkedHashMap<>();
        for (Map.Entry<String, Path> entry : mapping.entrySet()) {
            if (!failed.contains(entry.getKey()) && Files.exists(entry.getValue())) {
                downloaded.put(entry.getKey(), entry.getValue());
            }
        }

        return new DownloadResult(downloaded, missing, failed);
    }

    /**
     * Search first, then download only found files.
     */
    public DownloadResult searchAndDownloadByExactName(
            SshTarget target,
            String remoteFolder,
            String fileName,
            Path localRoot) throws IOException {
        List<String> found = searchFilesByExactName(target, remoteFolder, fileName);
        return downloadFiles(target, found, localRoot);
    }

    /**
     * Search first, then download only found files.
     */
    public DownloadResult searchAndDownloadByGlob(
            SshTarget target,
            String remoteFolder,
            String globPattern,
            Path localRoot) throws IOException {
        List<String> found = searchFilesByGlob(target, remoteFolder, globPattern);
        return downloadFiles(target, found, localRoot);
    }

    /**
     * Read remote file as String.
     * Intended for small/medium text files.
     */
    public String readFileAsString(SshTarget target, String remotePath, Charset charset) throws IOException {
        Objects.requireNonNull(target, "target");
        requireNonBlank(remotePath, "remotePath");
        Charset cs = charset == null ? UTF_8 : charset;

        if (!checkFilesExist(target, List.of(remotePath)).getOrDefault(remotePath, false)) {
            throw new IOException("Remote file does not exist: " + remotePath);
        }

        ExecResult result = switch (target.remoteOs()) {
            case LINUX -> run(buildLinuxReadCommand(target, remotePath), commandTimeout);
            case WINDOWS_OPENSSH -> run(buildWindowsReadStringCommand(target, remotePath), commandTimeout);
        };

        if (result.exitCode() != 0) {
            throw new IOException("Failed to read remote file: " + remotePath
                    + ", stderr=" + new String(result.stderr(), UTF_8));
        }
        return new String(result.stdout(), cs);
    }

    /**
     * Linux: true remote stream.
     * Windows: temp local file-backed stream after download.
     */
    public InputStream openInputStream(SshTarget target, String remotePath) throws IOException {
        Objects.requireNonNull(target, "target");
        requireNonBlank(remotePath, "remotePath");

        if (!checkFilesExist(target, List.of(remotePath)).getOrDefault(remotePath, false)) {
            throw new IOException("Remote file does not exist: " + remotePath);
        }

        return switch (target.remoteOs()) {
            case LINUX -> openLinuxInputStream(target, remotePath);
            case WINDOWS_OPENSSH -> openWindowsInputStreamViaTempDownload(target, remotePath);
        };
    }

    public Map<String, Boolean> checkFilesExist(SshTarget target, List<String> remotePaths) throws IOException {
        return checkFilesExist(target, remotePaths, DEFAULT_EXISTENCE_CHECK_BATCH_SIZE);
    }

    public Map<String, Boolean> checkFilesExist(SshTarget target, List<String> remotePaths, int batchSize) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(remotePaths, "remotePaths");

        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be > 0");
        }
        if (remotePaths.isEmpty()) {
            return Collections.emptyMap();
        }

        List<String> normalized = new ArrayList<>();
        for (String path : remotePaths) {
            requireNonBlank(path, "remotePaths item");
            normalized.add(path);
        }

        Map<String, Boolean> merged = new LinkedHashMap<>();
        for (List<String> chunk : partition(normalized, batchSize)) {
            Map<String, Boolean> partial = switch (target.remoteOs()) {
                case LINUX -> checkLinuxFilesExistChunk(target, chunk);
                case WINDOWS_OPENSSH -> checkWindowsFilesExistChunk(target, chunk);
            };
            merged.putAll(partial);
        }

        for (String path : normalized) {
            merged.putIfAbsent(path, false);
        }
        return merged;
    }

    public void closeMasterConnection(SshTarget target) {
        try {
            List<String> command = new ArrayList<>();
            command.add("ssh");
            command.add("-O");
            command.add("exit");
            command.addAll(commonSshOptions(target));
            command.add(target.destination());
            run(command, Duration.ofSeconds(15));
        } catch (Exception ignored) {
            // best effort
        }
    }

    private List<String> searchLinux(SshTarget target, String remoteFolder, String pattern) throws IOException {
        String remoteCmd = "find " + shQuote(remoteFolder)
                + " -type f -name " + shQuote(pattern)
                + " -print";

        ExecResult result = run(buildSshCommand(target, remoteCmd), commandTimeout);
        if (result.exitCode() != 0) {
            throw new IOException("Linux search failed: " + new String(result.stderr(), UTF_8));
        }
        return splitLines(result.stdout());
    }

    private List<String> searchWindows(SshTarget target, String remoteFolder, String pattern) throws IOException {
        String windowsFolder = toWindowsLiteralPath(remoteFolder);

        String ps =
                "$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false);" +
                "$root = " + psSingleQuote(windowsFolder) + ";" +
                "$filter = " + psSingleQuote(pattern) + ";" +
                "$items = Get-ChildItem -LiteralPath $root -Recurse -File -Filter $filter -ErrorAction Stop;" +
                "$items | ForEach-Object {" +
                "  $p = $_.FullName;" +
                "  $drive = $p.Substring(0,1).ToLower();" +
                "  $rest = $p.Substring(2).Replace('\\\\','/');" +
                "  '/' + $drive + ':' + $rest" +
                "}";

        ExecResult result = run(buildWindowsPowerShellCommand(target, ps), commandTimeout);
        if (result.exitCode() != 0) {
            throw new IOException("Windows search failed: " + new String(result.stderr(), UTF_8));
        }
        return splitLines(result.stdout());
    }

    private Map<String, Boolean> checkLinuxFilesExistChunk(SshTarget target, List<String> remotePaths) throws IOException {
        if (remotePaths.isEmpty()) {
            return Collections.emptyMap();
        }

        StringBuilder remoteCmd = new StringBuilder();
        for (String path : remotePaths) {
            String q = shQuote(path);
            remoteCmd.append("if [ -f ").append(q).append(" ]; then ");
            remoteCmd.append("printf 'EXISTS\\t%s\\n' ").append(q).append("; ");
            remoteCmd.append("else ");
            remoteCmd.append("printf 'MISSING\\t%s\\n' ").append(q).append("; ");
            remoteCmd.append("fi; ");
        }

        ExecResult result = run(buildSshCommand(target, remoteCmd.toString()), commandTimeout);
        if (result.exitCode() != 0) {
            throw new IOException("Linux existence check failed: " + new String(result.stderr(), UTF_8));
        }

        Map<String, Boolean> map = new LinkedHashMap<>();
        for (String line : splitLines(result.stdout())) {
            int idx = line.indexOf('\t');
            if (idx > 0) {
                String status = line.substring(0, idx);
                String path = line.substring(idx + 1);
                map.put(path, "EXISTS".equals(status));
            }
        }

        for (String path : remotePaths) {
            map.putIfAbsent(path, false);
        }
        return map;
    }

    private Map<String, Boolean> checkWindowsFilesExistChunk(SshTarget target, List<String> remotePaths) throws IOException {
        if (remotePaths.isEmpty()) {
            return Collections.emptyMap();
        }

        List<String> windowsPaths = remotePaths.stream()
                .map(this::toWindowsLiteralPath)
                .collect(Collectors.toList());

        StringBuilder ps = new StringBuilder();
        ps.append("$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false);");

        for (String path : windowsPaths) {
            ps.append("$p = ").append(psSingleQuote(path)).append(";");
            ps.append("if (Test-Path -LiteralPath $p -PathType Leaf) {");
            ps.append("  'EXISTS' + \"`t\" + $p");
            ps.append("} else {");
            ps.append("  'MISSING' + \"`t\" + $p");
            ps.append("};");
        }

        ExecResult result = run(buildWindowsPowerShellCommand(target, ps.toString()), commandTimeout);
        if (result.exitCode() != 0) {
            throw new IOException("Windows existence check failed: " + new String(result.stderr(), UTF_8));
        }

        Map<String, Boolean> winResult = new LinkedHashMap<>();
        for (String line : splitLines(result.stdout())) {
            int idx = line.indexOf('\t');
            if (idx > 0) {
                String status = line.substring(0, idx);
                String path = line.substring(idx + 1);
                winResult.put(path, "EXISTS".equals(status));
            }
        }

        Map<String, Boolean> finalMap = new LinkedHashMap<>();
        for (int i = 0; i < remotePaths.size(); i++) {
            String original = remotePaths.get(i);
            String win = windowsPaths.get(i);
            finalMap.put(original, winResult.getOrDefault(win, false));
        }
        return finalMap;
    }

    private List<String> batchDownloadExisting(SshTarget target, Map<String, Path> mapping) throws IOException {
        if (mapping.isEmpty()) {
            return Collections.emptyList();
        }

        Path batchFile = Files.createTempFile("sftp-batch-", ".txt");
        try {
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, Path> entry : mapping.entrySet()) {
                Files.createDirectories(entry.getValue().getParent());
                String localPathForSftp = entry.getValue().toAbsolutePath().toString().replace('\\', '/');
                lines.add("get " + sftpQuote(entry.getKey()) + " " + sftpQuote(localPathForSftp));
            }

            Files.write(batchFile, lines, UTF_8);

            List<String> command = new ArrayList<>();
            command.add("sftp");
            command.addAll(commonSshOptions(target));
            command.add("-b");
            command.add(batchFile.toString());
            command.add(target.destination());

            ExecResult result = run(command, commandTimeout);

            List<String> failed = new ArrayList<>();
            if (result.exitCode() != 0) {
                for (Map.Entry<String, Path> entry : mapping.entrySet()) {
                    if (!Files.exists(entry.getValue())) {
                        failed.add(entry.getKey());
                    }
                }
            }

            return failed;
        } finally {
            Files.deleteIfExists(batchFile);
        }
    }

    private List<String> buildSshCommand(SshTarget target, String remoteCommand) {
        List<String> command = new ArrayList<>();
        command.add("ssh");
        command.addAll(commonSshOptions(target));
        command.add(target.destination());
        command.add(remoteCommand);
        return command;
    }

    private List<String> buildLinuxReadCommand(SshTarget target, String remotePath) {
        return buildSshCommand(target, "cat -- " + shQuote(remotePath));
    }

    private List<String> buildWindowsReadStringCommand(SshTarget target, String remotePath) {
        String windowsPath = toWindowsLiteralPath(remotePath);

        String ps =
                "$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false);" +
                "Get-Content -Raw -LiteralPath " + psSingleQuote(windowsPath);

        return buildWindowsPowerShellCommand(target, ps);
    }

    private List<String> buildWindowsPowerShellCommand(SshTarget target, String powerShellScript) {
        String encoded = Base64.getEncoder()
                .encodeToString(powerShellScript.getBytes(StandardCharsets.UTF_16LE));

        return buildSshCommand(
                target,
                "powershell -NoProfile -NonInteractive -EncodedCommand " + encoded);
    }

    private InputStream openLinuxInputStream(SshTarget target, String remotePath) throws IOException {
        Process process = start(buildLinuxReadCommand(target, remotePath));
        return new RemoteInputStream(
                new BufferedInputStream(process.getInputStream()),
                process,
                null,
                null);
    }

    private InputStream openWindowsInputStreamViaTempDownload(SshTarget target, String remotePath) throws IOException {
        Path tempDir = Files.createTempDirectory("remote-win-file-");
        DownloadResult result = downloadFiles(target, List.of(remotePath), tempDir);

        if (!result.missing().isEmpty()) {
            Files.deleteIfExists(tempDir);
            throw new IOException("Remote file does not exist: " + remotePath);
        }
        if (!result.failed().isEmpty()) {
            Files.deleteIfExists(tempDir);
            throw new IOException("Failed to download remote file: " + remotePath);
        }

        Path localFile = result.downloaded().get(remotePath);
        if (localFile == null || !Files.exists(localFile)) {
            Files.deleteIfExists(tempDir);
            throw new IOException("Downloaded file not found locally for remote path: " + remotePath);
        }

        return new RemoteInputStream(
                new BufferedInputStream(Files.newInputStream(localFile)),
                null,
                localFile,
                tempDir);
    }

    private Map<String, Path> buildLocalMapping(SshTarget target, List<String> remotePaths, Path localRoot) {
        Map<String, Path> mapping = new LinkedHashMap<>();
        List<String> sorted = remotePaths.stream()
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.toList());

        for (String remotePath : sorted) {
            String relative = switch (target.remoteOs()) {
                case LINUX -> linuxRemotePathToRelative(remotePath);
                case WINDOWS_OPENSSH -> windowsSftpPathToRelative(remotePath);
            };
            mapping.put(remotePath, localRoot.resolve(relative));
        }
        return mapping;
    }

    private String linuxRemotePathToRelative(String remotePath) {
        String normalized = remotePath.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    /**
     * Example:
     * /d:/apps/配置/a.txt -> d/apps/配置/a.txt
     */
    private String windowsSftpPathToRelative(String remotePath) {
        String p = remotePath.trim().replace('\\', '/');
        if (p.matches("^/[a-zA-Z]:/.*")) {
            char drive = Character.toLowerCase(p.charAt(1));
            String rest = p.substring(4);
            if (rest.startsWith("/")) {
                rest = rest.substring(1);
            }
            return drive + "/" + rest;
        }
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p;
    }

    /**
     * Accept:
     * - D:\应用\配置
     * - /d:/应用/配置
     */
    private String toWindowsLiteralPath(String path) {
        String p = path.trim();

        if (p.matches("^/[a-zA-Z]:/.*")) {
            char drive = Character.toUpperCase(p.charAt(1));
            String rest = p.substring(4).replace('/', '\\');
            return drive + ":\\" + rest;
        }
        return p;
    }

    private List<String> commonSshOptions(SshTarget target) {
        List<String> opts = new ArrayList<>();
        opts.add("-p");
        opts.add(String.valueOf(target.port()));

        opts.add("-o");
        opts.add("ControlMaster=auto");
        opts.add("-o");
        opts.add("ControlPersist=5m");
        opts.add("-o");
        opts.add("ControlPath=" + controlPathDir + "/mux-%C");

        opts.add("-o");
        opts.add("BatchMode=yes");

        if (!strictHostKeyChecking) {
            opts.add("-o");
            opts.add("StrictHostKeyChecking=no");
            opts.add("-o");
            opts.add("UserKnownHostsFile=/dev/null");
        }

        return opts;
    }

    private ExecResult run(List<String> command, Duration timeout) throws IOException {
        Process process = start(command);
        return waitFor(process, timeout);
    }

    private ExecResult waitFor(Process process, Duration timeout) throws IOException {
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting process", e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Command timed out");
        }

        return new ExecResult(
                process.exitValue(),
                process.getInputStream().readAllBytes(),
                process.getErrorStream().readAllBytes());
    }

    private Process start(List<String> command) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        return pb.start();
    }

    private static List<String> splitLines(byte[] bytes) {
        String text = new String(bytes, UTF_8);
        return text.lines()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private static <T> List<List<T>> partition(List<T> input, int batchSize) {
        if (input.isEmpty()) {
            return Collections.emptyList();
        }

        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < input.size(); i += batchSize) {
            parts.add(new ArrayList<>(input.subList(i, Math.min(i + batchSize, input.size()))));
        }
        return parts;
    }

    private static String shQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String psSingleQuote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String sftpQuote(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}