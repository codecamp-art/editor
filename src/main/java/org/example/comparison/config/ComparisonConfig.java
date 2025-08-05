package org.example.comparison.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Configuration properties for the comparison application
 */
@Component
@ConfigurationProperties(prefix = "comparison")
public class ComparisonConfig {

    @NotNull
    private DatabaseConfig primary;

    @NotNull
    private DatabaseConfig secondary;

    @NotNull
    private SftpConfig sftp;

    @NotNull
    private EmailConfig email;

    @NotNull
    private ReportConfig report;

    // Nested configuration classes
    public static class DatabaseConfig {
        @NotBlank
        private String url;
        private String username;  // Optional for Kerberos authentication
        private String password;  // Optional for Kerberos authentication
        @NotBlank
        private String driverClassName;
        private boolean useKerberos = false;  // Flag to enable Kerberos authentication
        private String serviceName;  // Oracle service name for Kerberos
        private String realm;  // Kerberos realm (optional)
        private boolean mutualAuthentication = true;  // Kerberos mutual authentication

        // Getters and Setters
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getDriverClassName() { return driverClassName; }
        public void setDriverClassName(String driverClassName) { this.driverClassName = driverClassName; }
        public boolean isUseKerberos() { return useKerberos; }
        public void setUseKerberos(boolean useKerberos) { this.useKerberos = useKerberos; }
        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        public String getRealm() { return realm; }
        public void setRealm(String realm) { this.realm = realm; }
        public boolean isMutualAuthentication() { return mutualAuthentication; }
        public void setMutualAuthentication(boolean mutualAuthentication) { this.mutualAuthentication = mutualAuthentication; }
    }

    public static class SftpConfig {
        @NotBlank
        private String host;
        private int port = 22;
        @NotBlank
        private String username;
        @NotBlank
        private String password;
        @NotBlank
        private String remoteDirectory;
        private int connectionTimeout = 30000;
        private int sessionTimeout = 30000;

        // Getters and Setters
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getRemoteDirectory() { return remoteDirectory; }
        public void setRemoteDirectory(String remoteDirectory) { this.remoteDirectory = remoteDirectory; }
        public int getConnectionTimeout() { return connectionTimeout; }
        public void setConnectionTimeout(int connectionTimeout) { this.connectionTimeout = connectionTimeout; }
        public int getSessionTimeout() { return sessionTimeout; }
        public void setSessionTimeout(int sessionTimeout) { this.sessionTimeout = sessionTimeout; }
    }

    public static class EmailConfig {
        @NotBlank
        private String from;
        @NotNull
        private List<String> to;
        private List<String> cc;
        private String subject = "Daily FIX Log Comparison Report";

        // Getters and Setters
        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }
        public List<String> getTo() { return to; }
        public void setTo(List<String> to) { this.to = to; }
        public List<String> getCc() { return cc; }
        public void setCc(List<String> cc) { this.cc = cc; }
        public String getSubject() { return subject; }
        public void setSubject(String subject) { this.subject = subject; }
    }

    public static class ReportConfig {
        private String outputDirectory = "./reports";
        private String excelFileName = "fix_comparison_report_{date}.xlsx";
        private boolean deleteOldReports = true;
        private int keepReportsForDays = 30;

        // Getters and Setters
        public String getOutputDirectory() { return outputDirectory; }
        public void setOutputDirectory(String outputDirectory) { this.outputDirectory = outputDirectory; }
        public String getExcelFileName() { return excelFileName; }
        public void setExcelFileName(String excelFileName) { this.excelFileName = excelFileName; }
        public boolean isDeleteOldReports() { return deleteOldReports; }
        public void setDeleteOldReports(boolean deleteOldReports) { this.deleteOldReports = deleteOldReports; }
        public int getKeepReportsForDays() { return keepReportsForDays; }
        public void setKeepReportsForDays(int keepReportsForDays) { this.keepReportsForDays = keepReportsForDays; }
    }

    // Main getters and setters
    public DatabaseConfig getPrimary() { return primary; }
    public void setPrimary(DatabaseConfig primary) { this.primary = primary; }
    public DatabaseConfig getSecondary() { return secondary; }
    public void setSecondary(DatabaseConfig secondary) { this.secondary = secondary; }
    public SftpConfig getSftp() { return sftp; }
    public void setSftp(SftpConfig sftp) { this.sftp = sftp; }
    public EmailConfig getEmail() { return email; }
    public void setEmail(EmailConfig email) { this.email = email; }
    public ReportConfig getReport() { return report; }
    public void setReport(ReportConfig report) { this.report = report; }
}