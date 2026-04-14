package org.example.remotefetch;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DownloadResult {
    private final Map<String, Path> downloaded;
    private final List<String> missing;
    private final List<String> failed;

    public DownloadResult(Map<String, Path> downloaded, List<String> missing, List<String> failed) {
        this.downloaded = Objects.requireNonNull(downloaded, "downloaded");
        this.missing = Objects.requireNonNull(missing, "missing");
        this.failed = Objects.requireNonNull(failed, "failed");
    }

    public Map<String, Path> downloaded() {
        return Collections.unmodifiableMap(downloaded);
    }

    public List<String> missing() {
        return Collections.unmodifiableList(missing);
    }

    public List<String> failed() {
        return Collections.unmodifiableList(failed);
    }
}