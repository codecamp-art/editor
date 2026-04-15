package org.example.remotefetch;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

@FunctionalInterface
public interface CommandExecutor {
    ExecResult run(List<String> command, Duration timeout) throws IOException;
}