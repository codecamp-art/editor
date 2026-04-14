package org.example.remotefetch;

/**
 * One explicit remote path fetch request.
 */
public record RemoteFileTask(String remoteAbsolutePath, FetchMode fetchMode) {
    public RemoteFileTask {
        if (remoteAbsolutePath == null || remoteAbsolutePath.isBlank()) {
            throw new IllegalArgumentException("Remote path is required");
        }
        if (fetchMode == null) {
            throw new IllegalArgumentException("Fetch mode is required");
        }
    }
}
