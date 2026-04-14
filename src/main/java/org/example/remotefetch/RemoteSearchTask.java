package org.example.remotefetch;

/**
 * Recursive search request under a root folder.
 * filenamePattern is a regex (e.g. "(?i).*\\.(ini|conf|xml)$").
 */
public record RemoteSearchTask(
        String searchRootAbsolutePath,
        String filenamePattern,
        FetchMode fetchMode
) {
    public RemoteSearchTask {
        if (searchRootAbsolutePath == null || searchRootAbsolutePath.isBlank()) {
            throw new IllegalArgumentException("Search root is required");
        }
        if (filenamePattern == null || filenamePattern.isBlank()) {
            throw new IllegalArgumentException("Filename pattern is required");
        }
        if (fetchMode == null) {
            throw new IllegalArgumentException("Fetch mode is required");
        }
    }
}
