package com.example.tdsweb.tds;

import com.example.tdsweb.config.TdsProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class NativeSdkValidator {
    private static final List<String> REQUIRED_FILES = List.of(
        "include/tds_api.h",
        "include/tds_api_define.h",
        "include/tds_api_struct_type.h",
        "linux_x86_64/libtds_api.so",
        "linux_x86_64/cpack.dat"
    );

    private final TdsProperties properties;

    public NativeSdkValidator(TdsProperties properties) {
        this.properties = properties;
    }

    public void validate() {
        Path root = properties.getSdkRoot().normalize();
        List<String> missing = REQUIRED_FILES.stream()
            .filter(relative -> !Files.isRegularFile(root.resolve(relative)))
            .toList();
        if (!missing.isEmpty()) {
            throw new TdsClientException("missing TDS SDK file(s) under " + root + ": " + String.join(", ", missing));
        }
        if (!Files.isRegularFile(properties.getNativeAdapter().getExecutable())) {
            throw new TdsClientException(
                "missing native TDS adapter executable: " + properties.getNativeAdapter().getExecutable());
        }
    }
}
