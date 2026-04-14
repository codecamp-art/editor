package org.example.remotefetch;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class ExampleUsage {
    public static void main(String[] args) throws Exception {
        OpenSshFileFetcher fetcher = new OpenSshFileFetcher(
                Path.of("/tmp/openssh-mux"),
                Duration.ofMinutes(5),
                false);

        SshTarget linux = new SshTarget(
                "linux1.example.com",
                22,
                "user",
                RemoteOs.LINUX);

        SshTarget windows = new SshTarget(
                "server.ab.cd.com",
                22,
                "ABC\\user",
                RemoteOs.WINDOWS_OPENSSH);

        // 1) Search exact file name under folder
        List<String> linuxFiles = fetcher.searchFilesByExactName(
                linux,
                "/opt/app/config",
                "application.properties");

        System.out.println("Linux search result:");
        linuxFiles.forEach(System.out::println);

        // 2) Search by glob under folder
        List<String> winFiles = fetcher.searchFilesByGlob(
                windows,
                "D:\\apps\\cfg",
                "*.xml");

        System.out.println("Windows search result:");
        winFiles.forEach(System.out::println);

        // 3) Download exact paths to local disk
        Map<String, Path> downloadedLinux = fetcher.downloadFiles(
                linux,
                List.of(
                        "/opt/app/config/application.properties",
                        "/opt/app/config/logback.xml"),
                Path.of("/tmp/fetched/linux1"));

        System.out.println("Downloaded Linux:");
        downloadedLinux.forEach((remote, local) -> System.out.println(remote + " -> " + local));

        // 4) Search and download
        Map<String, Path> downloadedWindows = fetcher.searchAndDownloadByExactName(
                windows,
                "D:\\apps\\cfg",
                "server.properties",
                Path.of("/tmp/fetched/server-ab-cd"));

        System.out.println("Downloaded Windows:");
        downloadedWindows.forEach((remote, local) -> System.out.println(remote + " -> " + local));

        // 5) Read remote file as String
        String text = fetcher.readFileAsString(
                linux,
                "/opt/app/config/application.properties",
                StandardCharsets.UTF_8);

        System.out.println("Remote file text:\n" + text);

        // 6) Open InputStream
        try (InputStream in = fetcher.openInputStream(linux, "/opt/app/config/application.properties")) {
            byte[] bytes = in.readAllBytes();
            System.out.println("Read bytes from remote stream: " + bytes.length);
        }

        // Optional: close master connections when the app is done
        fetcher.closeMasterConnection(linux);
        fetcher.closeMasterConnection(windows);
    }
}