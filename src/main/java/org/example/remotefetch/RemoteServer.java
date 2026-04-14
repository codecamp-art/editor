package org.example.remotefetch;

/**
 * Connection descriptor for one remote host.
 */
public record RemoteServer(
        String id,
        String host,
        int port,
        String username,
        String password,
        RemotePlatform platform
) {
    public RemoteServer {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Server id is required");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Host is required");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username is required");
        }
        if (platform == null) {
            throw new IllegalArgumentException("Platform is required");
        }
    }
}
