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
                "/tmp/sshmux",
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

        // 1) Search by exact name under folder
        List<String> linuxFound = fetcher.searchFilesByExactName(
                linux,
                "/opt/配置",
                "application.properties");

        System.out.println("Linux found:");
        linuxFound.forEach(System.out::println);

        // 2) Search by glob under folder
        List<String> windowsFound = fetcher.searchFilesByGlob(
                windows,
                "D:\\应用\\配置",
                "*.xml");

        System.out.println("Windows found:");
        windowsFound.forEach(System.out::println);

        // 3) Check explicit paths first
        Map<String, Boolean> linuxExists = fetcher.checkFilesExist(
                linux,
                List.of(
                        "/opt/配置/application.properties",
                        "/opt/配置/missing.properties"));

        System.out.println("Linux existence:");
        linuxExists.forEach((k, v) -> System.out.println(k + " -> " + v));

        // 4) Download explicit paths
        DownloadResult linuxDownload = fetcher.downloadFiles(
                linux,
                List.of(
                        "/opt/配置/application.properties",
                        "/opt/配置/logback.xml",
                        "/opt/配置/missing.txt"),
                Path.of("/tmp/fetched/linux1"));

        System.out.println("Linux downloaded:");
        linuxDownload.downloaded().forEach((r, l) -> System.out.println(r + " -> " + l));
        System.out.println("Linux missing: " + linuxDownload.missing());
        System.out.println("Linux failed: " + linuxDownload.failed());

        // 5) Search then download
        DownloadResult windowsDownload = fetcher.searchAndDownloadByExactName(
                windows,
                "D:\\应用\\配置",
                "server.properties",
                Path.of("/tmp/fetched/windows1"));

        System.out.println("Windows downloaded:");
        windowsDownload.downloaded().forEach((r, l) -> System.out.println(r + " -> " + l));
        System.out.println("Windows missing: " + windowsDownload.missing());
        System.out.println("Windows failed: " + windowsDownload.failed());

        // 6) Read small text file as String
        String linuxText = fetcher.readFileAsString(
                linux,
                "/opt/配置/application.properties",
                StandardCharsets.UTF_8);

        System.out.println(linuxText);

        // 7) Open InputStream
        try (InputStream in = fetcher.openInputStream(windows, "/d:/应用/配置/server.properties")) {
            byte[] data = in.readAllBytes();
            System.out.println("Read bytes = " + data.length);
        }

        fetcher.closeMasterConnection(linux);
        fetcher.closeMasterConnection(windows);
    }
}