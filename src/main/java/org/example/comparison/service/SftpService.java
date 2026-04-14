package org.example.comparison.service;

import org.example.comparison.config.ComparisonConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Service for handling remote SSH/SFTP-style operations to fetch FIX log files.
 * Uses native shell commands via ProcessBuilder instead of JSch.
 */
@Service
public class SftpService {

    private static final Logger logger = LoggerFactory.getLogger(SftpService.class);

    private final ComparisonConfig.SftpConfig sftpConfig;
    private final ExecutorService executorService;

    public SftpService(ComparisonConfig comparisonConfig) {
        this.sftpConfig = comparisonConfig.getSftp();
        this.executorService = Executors.newFixedThreadPool(5); // Configurable thread pool
    }

    /**
     * Fetches FIX log files from all session directories for a specific date.
     */
    public Map<String, String> fetchFixLogFiles(LocalDate date) {
        Map<String, String> logFiles = new ConcurrentHashMap<>();

        try {
            List<String> sessionDirectories = getSessionDirectories(date);
            logger.info("Found {} session directories for date: {}", sessionDirectories.size(), date);

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (String sessionDir : sessionDirectories) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        String logContent = fetchLogFromDirectory(sessionDir, date);
                        if (logContent != null && !logContent.trim().isEmpty()) {
                            logFiles.put(sessionDir, logContent);
                            logger.info("Successfully fetched log from session: {}", sessionDir);
                        } else {
                            logger.warn("No log content found for session: {}", sessionDir);
                        }
                    } catch (Exception e) {
                        logger.error("Error fetching log from session directory: {}", sessionDir, e);
                    }
                }, executorService);
                futures.add(future);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            logger.error("Error fetching FIX log files for date: {}", date, e);
            throw new RuntimeException("Failed to fetch FIX log files", e);
        }

        logger.info("Successfully fetched {} log files for date: {}", logFiles.size(), date);
        return logFiles;
    }

    private List<String> getSessionDirectories(LocalDate date) {
        String dateDir = sftpConfig.getRemoteDirectory() + "/" + date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String remoteCommand = "if [ -d " + shellQuote(dateDir) + " ]; then find " + shellQuote(dateDir)
                + " -mindepth 1 -maxdepth 1 -type d -printf '%f\\n'; fi";

        try {
            String output = runSshCommand(remoteCommand);
            if (output == null || output.isBlank()) {
                return Collections.emptyList();
            }
            return output.lines()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.warn("Directory not found or accessible: {}", dateDir, e);
            return Collections.emptyList();
        }
    }

    private String fetchLogFromDirectory(String sessionDir, LocalDate date) {
        String sessionPath = sftpConfig.getRemoteDirectory() + "/"
                + date.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "/" + sessionDir;

        String remoteCommand = "if [ -d " + shellQuote(sessionPath)
                + " ]; then find " + shellQuote(sessionPath)
                + " -maxdepth 1 -type f -name '*.log' -print0 | xargs -0 -r cat; fi";

        try {
            return runSshCommand(remoteCommand);
        } catch (Exception e) {
            logger.error("Error accessing session directory: {}", sessionDir, e);
            return null;
        }
    }

    /**
     * Tests SSH connectivity with configured host.
     */
    public boolean testConnection() {
        try {
            runSshCommand("pwd >/dev/null");
            logger.info("SFTP/SSH connection test successful");
            return true;
        } catch (Exception e) {
            logger.error("SFTP/SSH connection test failed", e);
            return false;
        }
    }

    private String runSshCommand(String remoteCommand) throws IOException, InterruptedException {
        List<String> command = buildSshCommand(remoteCommand);
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();

        boolean finished = process.waitFor(Math.max(1, sftpConfig.getSessionTimeout()), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("SSH command timed out");
        }

        int exitCode = process.exitValue();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        if (exitCode != 0) {
            throw new IOException("SSH command failed (exit=" + exitCode + "): " + stderr.trim());
        }

        return stdout;
    }

    private List<String> buildSshCommand(String remoteCommand) {
        List<String> command = new ArrayList<>();
        if (sftpConfig.getPassword() != null && !sftpConfig.getPassword().isBlank()) {
            command.add("sshpass");
            command.add("-p");
            command.add(sftpConfig.getPassword());
        }

        command.add("ssh");
        command.add("-p");
        command.add(String.valueOf(sftpConfig.getPort()));
        command.add("-o");
        command.add("StrictHostKeyChecking=no");
        command.add("-o");
        command.add("UserKnownHostsFile=/dev/null");
        command.add("-o");
        command.add("ConnectTimeout=" + Math.max(1, sftpConfig.getConnectionTimeout() / 1000));
        command.add(sftpConfig.getUsername() + "@" + sftpConfig.getHost());
        command.add(remoteCommand);
        return command;
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * Cleanup resources.
     */
    public void shutdown() {
        if (!executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}
