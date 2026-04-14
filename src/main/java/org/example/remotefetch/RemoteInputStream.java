package com.example.sshfetch;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RemoteInputStream extends FilterInputStream {
    private final Process process;
    private final Path tempFileToDeleteOnClose;
    private final Path tempDirToDeleteOnClose;

    public RemoteInputStream(InputStream in, Process process, Path tempFileToDeleteOnClose, Path tempDirToDeleteOnClose) {
        super(in);
        this.process = process;
        this.tempFileToDeleteOnClose = tempFileToDeleteOnClose;
        this.tempDirToDeleteOnClose = tempDirToDeleteOnClose;
    }

    @Override
    public void close() throws IOException {
        IOException first = null;

        try {
            super.close();
        } catch (IOException e) {
            first = e;
        }

        if (process != null) {
            try {
                int exit = process.waitFor();
                if (exit != 0) {
                    byte[] err = process.getErrorStream().readAllBytes();
                    IOException e = new IOException(
                            "Remote process exited with code " + exit + ", stderr=" + new String(err));
                    if (first != null) {
                        e.addSuppressed(first);
                    }
                    throw e;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                IOException io = new IOException("Interrupted while waiting for remote process", e);
                if (first != null) {
                    io.addSuppressed(first);
                }
                throw io;
            } finally {
                process.destroyForcibly();
            }
        }

        try {
            if (tempFileToDeleteOnClose != null) {
                Files.deleteIfExists(tempFileToDeleteOnClose);
            }
            if (tempDirToDeleteOnClose != null) {
                Files.deleteIfExists(tempDirToDeleteOnClose);
            }
        } catch (IOException e) {
            if (first != null) {
                e.addSuppressed(first);
            }
            throw e;
        }

        if (first != null) {
            throw first;
        }
    }
}