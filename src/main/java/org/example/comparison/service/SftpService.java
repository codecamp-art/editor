package org.example.comparison.service;

import com.jcraft.jsch.*;
import org.example.comparison.config.ComparisonConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Service for handling SFTP operations to fetch FIX log files
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
     * Fetches FIX log files from all session directories for a specific date
     */
    public Map<String, String> fetchFixLogFiles(LocalDate date) {
        Map<String, String> logFiles = new ConcurrentHashMap<>();
        
        try {
            Session session = createSftpSession();
            ChannelSftp sftpChannel = (ChannelSftp) session.openChannel("sftp");
            sftpChannel.connect();
            
            try {
                // Get all session directories
                List<String> sessionDirectories = getSessionDirectories(sftpChannel, date);
                logger.info("Found {} session directories for date: {}", sessionDirectories.size(), date);
                
                // Process directories in parallel
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                
                for (String sessionDir : sessionDirectories) {
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        try {
                            Session threadSession = createSftpSession();
                            ChannelSftp threadSftpChannel = (ChannelSftp) threadSession.openChannel("sftp");
                            threadSftpChannel.connect();
                            
                            try {
                                String logContent = fetchLogFromDirectory(threadSftpChannel, sessionDir, date);
                                if (logContent != null && !logContent.trim().isEmpty()) {
                                    logFiles.put(sessionDir, logContent);
                                    logger.info("Successfully fetched log from session: {}", sessionDir);
                                } else {
                                    logger.warn("No log content found for session: {}", sessionDir);
                                }
                            } finally {
                                threadSftpChannel.disconnect();
                                threadSession.disconnect();
                            }
                        } catch (Exception e) {
                            logger.error("Error fetching log from session directory: {}", sessionDir, e);
                        }
                    }, executorService);
                    
                    futures.add(future);
                }
                
                // Wait for all downloads to complete
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                
            } finally {
                sftpChannel.disconnect();
                session.disconnect();
            }
            
        } catch (Exception e) {
            logger.error("Error fetching FIX log files for date: {}", date, e);
            throw new RuntimeException("Failed to fetch FIX log files", e);
        }

        logger.info("Successfully fetched {} log files for date: {}", logFiles.size(), date);
        return logFiles;
    }

    /**
     * Creates and configures SFTP session
     */
    private Session createSftpSession() throws JSchException {
        JSch jsch = new JSch();
        Session session = jsch.getSession(sftpConfig.getUsername(), sftpConfig.getHost(), sftpConfig.getPort());
        session.setPassword(sftpConfig.getPassword());
        
        // Configure session properties
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        config.put("PreferredAuthentications", "password");
        session.setConfig(config);
        
        session.setTimeout(sftpConfig.getSessionTimeout());
        session.connect(sftpConfig.getConnectionTimeout());
        
        return session;
    }

    /**
     * Gets all session directories for a specific date
     */
    private List<String> getSessionDirectories(ChannelSftp sftpChannel, LocalDate date) throws SftpException {
        List<String> sessionDirectories = new ArrayList<>();
        String dateDir = sftpConfig.getRemoteDirectory() + "/" + date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        
        try {
            sftpChannel.cd(dateDir);
            Vector<ChannelSftp.LsEntry> entries = sftpChannel.ls(".");
            
            for (ChannelSftp.LsEntry entry : entries) {
                if (entry.getAttrs().isDir() && 
                    !entry.getFilename().equals(".") && 
                    !entry.getFilename().equals("..")) {
                    sessionDirectories.add(entry.getFilename());
                }
            }
            
        } catch (SftpException e) {
            logger.warn("Directory not found or accessible: {}", dateDir);
        }
        
        return sessionDirectories;
    }

    /**
     * Fetches log content from a specific session directory
     */
    private String fetchLogFromDirectory(ChannelSftp sftpChannel, String sessionDir, LocalDate date) {
        try {
            String sessionPath = sftpConfig.getRemoteDirectory() + "/" + 
                                date.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "/" + sessionDir;
            
            sftpChannel.cd(sessionPath);
            Vector<ChannelSftp.LsEntry> files = sftpChannel.ls("*.log");
            
            StringBuilder logContent = new StringBuilder();
            
            for (ChannelSftp.LsEntry file : files) {
                if (!file.getAttrs().isDir()) {
                    try {
                        String fileContent = downloadFile(sftpChannel, file.getFilename());
                        logContent.append(fileContent);
                        logger.debug("Downloaded file: {} from session: {}", file.getFilename(), sessionDir);
                    } catch (Exception e) {
                        logger.warn("Failed to download file: {} from session: {}", file.getFilename(), sessionDir, e);
                    }
                }
            }
            
            return logContent.toString();
            
        } catch (SftpException e) {
            logger.error("Error accessing session directory: {}", sessionDir, e);
            return null;
        }
    }

    /**
     * Downloads a single file content as string
     */
    private String downloadFile(ChannelSftp sftpChannel, String filename) throws SftpException, IOException {
        try (InputStream inputStream = sftpChannel.get(filename);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            
            return outputStream.toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * Tests SFTP connection
     */
    public boolean testConnection() {
        try {
            Session session = createSftpSession();
            ChannelSftp sftpChannel = (ChannelSftp) session.openChannel("sftp");
            sftpChannel.connect();
            
            // Test basic operations
            sftpChannel.pwd();
            
            sftpChannel.disconnect();
            session.disconnect();
            
            logger.info("SFTP connection test successful");
            return true;
            
        } catch (Exception e) {
            logger.error("SFTP connection test failed", e);
            return false;
        }
    }

    /**
     * Cleanup resources
     */
    public void shutdown() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}