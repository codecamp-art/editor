# Java remote config collector (no shell ssh/sftp/scp)

This solution uses Java + JSch API directly (no shell commands) to pull config files from Linux and Windows servers.

## What it supports

1. **Many servers (Linux + Windows)** in parallel with a configurable thread pool.
2. **Path-preserving local download**: remote absolute path is kept under local root.
   - Linux `/etc/app/a.conf` -> `<localRoot>/etc/app/a.conf`
   - Windows `C:\\ProgramData\\app\\a.ini` -> `<localRoot>/c/ProgramData/app/a.ini`
3. **Read to memory** without writing to local disk (`byte[]` from SFTP stream).
4. Two input models:
   - explicit remote path list
   - recursive search under root by filename regex
5. **Authentication options**:
   - password auth
   - private key auth
   - kerberos (`gssapi-with-mic`) auth for SSH/SFTP
6. **Windows compatibility** over SSH/SFTP:
   - accepts `C:/...` style inputs
   - automatically tries `/c/...`, `/C:/...`, and `C:/...` forms used by OpenSSH SFTP servers

## Main classes

- `org.example.remotefetch.SftpConfigCollector`
- `org.example.remotefetch.RemotePathMapper`
- `org.example.remotefetch.RemoteServer`
- `org.example.remotefetch.RemoteAuthMode`
- `org.example.remotefetch.RemoteFileTask`
- `org.example.remotefetch.RemoteSearchTask`
- `org.example.remotefetch.FetchResult`

## Example use

```java
SftpConfigCollector collector = new SftpConfigCollector(16);

List<RemoteServer> servers = List.of(
    // Password auth (Linux)
    new RemoteServer("linux-a", "10.1.1.10", 22, "ops", "***", RemotePlatform.LINUX),

    // Private key auth (Windows over OpenSSH)
    RemoteServer.withPrivateKey(
        "win-a",
        "10.1.2.20",
        22,
        "Administrator",
        "/keys/win-a-id_rsa",
        null,
        RemotePlatform.WINDOWS
    ),

    // Kerberos auth (ensure kinit / JAAS/Krb5 config is available to this JVM)
    RemoteServer.withKerberos(
        "linux-kerb",
        "10.1.3.30",
        22,
        "svc_config",
        "svc_config@EXAMPLE.COM",
        RemotePlatform.LINUX
    )
);

List<RemoteFileTask> files = List.of(
    new RemoteFileTask("/etc/myapp/app.conf", FetchMode.SAVE_TO_DISK),
    new RemoteFileTask("C:/ProgramData/MyApp/runtime.properties", FetchMode.READ_TO_MEMORY)
);

List<RemoteSearchTask> searches = List.of(
    new RemoteSearchTask("/opt/myapp/conf", "(?i).*\\.(xml|conf|ini)$", FetchMode.SAVE_TO_DISK),
    new RemoteSearchTask("C:/MyApp/config", "(?i)^application\\.ya?ml$", FetchMode.READ_TO_MEMORY)
);

Map<String, FetchResult> result = collector.collect(servers, files, searches, Path.of("./downloaded-configs"));
collector.shutdown();
```
