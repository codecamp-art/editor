package org.example.comparison.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class RemoteFetchService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteFetchService.class);

    private final String remoteServer;

    private final String remoteDataPath;

    private final String localDataPath;

    public RemoteFetchService(@Value("${fix.gateway.server}") String remoteServer, @Value("${remote.data.path}") String remoteDataPath, @Value("${local.data.path}") String localDataPath) {
        this.remoteServer = remoteServer;
        this.remoteDataPath = remoteDataPath;
        this.localDataPath = localDataPath;
    }

    public Map<String, String> fetchFixDataFiles() {
        LOGGER.info("start to copy files");
        try {
            deleteContents(Paths.get(localDataPath));
        } catch (IOException e) {
            LOGGER.error("failed to create {}", localDataPath);
            throw new RuntimeException(e);
        }
        try {
            runCommand("/usr/bin/python3 WinRMConnection.py --host %s --root_path %s --filename %s --out_path %s", remoteServer, remoteDataPath.replace("\\", "\\\\"), "OUT.dat", localDataPath);
            LOGGER.info("transfer done");
        } catch (Exception e) {
            LOGGER.error("failed to transfer");
            throw new RuntimeException(e);
        }
        return readAllDataFiles(localDataPath);
    }

    protected Map<String, String> readAllDataFiles(String rootPathStr) {
        Path rootPath = Paths.get(rootPathStr);
        if (!Files.exists(rootPath)) {
            LOGGER.info("{} is not found", rootPath);
            return new HashMap<>();
        }
        try (Stream<Path> stream = Files.walk(rootPath)) {
            return stream
                    .filter(Files::isRegularFile)
                    .collect(Collectors.toMap(path -> path.getParent().getFileName().toString(), path -> {
                        try {
                            return Files.readString(path);
                        } catch (IOException e) {
                            throw new RuntimeException("failed to read data file", e);
                        }
                    }));
        } catch (IOException e) {
            throw new RuntimeException("failed to read data", e);
        }
    }

    protected void runCommand(String command, Object... args) throws Exception {
        List<String> errors = new ArrayList<>();
        ProcessBuilder builder = new ProcessBuilder();
        Process process = null;
        try {
            String[] commandArray = getOsSpecificCommand(String.format(command, args));
            builder.command(commandArray);
            process = builder.start();
            BufferedReader stdError = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String s;
            while ((s = stdError.readLine()) != null) {
                errors.add(s);
            }
            if (!errors.isEmpty()) {
                throw new Exception(String.format("failed to run %s, error: %s", String.format(command, args), String.join(System.lineSeparator(), errors)));
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new Exception(String.format("failed to run %s, error: %s", String.format(command, args), String.join(System.lineSeparator(), errors)));
            }
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    protected void deleteContents(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            Files.createDirectories(folder);
        } else if (!Files.isDirectory(folder)) {
            throw new RuntimeException("The path must be an existing dir " + folder);
        } else {
            Files.walkFileTree(folder, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (!dir.equals(folder)) {
                        Files.delete(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    /**
     * Returns OS-specific command array for executing shell commands
     * @param command the command to execute
     * @return command array suitable for ProcessBuilder
     */
    protected String[] getOsSpecificCommand(String command) {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("windows")) {
            return new String[]{"cmd", "/c", command};
        } else {
            return new String[]{"/bin/bash", "-c", command};
        }
    }
}
