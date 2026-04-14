package org.example.remotefetch;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RemotePathMapperTest {

    @Test
    void normalizeLinuxPath() {
        String normalized = RemotePathMapper.normalizeRemoteAbsolutePath(RemotePlatform.LINUX, "/etc/myapp/app.conf");
        assertEquals("/etc/myapp/app.conf", normalized);
    }

    @Test
    void normalizeWindowsDrivePath() {
        String normalized = RemotePathMapper.normalizeRemoteAbsolutePath(RemotePlatform.WINDOWS, "C:/ProgramData/MyApp/a.ini");
        assertEquals("/c/ProgramData/MyApp/a.ini", normalized);
    }

    @Test
    void normalizeWindowsSftpStylePath() {
        String normalized = RemotePathMapper.normalizeRemoteAbsolutePath(RemotePlatform.WINDOWS, "/D:/config/app.yml");
        assertEquals("/d/config/app.yml", normalized);
    }

    @Test
    void mapToLocalPath() {
        Path mapped = RemotePathMapper.toLocalPath(Path.of("/tmp/download"), RemotePlatform.WINDOWS, "C:/ProgramData/MyApp/a.ini");
        assertEquals(Path.of("/tmp/download/c/ProgramData/MyApp/a.ini"), mapped);
    }
}
