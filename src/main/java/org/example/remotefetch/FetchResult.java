package org.example.remotefetch;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aggregates pulled files for one server.
 */
public class FetchResult {
    private final String serverId;
    private final Map<String, Path> localFiles = new ConcurrentHashMap<>();
    private final Map<String, byte[]> inMemoryFiles = new ConcurrentHashMap<>();

    public FetchResult(String serverId) {
        this.serverId = serverId;
    }

    public String getServerId() {
        return serverId;
    }

    public Map<String, Path> getLocalFiles() {
        return localFiles;
    }

    public Map<String, byte[]> getInMemoryFiles() {
        return inMemoryFiles;
    }

    public void addLocalFile(String remotePath, Path localPath) {
        localFiles.put(remotePath, localPath);
    }

    public void addInMemoryFile(String remotePath, byte[] bytes) {
        inMemoryFiles.put(remotePath, bytes);
    }
}
