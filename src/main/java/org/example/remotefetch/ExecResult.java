package org.example.remotefetch;

public final class ExecResult {
    private final int exitCode;
    private final byte[] stdout;
    private final byte[] stderr;

    public ExecResult(int exitCode, byte[] stdout, byte[] stderr) {
        this.exitCode = exitCode;
        this.stdout = stdout == null ? new byte[0] : stdout;
        this.stderr = stderr == null ? new byte[0] : stderr;
    }

    public int exitCode() {
        return exitCode;
    }

    public byte[] stdout() {
        return stdout;
    }

    public byte[] stderr() {
        return stderr;
    }
}