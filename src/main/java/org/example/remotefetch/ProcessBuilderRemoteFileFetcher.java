package org.example.remotefetch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Lightweight single-file wrapper around the native-sftp ProcessBuilder collector.
 */
public class ProcessBuilderRemoteFileFetcher extends ProcessBuilderConfigCollector {

    public ProcessBuilderRemoteFileFetcher() {
        super(1);
    }

    public InputStream fetchAsInputStream(RemoteServer server, String remotePath) throws IOException, InterruptedException {
        return fetchFileAsInputStream(server, remotePath);
    }

    public String fetchAsString(RemoteServer server, String remotePath) throws IOException, InterruptedException {
        return fetchFileAsString(server, remotePath);
    }

    public Path fetchToDisk(RemoteServer server, String remotePath, Path localRoot) throws IOException, InterruptedException {
        return fetchFileToDisk(server, remotePath, localRoot);
    }

    public byte[] fetchAsBytes(RemoteServer server, String remotePath) throws IOException, InterruptedException {
        return fetchFileAsBytes(server, remotePath);
    }
}
