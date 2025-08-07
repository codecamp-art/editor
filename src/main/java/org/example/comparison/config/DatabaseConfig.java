package org.example.comparison.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Database configuration for multiple Oracle database connections
 * Supports both traditional username/password and Kerberos authentication
 */
@Configuration
public class DatabaseConfig {

    private final ComparisonConfig comparisonConfig;

    public DatabaseConfig(ComparisonConfig comparisonConfig) {
        this.comparisonConfig = comparisonConfig;
    }

    @Bean
    @Primary
    @Qualifier("primaryDataSource")
    public DataSource primaryDataSource() {
        ComparisonConfig.DatabaseConfig dbConfig = comparisonConfig.getPrimary();
        return new HikariDataSource();
    }

    @Bean
    @Qualifier("secondaryDataSource")
    public DataSource secondaryDataSource() {
        HikariConfig config = new HikariConfig();
        ComparisonConfig.DatabaseConfig dbConfig = comparisonConfig.getSecondary();
        
        config.setJdbcUrl(dbConfig.getUrl());
        config.setUsername(dbConfig.getUsername());
        config.setPassword(dbConfig.getPassword());
        config.setDriverClassName(dbConfig.getDriverClassName());
        
        // Connection pool settings
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        config.setLeakDetectionThreshold(60000);
        
        // Oracle specific settings
        config.addDataSourceProperty("oracle.jdbc.useThreadLocalBufferCache", "true");
        config.addDataSourceProperty("oracle.jdbc.implicitStatementCacheSize", "20");
        config.addDataSourceProperty("oracle.jdbc.defaultExecuteBatch", "20");
        
        return new HikariDataSource(config);
    }

    @Bean
    @Primary
    @Qualifier("primaryJdbcTemplate")
    public JdbcTemplate primaryJdbcTemplate(@Qualifier("primaryDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    @Qualifier("secondaryJdbcTemplate")
    public JdbcTemplate secondaryJdbcTemplate(@Qualifier("secondaryDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}