package org.example.remotefetch;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/**
 * High-throughput collector for explicit files and recursive filename search.
 * Uses JSch SFTP session directly (no shell commands).
 */
public class SftpConfigCollector {

    private static final int SFTP_BUFFER_SIZE = 16 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 15_000;

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
    ) throws IOException {
        FetchResult result = new FetchResult(server.id());

        try (SftpSession sftpSession = openSftpSession(server)) {
            for (RemoteFileTask task : fileTasks) {
                fetchFile(sftpSession.channelSftp, server.platform(), task.remoteAbsolutePath(), task.fetchMode(), localRoot, result);
            }

            for (RemoteSearchTask task : searchTasks) {
                Pattern pattern = Pattern.compile(task.filenamePattern());
                List<String> matched = walkAndCollectMatches(
                        sftpSession.channelSftp,
                        server.platform(),
                        task.searchRootAbsolutePath(),
                        pattern
                );
                for (String remotePath : matched) {
                    fetchFile(sftpSession.channelSftp, server.platform(), remotePath, task.fetchMode(), localRoot, result);
                }
            }
        }

        return result;
    }

    private void fetchFile(
            ChannelSftp channelSftp,
            RemotePlatform platform,
            String remotePath,
            FetchMode fetchMode,
            Path localRoot,
            FetchResult result
    ) throws IOException {
        byte[] content = readBytes(channelSftp, platform, remotePath);

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

    private byte[] readBytes(ChannelSftp channelSftp, RemotePlatform platform, String remotePath) throws IOException {
        String normalizedPath = RemotePathMapper.normalizeRemoteAbsolutePath(platform, remotePath);

        for (String candidate : remotePathCandidates(platform, normalizedPath)) {
            try (InputStream in = channelSftp.get(candidate);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[SFTP_BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            } catch (SftpException e) {
                if (!isNoSuchFile(e)) {
                    throw new IOException("Failed to read remote file: " + candidate, e);
                }
            }
        }

        throw new IOException("Remote file not found: " + remotePath);
    }

    private List<String> walkAndCollectMatches(
            ChannelSftp channelSftp,
            RemotePlatform platform,
            String root,
            Pattern filenamePattern
    ) throws IOException {
        String normalizedRoot = RemotePathMapper.normalizeRemoteAbsolutePath(platform, root);
        List<String> matches = new ArrayList<>();

        IOException lastError = null;
        for (String candidateRoot : remotePathCandidates(platform, normalizedRoot)) {
            try {
                walkDirectory(channelSftp, candidateRoot, filenamePattern, matches);
                return matches;
            } catch (IOException e) {
                lastError = e;
            }
        }

        if (lastError != null) {
            throw lastError;
        }

        return List.of();
    }

    private void walkDirectory(
            ChannelSftp channelSftp,
            String directory,
            Pattern filenamePattern,
            List<String> matches
    ) throws IOException {
        Vector<ChannelSftp.LsEntry> entries;
        try {
            @SuppressWarnings("unchecked")
            Vector<ChannelSftp.LsEntry> listed = channelSftp.ls(directory);
            entries = listed;
        } catch (SftpException e) {
            if (isNoSuchFile(e)) {
                return;
            }
            throw new IOException("Failed to list directory: " + directory, e);
        }

        entries.sort(Comparator.comparing(ChannelSftp.LsEntry::getFilename));
        for (ChannelSftp.LsEntry entry : entries) {
            String name = entry.getFilename();
            if (".".equals(name) || "..".equals(name)) {
                continue;
            }

            String child = directory.endsWith("/") ? directory + name : directory + "/" + name;
            SftpATTRS attrs = entry.getAttrs();
            if (attrs.isDir()) {
                walkDirectory(channelSftp, child, filenamePattern, matches);
            } else if (filenamePattern.matcher(name).matches()) {
                matches.add(child);
            }
        }
    }

    private SftpSession openSftpSession(RemoteServer server) throws IOException {
        try {
            JSch jsch = new JSch();

            if (server.authMode() == RemoteAuthMode.PRIVATE_KEY) {
                if (server.privateKeyPassphrase() != null && !server.privateKeyPassphrase().isBlank()) {
                    jsch.addIdentity(server.privateKeyPath(), server.privateKeyPassphrase());
                } else {
                    jsch.addIdentity(server.privateKeyPath());
                }
            }

            Session session = jsch.getSession(server.username(), server.host(), server.port());
            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");

            if (server.authMode() == RemoteAuthMode.KERBEROS) {
                config.put("PreferredAuthentications", "gssapi-with-mic");
                config.put("GSSAPIAuthentication", "yes");
                if (server.kerberosPrincipal() != null && !server.kerberosPrincipal().isBlank()) {
                    session.setConfig("userauth.gssapi-with-mic", "com.jcraft.jsch.UserAuthGSSAPIWithMIC");
                }
            } else if (server.authMode() == RemoteAuthMode.PRIVATE_KEY) {
                config.put("PreferredAuthentications", "publickey,password");
            } else {
                session.setPassword(server.password());
                config.put("PreferredAuthentications", "password,publickey");
            }

            session.setConfig(config);
            session.connect(CONNECT_TIMEOUT_MS);

            Channel channel = session.openChannel("sftp");
            channel.connect(CONNECT_TIMEOUT_MS);

            return new SftpSession(session, (ChannelSftp) channel);
        } catch (JSchException e) {
            throw new IOException("Failed to open SFTP connection to " + server.id(), e);
        }
    }

    private List<String> remotePathCandidates(RemotePlatform platform, String normalizedPath) {
        if (platform != RemotePlatform.WINDOWS) {
            return List.of(normalizedPath);
        }

        if (!normalizedPath.matches("/[a-zA-Z]/.*")) {
            return List.of(normalizedPath);
        }

        String drive = normalizedPath.substring(1, 2).toUpperCase();
        String rest = normalizedPath.substring(2);

        List<String> candidates = new ArrayList<>();
        candidates.add(normalizedPath);        // /c/ProgramData/file
        candidates.add("/" + drive + ":" + rest); // /C:/ProgramData/file
        candidates.add(drive + ":" + rest);  // C:/ProgramData/file
        return candidates;
    }

    private boolean isNoSuchFile(SftpException e) {
        return e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE;
    }

    public void shutdown() {
        executorService.shutdown();
    }

    private static final class SftpSession implements AutoCloseable {
        private final Session session;
        private final ChannelSftp channelSftp;

        private SftpSession(Session session, ChannelSftp channelSftp) {
            this.session = session;
            this.channelSftp = channelSftp;
        }

        @Override
        public void close() {
            if (channelSftp != null && channelSftp.isConnected()) {
                channelSftp.disconnect();
            }
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }
}
