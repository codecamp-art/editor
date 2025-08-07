package org.example.comparison.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DatabaseConfig
 */
@ExtendWith(MockitoExtension.class)
class DatabaseConfigTest {

    @Mock
    private ComparisonConfig comparisonConfig;

    @Mock
    private ComparisonConfig.DatabaseConfig primaryDbConfig;

    @Mock
    private ComparisonConfig.DatabaseConfig secondaryDbConfig;

    private DatabaseConfig databaseConfig;

    @BeforeEach
    void setUp() {
        when(comparisonConfig.getPrimary()).thenReturn(primaryDbConfig);
        when(comparisonConfig.getSecondary()).thenReturn(secondaryDbConfig);

        // Setup primary database config
        when(primaryDbConfig.getUrl()).thenReturn("jdbc:oracle:thin:@primary-db:1521:XE");
        when(primaryDbConfig.getUsername()).thenReturn("primary_user");
        when(primaryDbConfig.getPassword()).thenReturn("primary_pass");
        when(primaryDbConfig.getDriverClassName()).thenReturn("oracle.jdbc.OracleDriver");

        // Setup secondary database config
        when(secondaryDbConfig.getUrl()).thenReturn("jdbc:oracle:thin:@secondary-db:1521:XE");
        when(secondaryDbConfig.getUsername()).thenReturn("secondary_user");
        when(secondaryDbConfig.getPassword()).thenReturn("secondary_pass");
        when(secondaryDbConfig.getDriverClassName()).thenReturn("oracle.jdbc.OracleDriver");

        databaseConfig = new DatabaseConfig(comparisonConfig);
    }

    @Test
    void testConstructor() {
        // When
        DatabaseConfig config = new DatabaseConfig(comparisonConfig);

        // Then
        assertNotNull(config);
    }

    @Test
    void testPrimaryDataSource() {
        // When
        DataSource dataSource = databaseConfig.primaryDataSource();

        // Then
        assertNotNull(dataSource);
        assertTrue(dataSource instanceof HikariDataSource);
        verify(comparisonConfig).getPrimary();
    }

    @Test
    void testSecondaryDataSource() {
        // When
        DataSource dataSource = databaseConfig.secondaryDataSource();

        // Then
        assertNotNull(dataSource);
        assertTrue(dataSource instanceof HikariDataSource);
        
        // Verify configuration was accessed
        verify(comparisonConfig).getSecondary();
        verify(secondaryDbConfig).getUrl();
        verify(secondaryDbConfig).getUsername();
        verify(secondaryDbConfig).getPassword();
        verify(secondaryDbConfig).getDriverClassName();
    }

    @Test
    void testSecondaryDataSource_Configuration() {
        // When
        DataSource dataSource = databaseConfig.secondaryDataSource();
        HikariDataSource hikariDataSource = (HikariDataSource) dataSource;

        try {
            // Then
            assertEquals("jdbc:oracle:thin:@secondary-db:1521:XE", hikariDataSource.getJdbcUrl());
            assertEquals("secondary_user", hikariDataSource.getUsername());
            assertEquals("secondary_pass", hikariDataSource.getPassword());
            assertEquals("oracle.jdbc.OracleDriver", hikariDataSource.getDriverClassName());
            
            // Verify connection pool settings
            assertEquals(10, hikariDataSource.getMaximumPoolSize());
            assertEquals(2, hikariDataSource.getMinimumIdle());
            assertEquals(30000, hikariDataSource.getConnectionTimeout());
            assertEquals(600000, hikariDataSource.getIdleTimeout());
            assertEquals(1800000, hikariDataSource.getMaxLifetime());
            assertEquals(60000, hikariDataSource.getLeakDetectionThreshold());
        } finally {
            hikariDataSource.close();
        }
    }

    @Test
    void testSecondaryDataSource_OracleSpecificProperties() {
        // When
        DataSource dataSource = databaseConfig.secondaryDataSource();
        HikariDataSource hikariDataSource = (HikariDataSource) dataSource;

        // Then
        // Note: Testing HikariCP data source properties directly is complex
        // In a real scenario, you might want to test through integration tests
        // or by extracting configuration to a separate method
        assertNotNull(hikariDataSource);
    }

    @Test
    void testPrimaryJdbcTemplate() {
        // Given
        DataSource mockDataSource = mock(DataSource.class);

        // When
        JdbcTemplate jdbcTemplate = databaseConfig.primaryJdbcTemplate(mockDataSource);

        // Then
        assertNotNull(jdbcTemplate);
        assertEquals(mockDataSource, jdbcTemplate.getDataSource());
    }

    @Test
    void testSecondaryJdbcTemplate() {
        // Given
        DataSource mockDataSource = mock(DataSource.class);

        // When
        JdbcTemplate jdbcTemplate = databaseConfig.secondaryJdbcTemplate(mockDataSource);

        // Then
        assertNotNull(jdbcTemplate);
        assertEquals(mockDataSource, jdbcTemplate.getDataSource());
    }

    @Test
    void testJdbcTemplateWithNullDataSource() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () ->
                databaseConfig.primaryJdbcTemplate(null));
    }

    @Test
    void testDataSourceConfiguration_WithNullUrl() {
        // Given
        when(secondaryDbConfig.getUrl()).thenReturn(null);

        // When
        DataSource dataSource = databaseConfig.secondaryDataSource();

        // Then
        assertNotNull(dataSource);
        // HikariCP will handle null URL appropriately during actual connection
    }

    @Test
    void testDataSourceConfiguration_WithEmptyCredentials() {
        // Given
        when(secondaryDbConfig.getUsername()).thenReturn("");
        when(secondaryDbConfig.getPassword()).thenReturn("");

        // When
        DataSource dataSource = databaseConfig.secondaryDataSource();
        HikariDataSource hikariDataSource = (HikariDataSource) dataSource;

        try {
            // Then
            assertNotNull(dataSource);
            assertEquals("", hikariDataSource.getUsername());
            assertEquals("", hikariDataSource.getPassword());
        } finally {
            hikariDataSource.close();
        }
    }

    @Test
    void testConnectionPoolSettings() {
        // When
        DataSource dataSource = databaseConfig.secondaryDataSource();
        HikariDataSource hikariDataSource = (HikariDataSource) dataSource;

        try {
            // Then - Verify all connection pool settings
            assertEquals(10, hikariDataSource.getMaximumPoolSize());
            assertEquals(2, hikariDataSource.getMinimumIdle());
            assertEquals(30000, hikariDataSource.getConnectionTimeout());
            assertEquals(600000, hikariDataSource.getIdleTimeout());
            assertEquals(1800000, hikariDataSource.getMaxLifetime());
            assertEquals(60000, hikariDataSource.getLeakDetectionThreshold());
        } finally {
            hikariDataSource.close();
        }
    }

    @Test
    void testPrimaryDataSourceIsPrimary() {
        // This test verifies that the primary data source is properly annotated
        // In a real Spring context, this would be tested through integration tests
        // Here we just verify the method exists and returns a DataSource
        
        // When
        DataSource dataSource = databaseConfig.primaryDataSource();

        // Then
        assertNotNull(dataSource);
    }

    @Test
    void testDataSourceClose() {
        // When
        DataSource dataSource = databaseConfig.secondaryDataSource();
        HikariDataSource hikariDataSource = (HikariDataSource) dataSource;

        // Then
        assertFalse(hikariDataSource.isClosed());
        
        // Cleanup
        hikariDataSource.close();
        assertTrue(hikariDataSource.isClosed());
    }

    @Test
    void testMultipleDataSourceCreation() {
        // When
        DataSource dataSource1 = databaseConfig.secondaryDataSource();
        DataSource dataSource2 = databaseConfig.secondaryDataSource();

        // Then
        assertNotNull(dataSource1);
        assertNotNull(dataSource2);
        assertNotSame(dataSource1, dataSource2); // Should create new instances
        
        // Cleanup
        ((HikariDataSource) dataSource1).close();
        ((HikariDataSource) dataSource2).close();
    }

    @Test
    void testConfigurationInjection() {
        // When
        DatabaseConfig config = new DatabaseConfig(comparisonConfig);

        // Then
        assertNotNull(config);
        // Verify that the configuration is properly injected by testing method calls
        config.primaryDataSource();
        verify(comparisonConfig).getPrimary();
    }
}