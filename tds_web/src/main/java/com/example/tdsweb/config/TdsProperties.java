package com.example.tdsweb.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "tds")
public class TdsProperties {
    private Mode mode = Mode.STUB;
    private String sdkRoot = "../tds";
    @Valid
    private NativeAdapter nativeAdapter = new NativeAdapter();
    @Valid
    private List<DrtpEndpoint> drtpEndpoints = new ArrayList<>();
    @Valid
    private Vault vault = new Vault();
    private String user = "10000";
    private int reqTimeoutMs = 300000;
    private int logLevel = 2000;
    private boolean klgEnable = false;
    private int functionNo = 20100;

    public enum Mode {
        STUB,
        NATIVE
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public Path getSdkRoot() {
        return Path.of(sdkRoot);
    }

    public void setSdkRoot(String sdkRoot) {
        this.sdkRoot = sdkRoot;
    }

    public NativeAdapter getNativeAdapter() {
        return nativeAdapter;
    }

    public void setNativeAdapter(NativeAdapter nativeAdapter) {
        this.nativeAdapter = nativeAdapter;
    }

    public List<DrtpEndpoint> getDrtpEndpoints() {
        return drtpEndpoints;
    }

    public void setDrtpEndpoints(List<DrtpEndpoint> drtpEndpoints) {
        this.drtpEndpoints = drtpEndpoints;
    }

    public Vault getVault() {
        return vault;
    }

    public void setVault(Vault vault) {
        this.vault = vault;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public int getReqTimeoutMs() {
        return reqTimeoutMs;
    }

    public void setReqTimeoutMs(int reqTimeoutMs) {
        this.reqTimeoutMs = reqTimeoutMs;
    }

    public int getLogLevel() {
        return logLevel;
    }

    public void setLogLevel(int logLevel) {
        this.logLevel = logLevel;
    }

    public boolean isKlgEnable() {
        return klgEnable;
    }

    public void setKlgEnable(boolean klgEnable) {
        this.klgEnable = klgEnable;
    }

    public int getFunctionNo() {
        return functionNo;
    }

    public void setFunctionNo(int functionNo) {
        this.functionNo = functionNo;
    }

    public String formattedDrtpEndpoints() {
        List<String> values = new ArrayList<>();
        for (DrtpEndpoint endpoint : drtpEndpoints) {
            values.add(endpoint.host() + ":" + endpoint.port());
        }
        return String.join(",", values);
    }

    public static class NativeAdapter {
        private String executable = "build/native/tds_adapter";

        public Path getExecutable() {
            return Path.of(executable);
        }

        public void setExecutable(String executable) {
            this.executable = executable;
        }
    }

    public static class Vault {
        private String address = "";
        private String namespace = "";
        private String secretEngine = "";
        private String secretPath = "";
        private String secretKey = "password";
        @Min(1)
        private int connectTimeoutMs = 15000;
        @Min(1)
        private int readTimeoutMs = 60000;

        public String getAddress() {
            return address;
        }

        public void setAddress(String address) {
            this.address = address;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String getSecretEngine() {
            return secretEngine;
        }

        public void setSecretEngine(String secretEngine) {
            this.secretEngine = secretEngine;
        }

        public String getSecretPath() {
            return secretPath;
        }

        public void setSecretPath(String secretPath) {
            this.secretPath = secretPath;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }
    }

    public record DrtpEndpoint(@NotBlank String host, @Min(1) int port) {
    }
}
