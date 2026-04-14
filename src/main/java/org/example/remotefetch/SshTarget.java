package org.example.remotefetch;

import java.util.Objects;

public final class SshTarget {
    private final String host;
    private final int port;
    private final String remoteUser;
    private final RemoteOs remoteOs;

    public SshTarget(String host, int port, String remoteUser, RemoteOs remoteOs) {
        this.host = Objects.requireNonNull(host, "host");
        this.port = port <= 0 ? 22 : port;
        this.remoteUser = Objects.requireNonNull(remoteUser, "remoteUser");
        this.remoteOs = Objects.requireNonNull(remoteOs, "remoteOs");
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String remoteUser() {
        return remoteUser;
    }

    public RemoteOs remoteOs() {
        return remoteOs;
    }

    public String destination() {
        return remoteUser + "@" + host;
    }

    @Override
    public String toString() {
        return destination() + ":" + port + " [" + remoteOs + "]";
    }
}