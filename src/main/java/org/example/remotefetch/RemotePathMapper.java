package org.example.remotefetch;

import java.nio.file.Path;

/**
 * Maps remote absolute path into a safe local child path.
 */
public final class RemotePathMapper {

    private RemotePathMapper() {
    }

    public static Path toLocalPath(Path localRoot, RemotePlatform platform, String remoteAbsolutePath) {
        String normalized = normalizeRemoteAbsolutePath(platform, remoteAbsolutePath);
        String relative = normalized.startsWith("/") ? normalized.substring(1) : normalized;
        return localRoot.resolve(relative);
    }

    /**
     * Linux: /etc/app/a.conf -> /etc/app/a.conf
     * Windows: C:\\ProgramData\\a.ini or /C:/ProgramData/a.ini -> /c/ProgramData/a.ini
     */
    public static String normalizeRemoteAbsolutePath(RemotePlatform platform, String remoteAbsolutePath) {
        if (remoteAbsolutePath == null || remoteAbsolutePath.isBlank()) {
            throw new IllegalArgumentException("Remote path is required");
        }

        String path = remoteAbsolutePath.trim().replace('\\', '/');

        if (platform == RemotePlatform.WINDOWS) {
            path = normalizeWindowsPath(path);
        }

        return path.startsWith("/") ? path : "/" + path;
    }

    private static String normalizeWindowsPath(String path) {
        // /C:/dir/file or C:/dir/file => /c/dir/file
        String candidate = path;
        if (candidate.startsWith("/")) {
            candidate = candidate.substring(1);
        }

        if (candidate.length() >= 2 && candidate.charAt(1) == ':') {
            String drive = String.valueOf(Character.toLowerCase(candidate.charAt(0)));
            String rest = candidate.substring(2);
            if (!rest.startsWith("/")) {
                rest = "/" + rest;
            }
            return "/" + drive + rest;
        }

        // Already /c/... style.
        return path;
    }
}
