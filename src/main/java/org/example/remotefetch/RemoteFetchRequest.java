package org.example.remotefetch;

import java.util.List;

/**
 * Per-server fetch plan. Allows each host to define its own explicit file list and search roots.
 */
public record RemoteFetchRequest(
        RemoteServer server,
        List<RemoteFileTask> fileTasks,
        List<RemoteSearchTask> searchTasks
) {
    public RemoteFetchRequest {
        if (server == null) {
            throw new IllegalArgumentException("Server is required");
        }

        fileTasks = fileTasks == null ? List.of() : List.copyOf(fileTasks);
        searchTasks = searchTasks == null ? List.of() : List.copyOf(searchTasks);
    }

    public RemoteFetchRequest(RemoteServer server, List<RemoteFileTask> fileTasks) {
        this(server, fileTasks, List.of());
    }
}
