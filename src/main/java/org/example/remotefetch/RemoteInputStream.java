package org.example.remotefetch;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RemoteInputStream extends FilterInputStream {
    private final Process process;
    private final Path tempFileToDeleteOnClose;

    public RemoteInputStream(InputStream in, Process process, Path tempFileToDeleteOnClose) {
        super(in);
        this.process = process;
        this.tempFileToDeleteOnClose = tempFileToDeleteOnClose;
    }

    @Override
    public void close() throws IOException {
        IOException suppressed = null;
        try {
            super.close();
        } catch (IOException e) {
            suppressed = e;
        }

        if (process != null) {
            try {
                int exit = process.waitFor();
                if (exit != 0) {
                    byte[] err = process.getErrorStream().readAllBytes();
                    IOException e = new IOException(
                            "Remote process exited with code " + exit + ", stderr=" + new String(err));
                    if (suppressed != null) {
                        e.addSuppressed(suppressed);
                    }
                    throw e;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                IOException io = new IOException("Interrupted while waiting remote process to finish", e);
                if (suppressed != null) {
                    io.addSuppressed(suppressed);
                }
                throw io;
            } finally {
                process.destroyForcibly();
            }
        }

        if (tempFileToDeleteOnClose != null) {
            try {
                Files.deleteIfExists(tempFileToDeleteOnClose);
            } catch (IOException e) {
                if (suppressed != null) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            }
        }

        if (suppressed != null) {
            throw suppressed;
        }
    }
}