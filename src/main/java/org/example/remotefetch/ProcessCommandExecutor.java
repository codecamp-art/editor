package org.example.remotefetch;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ProcessCommandExecutor implements CommandExecutor {

    @Override
    public ExecResult run(List<String> command, Duration timeout) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting process", e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Command timed out: " + String.join(" ", command));
        }

        return new ExecResult(
                process.exitValue(),
                process.getInputStream().readAllBytes(),
                process.getErrorStream().readAllBytes()
        );
    }
}