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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class OpenSshFileFetcher {
    private static final Charset UTF_8 = StandardCharsets.UTF_8;

    private final Path controlPathDir;
    private final Duration commandTimeout;
    private final boolean strictHostKeyChecking;

    public OpenSshFileFetcher(Path controlPathDir, Duration commandTimeout, boolean strictHostKeyChecking) {
        this.controlPathDir = Objects.requireNonNull(controlPathDir, "controlPathDir");
        this.commandTimeout = commandTimeout == null ? Duration.ofMinutes(10) : commandTimeout;
        this.strictHostKeyChecking = strictHostKeyChecking;

        try {
            Files.createDirectories(controlPathDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create controlPathDir: " + controlPathDir, e);
        }
    }

    /**
     * Search files recursively under a remote folder by exact filename.
     */
    public List<String> searchFilesByExactName(SshTarget target, String remoteFolder, String fileName) throws IOException {
        Objects.requireNonNull(target, "target");
        requireNonBlank(remoteFolder, "remoteFolder");
        requireNonBlank(fileName, "fileName");

        return switch (target.remoteOs()) {
            case LINUX -> searchLinux(target, remoteFolder, fileName, true);
            case WINDOWS_OPENSSH -> searchWindows(target, remoteFolder, fileName, true);
        };
    }

    /**
     * Search files recursively under a remote folder by glob pattern.
     * Examples: *.xml, app*.properties
     */
    public List<String> searchFilesByGlob(SshTarget target, String remoteFolder, String globPattern) throws IOException {
        Objects.requireNonNull(target, "target");
        requireNonBlank(remoteFolder, "remoteFolder");
        requireNonBlank(globPattern, "globPattern");

        return switch (target.remoteOs()) {
            case LINUX -> searchLinux(target, remoteFolder, globPattern, false);
            case WINDOWS_OPENSSH -> searchWindows(target, remoteFolder, globPattern, false);
        };
    }

    /**
     * Download exact remote paths to localRoot.
     *
     * Returns map: remotePath -> localPath
     */
    public Map<String, Path> downloadFiles(SshTarget target, List<String> remotePaths, Path localRoot) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(remotePaths, "remotePaths");
        Objects.requireNonNull(localRoot, "localRoot");

        if (remotePaths.isEmpty()) {
            return Collections.emptyMap();
        }

        Files.createDirectories(localRoot);

        Map<String, Path> mapping = buildLocalMapping(target, remotePaths, localRoot);
        Path batchFile = Files.createTempFile("sftp-batch-", ".txt");

        try {
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, Path> entry : mapping.entrySet()) {
                Files.createDirectories(entry.getValue().getParent());
                lines.add("get " + sftpQuote(entry.getKey()) + " " + sftpQuote(entry.getValue().toString()));
            }
            Files.write(batchFile, lines, UTF_8);

            List<String> command = new ArrayList<>();
            command.add("sftp");
            command.addAll(commonSshOptions(target));
            command.add("-b");
            command.add(batchFile.toString());
            command.add(target.destination());

            ExecResult result = run(command, commandTimeout);
            if (result.exitCode() != 0) {
                throw new IOException("sftp download failed: " + new String(result.stderr(), UTF_8));
            }

            for (Map.Entry<String, Path> entry : mapping.entrySet()) {
                if (!Files.exists(entry.getValue())) {
                    throw new IOException("Expected downloaded file does not exist: " + entry.getValue());
                }
            }
            return mapping;
        } finally {
            Files.deleteIfExists(batchFile);
        }
    }

    /**
     * Search files then download them.
     */
    public Map<String, Path> searchAndDownloadByExactName(
            SshTarget target,
            String remoteFolder,
            String fileName,
            Path localRoot) throws IOException {
        List<String> paths = searchFilesByExactName(target, remoteFolder, fileName);
        return downloadFiles(target, paths, localRoot);
    }

    /**
     * Search files then download them.
     */
    public Map<String, Path> searchAndDownloadByGlob(
            SshTarget target,
            String remoteFolder,
            String globPattern,
            Path localRoot) throws IOException {
        List<String> paths = searchFilesByGlob(target, remoteFolder, globPattern);
        return downloadFiles(target, paths, localRoot);
    }

    /**
     * Read remote file as String.
     * For Linux: direct ssh stream.
     * For Windows: PowerShell Get-Content -Raw.
     */
    public String readFileAsString(SshTarget target, String remotePath, Charset charset) throws IOException {
        Objects.requireNonNull(target, "target");
        requireNonBlank(remotePath, "remotePath");
        Charset cs = charset == null ? UTF_8 : charset;

        ExecResult result = switch (target.remoteOs()) {
            case LINUX -> run(buildLinuxReadCommand(target, remotePath), commandTimeout);
            case WINDOWS_OPENSSH -> run(buildWindowsReadStringCommand(target, remotePath), commandTimeout);
        };

        if (result.exitCode() != 0) {
            throw new IOException("Failed to read remote file: " + remotePath + ", stderr=" + new String(result.stderr(), UTF_8));
        }
        return new String(result.stdout(), cs);
    }

    /**
     * Open remote file as InputStream.
     *
     * Linux: stream via ssh cat
     * Windows: fallback to temp download via sftp and return local InputStream
     */
    public InputStream openInputStream(SshTarget target, String remotePath) throws IOException {
        Objects.requireNonNull(target, "target");
        requireNonBlank(remotePath, "remotePath");

        return switch (target.remoteOs()) {
            case LINUX -> openLinuxInputStream(target, remotePath);
            case WINDOWS_OPENSSH -> openWindowsInputStreamViaTempDownload(target, remotePath);
        };
    }

    /**
     * Optional: close shared OpenSSH master connection for a host.
     */
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

    private List<String> searchLinux(SshTarget target, String remoteFolder, String value, boolean exactName) throws IOException {
        String predicate = exactName
                ? "-name " + shQuote(value)
                : "-name " + shQuote(value);

        String remoteCmd = "find " + shQuote(remoteFolder)
                + " -type f "
                + predicate
                + " -print";

        ExecResult result = run(buildSshCommand(target, remoteCmd), commandTimeout);
        if (result.exitCode() != 0) {
            throw new IOException("Linux search failed: " + new String(result.stderr(), UTF_8));
        }

        return splitLines(result.stdout());
    }

    private List<String> searchWindows(SshTarget target, String remoteFolder, String value, boolean exactName) throws IOException {
        String windowsFolder = toWindowsLiteralPath(remoteFolder);
        String filter = value;

        String ps = "$root = " + psSingleQuote(windowsFolder) + ";"
                + "$filter = " + psSingleQuote(filter) + ";"
                + "$items = Get-ChildItem -LiteralPath $root -Recurse -File -Filter $filter -ErrorAction Stop;"
                + "$items | ForEach-Object { "
                + "$p = $_.FullName;"
                + "$drive = $p.Substring(0,1).ToLower();"
                + "$rest = $p.Substring(2).Replace('\\\\','/');"
                + "'/' + $drive + ':' + $rest"
                + "}";

        ExecResult result = run(buildWindowsPowerShellCommand(target, ps), commandTimeout);
        if (result.exitCode() != 0) {
            throw new IOException("Windows search failed: " + new String(result.stderr(), UTF_8));
        }

        return splitLines(result.stdout());
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
        String remoteCmd = "cat -- " + shQuote(remotePath);
        return buildSshCommand(target, remoteCmd);
    }

    private List<String> buildWindowsReadStringCommand(SshTarget target, String remotePath) {
        String windowsPath = toWindowsLiteralPath(remotePath);

        String ps = "$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false);"
                + "Get-Content -Raw -LiteralPath " + psSingleQuote(windowsPath);

        return buildWindowsPowerShellCommand(target, ps);
    }

    private List<String> buildWindowsPowerShellCommand(SshTarget target, String powerShellScript) {
        String encoded = Base64.getEncoder().encodeToString(powerShellScript.getBytes(StandardCharsets.UTF_16LE));

        return buildSshCommand(
                target,
                "powershell -NoProfile -NonInteractive -EncodedCommand " + encoded);
    }

    private InputStream openLinuxInputStream(SshTarget target, String remotePath) throws IOException {
        Process process = start(buildLinuxReadCommand(target, remotePath));
        return new RemoteInputStream(
                new BufferedInputStream(process.getInputStream()),
                process,
                null);
    }

    private InputStream openWindowsInputStreamViaTempDownload(SshTarget target, String remotePath) throws IOException {
        Path tempDir = Files.createTempDirectory("remote-win-file-");
        Map<String, Path> downloaded = downloadFiles(target, List.of(remotePath), tempDir);
        Path localFile = downloaded.get(remotePath);
        return new RemoteInputStream(
                new BufferedInputStream(Files.newInputStream(localFile)),
                null,
                localFile);
    }

    private Map<String, Path> buildLocalMapping(SshTarget target, List<String> remotePaths, Path localRoot) {
        Map<String, Path> mapping = new LinkedHashMap<>();
        for (String remotePath : remotePaths.stream().sorted(Comparator.naturalOrder()).collect(Collectors.toList())) {
            String relative = switch (target.remoteOs()) {
                case LINUX -> linuxRemotePathToRelative(remotePath);
                case WINDOWS_OPENSSH -> windowsSftpPathToRelative(remotePath);
            };
            Path local = localRoot.resolve(relative);
            mapping.put(remotePath, local);
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
     * /d:/apps/cfg/a.properties -> d/apps/cfg/a.properties
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
     * Accepts either:
     * - D:\apps\cfg
     * - /d:/apps/cfg
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

        // Reuse one connection per host/user/port
        opts.add("-o");
        opts.add("ControlMaster=auto");
        opts.add("-o");
        opts.add("ControlPersist=5m");
        opts.add("-o");
        opts.add("ControlPath=" + controlPathDir.resolve("mux-%C"));

        // Non-interactive
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

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting command: " + command, e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Command timed out: " + String.join(" ", command));
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