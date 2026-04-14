package org.example.remotefetch;

import com.jcraft.jsch.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Vector;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * High-throughput SFTP collector for explicit files and recursive filename search.
 *
 * Design notes:
 * - Multi-server parallelism (thread pool)
 * - One SFTP connection per server worker for predictable throughput
 * - No shell commands (no ssh/scp/sftp CLI), pure JSch API
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
    ) throws JSchException, SftpException, IOException {
        FetchResult result = new FetchResult(server.id());

        Session session = openSession(server);
        ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
        sftp.connect(15_000);

        try {
            // 1) explicit files
            for (RemoteFileTask task : fileTasks) {
                fetchFile(sftp, server.platform(), task.remoteAbsolutePath(), task.fetchMode(), localRoot, result);
            }

            // 2) recursive search by filename regex
            for (RemoteSearchTask task : searchTasks) {
                Pattern pattern = Pattern.compile(task.filenamePattern());
                List<String> matched = new ArrayList<>();
                walkAndCollectMatches(sftp, task.searchRootAbsolutePath(), pattern, matched);
                for (String remotePath : matched) {
                    fetchFile(sftp, server.platform(), remotePath, task.fetchMode(), localRoot, result);
                }
            }

            return result;
        } finally {
            sftp.disconnect();
            session.disconnect();
        }
    }

    private void fetchFile(
            ChannelSftp sftp,
            RemotePlatform platform,
            String remotePath,
            FetchMode fetchMode,
            Path localRoot,
            FetchResult result
    ) throws SftpException, IOException {
        if (fetchMode == FetchMode.READ_TO_MEMORY) {
            result.addInMemoryFile(remotePath, readBytes(sftp, remotePath));
            return;
        }

        Path localPath = RemotePathMapper.toLocalPath(localRoot, platform, remotePath);
        if (localPath.getParent() != null) {
            Files.createDirectories(localPath.getParent());
        }

        sftp.get(remotePath, localPath.toString());
        result.addLocalFile(remotePath, localPath);
    }

    private byte[] readBytes(ChannelSftp sftp, String remotePath) throws SftpException, IOException {
        try (InputStream in = sftp.get(remotePath);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    @SuppressWarnings("unchecked")
    private void walkAndCollectMatches(ChannelSftp sftp, String root, Pattern filenamePattern, List<String> matched)
            throws SftpException {
        Vector<ChannelSftp.LsEntry> entries = sftp.ls(root);
        for (ChannelSftp.LsEntry entry : entries) {
            String name = entry.getFilename();
            if (".".equals(name) || "..".equals(name)) {
                continue;
            }

            String child = root.endsWith("/") ? root + name : root + "/" + name;
            if (entry.getAttrs().isDir()) {
                walkAndCollectMatches(sftp, child, filenamePattern, matched);
            } else if (filenamePattern.matcher(name).matches()) {
                matched.add(child);
            }
        }
    }

    private Session openSession(RemoteServer server) throws JSchException {
        JSch jsch = new JSch();
        Session session = jsch.getSession(server.username(), server.host(), server.port());
        session.setPassword(server.password());

        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        config.put("PreferredAuthentications", "password");
        session.setConfig(config);

        session.connect(15_000);
        return session;
    }

    public void shutdown() {
        executorService.shutdown();
    }
}
