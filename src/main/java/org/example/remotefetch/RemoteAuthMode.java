package org.example.remotefetch;

/**
 * Authentication mechanism for SSH/SFTP.
 */
public enum RemoteAuthMode {
    PASSWORD,
    PRIVATE_KEY,
    KERBEROS
}
