package org.example.remotefetch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RemoteServerTest {

    @Test
    void defaultsToPasswordModeForBackwardCompatibility() {
        RemoteServer server = new RemoteServer("id", "host", 22, "user", "secret", RemotePlatform.LINUX);
        assertEquals(RemoteAuthMode.PASSWORD, server.authMode());
    }

    @Test
    void rejectsMissingPasswordWhenPasswordModeIsUsed() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new RemoteServer("id", "host", 22, "user", "", RemotePlatform.LINUX, RemoteAuthMode.PASSWORD, null, null, null));
        assertEquals("Password is required for PASSWORD auth mode", ex.getMessage());
    }

    @Test
    void buildsKerberosServerDescriptor() {
        RemoteServer server = RemoteServer.withKerberos(
                "id",
                "host",
                22,
                "user",
                "user@EXAMPLE.COM",
                RemotePlatform.WINDOWS
        );

        assertEquals(RemoteAuthMode.KERBEROS, server.authMode());
        assertEquals("user@EXAMPLE.COM", server.kerberosPrincipal());
    }
}
