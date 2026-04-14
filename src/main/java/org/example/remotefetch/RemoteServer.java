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
        RemotePlatform platform,
        RemoteAuthMode authMode,
        String privateKeyPath,
        String privateKeyPassphrase,
        String kerberosPrincipal
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

        RemoteAuthMode resolvedAuthMode = authMode == null ? RemoteAuthMode.PASSWORD : authMode;
        authMode = resolvedAuthMode;

        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be in range 1..65535");
        }

        if (resolvedAuthMode == RemoteAuthMode.PASSWORD && (password == null || password.isBlank())) {
            throw new IllegalArgumentException("Password is required for PASSWORD auth mode");
        }
        if (resolvedAuthMode == RemoteAuthMode.PRIVATE_KEY && (privateKeyPath == null || privateKeyPath.isBlank())) {
            throw new IllegalArgumentException("privateKeyPath is required for PRIVATE_KEY auth mode");
        }
    }

    public RemoteServer(
            String id,
            String host,
            int port,
            String username,
            String password,
            RemotePlatform platform
    ) {
        this(id, host, port, username, password, platform, RemoteAuthMode.PASSWORD, null, null, null);
    }

    public static RemoteServer withPrivateKey(
            String id,
            String host,
            int port,
            String username,
            String privateKeyPath,
            String privateKeyPassphrase,
            RemotePlatform platform
    ) {
        return new RemoteServer(
                id,
                host,
                port,
                username,
                null,
                platform,
                RemoteAuthMode.PRIVATE_KEY,
                privateKeyPath,
                privateKeyPassphrase,
                null
        );
    }

    public static RemoteServer withKerberos(
            String id,
            String host,
            int port,
            String username,
            String kerberosPrincipal,
            RemotePlatform platform
    ) {
        return new RemoteServer(
                id,
                host,
                port,
                username,
                null,
                platform,
                RemoteAuthMode.KERBEROS,
                null,
                null,
                kerberosPrincipal
        );
    }
}
