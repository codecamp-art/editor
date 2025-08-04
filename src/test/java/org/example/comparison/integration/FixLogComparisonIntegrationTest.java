package org.example.comparison.integration;

import org.example.comparison.FixLogComparisonApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Integration tests for the FIX Log Comparison Application
 */
@SpringBootTest(classes = FixLogComparisonApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "comparison.primary.url=jdbc:h2:mem:testdb1",
    "comparison.primary.username=sa",
    "comparison.primary.password=",
    "comparison.primary.driver-class-name=org.h2.Driver",
    "comparison.secondary.url=jdbc:h2:mem:testdb2",
    "comparison.secondary.username=sa",
    "comparison.secondary.password=",
    "comparison.secondary.driver-class-name=org.h2.Driver",
    "comparison.sftp.host=localhost",
    "comparison.sftp.port=22",
    "comparison.sftp.username=test",
    "comparison.sftp.password=test",
    "comparison.sftp.remote-directory=/test",
    "comparison.email.from=test@localhost",
    "comparison.email.to[0]=test@localhost",
    "comparison.report.output-directory=./test-reports",
    "spring.mail.host=localhost",
    "spring.mail.port=1025"
})
class FixLogComparisonIntegrationTest {

    @Test
    void contextLoads() {
        // This test verifies that the Spring context loads successfully
        // with all the configurations and beans properly wired
    }

    // Note: Full integration tests would require:
    // 1. Test databases with sample data
    // 2. Mock SFTP server
    // 3. Mock email server (like GreenMail)
    // 
    // For a production environment, consider using:
    // - Testcontainers for Oracle databases
    // - MockFtpServer for SFTP testing
    // - GreenMail for email testing
}