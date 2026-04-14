package org.example.remotefetch;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Separate ProcessBuilder solution for direct single-file fetches from remote Linux/Windows hosts.
 * Uses local ssh/sshpass binaries and supports fetching to disk or in-memory (InputStream/String).
 */
public class ProcessBuilderRemoteFileFetcher {

    private static final long COMMAND_TIMEOUT_MS = 20_000;

    public InputStream fetchAsInputStream(RemoteServer server, String remotePath) throws IOException, InterruptedException {
        return new ByteArrayInputStream(fetchAsBytes(server, remotePath));
    }

    public String fetchAsString(RemoteServer server, String remotePath) throws IOException, InterruptedException {
        return new String(fetchAsBytes(server, remotePath), StandardCharsets.UTF_8);
    }

    public Path fetchToDisk(RemoteServer server, String remotePath, Path localRoot) throws IOException, InterruptedException {
        byte[] bytes = fetchAsBytes(server, remotePath);
        Path localPath = RemotePathMapper.toLocalPath(localRoot, server.platform(), remotePath);
        if (localPath.getParent() != null) {
            Files.createDirectories(localPath.getParent());
        }
        Files.write(localPath, bytes);
        return localPath;
    }

    public byte[] fetchAsBytes(RemoteServer server, String remotePath) throws IOException, InterruptedException {
        String remoteCommand = buildReadCommand(server.platform(), remotePath);
        CommandResult result = runCommand(buildSshCommand(server, remoteCommand), COMMAND_TIMEOUT_MS);
        if (result.exitCode != 0) {
            throw new IOException("Failed to fetch remote file " + remotePath + ": " + result.stderrText());
        }
        return result.stdout;
    }

    private String buildReadCommand(RemotePlatform platform, String remotePath) {
        if (platform == RemotePlatform.WINDOWS) {
            return "powershell -NoProfile -NonInteractive -Command \"$p='" + psQuote(remotePath)
                    + "'; if (Test-Path -LiteralPath $p -PathType Leaf) { Get-Content -LiteralPath $p -Raw } else { exit 2 }\"";
        }

        return "cat " + shellQuote(remotePath);
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

    protected static final class CommandResult {
        private final int exitCode;
        private final byte[] stdout;
        private final byte[] stderr;

        protected CommandResult(int exitCode, byte[] stdout, byte[] stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        private String stderrText() {
            return new String(stderr, StandardCharsets.UTF_8).trim();
        }
    }
}
