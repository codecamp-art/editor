package com.example.tdsweb.tds;

import com.example.tdsweb.config.TdsProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class NativeSdkValidator {
    private static final List<String> COMMON_REQUIRED_FILES = List.of(
        "include/tds_api.h",
        "include/tds_api_define.h",
        "include/tds_api_struct_type.h"
    );

    private final TdsProperties properties;
    private final Platform platform;

    public NativeSdkValidator(TdsProperties properties) {
        this(properties, Platform.current());
    }

    NativeSdkValidator(TdsProperties properties, Platform platform) {
        this.properties = properties;
        this.platform = platform;
    }

    public void validate() {
        Path root = properties.getSdkRoot().normalize();
        List<String> missing = requiredFiles().stream()
            .filter(relative -> !Files.isRegularFile(root.resolve(relative)))
            .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        if (platform == Platform.WINDOWS && !hasWindowsRuntimeDll(root)) {
            missing.add("win32/*.dll");
        }
        if (!missing.isEmpty()) {
            throw new TdsClientException("missing TDS SDK file(s) under " + root + ": " + String.join(", ", missing));
        }
        if (!Files.isRegularFile(properties.getNativeAdapter().getExecutable())) {
            throw new TdsClientException(
                "missing native TDS adapter executable: " + properties.getNativeAdapter().getExecutable());
        }
    }

    private List<String> requiredFiles() {
        List<String> required = new ArrayList<>(COMMON_REQUIRED_FILES);
        if (platform == Platform.WINDOWS) {
            required.add("win32/tds_api.lib");
            required.add("win32/cpack.dat");
        } else {
            required.add("linux_x86_64/libtds_api.so");
            required.add("linux_x86_64/cpack.dat");
        }
        return required;
    }

    private static boolean hasWindowsRuntimeDll(Path root) {
        Path win32 = root.resolve("win32");
        if (!Files.isDirectory(win32)) {
            return false;
        }
        try (var files = Files.list(win32)) {
            return files.anyMatch(file -> Files.isRegularFile(file)
                && file.getFileName().toString().toLowerCase().endsWith(".dll"));
        } catch (java.io.IOException ex) {
            return false;
        }
    }

    enum Platform {
        LINUX,
        WINDOWS;

        static Platform current() {
            String osName = System.getProperty("os.name", "").toLowerCase();
            if (osName.contains("windows")) {
                return WINDOWS;
            }
            return LINUX;
        }
    }
}
