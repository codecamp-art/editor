package org.example.comparison.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.net.ssl.SSLContext;
import javax.sql.DataSource;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AppConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(AppConfig.class);

    public AppConfig(SslBundles sslBundles) {
        try {
            SSLContext.setDefault(sslBundles.getBundle("server").createSslContext());
        } catch (NoSuchSslBundleException e) {
            LOGGER.warn("certificate is not configured");
        }
    }

    @Bean
    public ExecutorService executors() {
        return Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() + 1);
    }

    @Bean(name = "fixDataSourceProperties")
    @ConfigurationProperties(prefix = "fix.datasource")
    public DataSourceProperties fixDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "fixDataSource")
    public DataSource fixDataSource(@Qualifier("fixDataSourceProperties") DataSourceProperties props) {
        return props.initializeDataSourceBuilder().build();
    }

    @Bean
    public JdbcTemplate fixJdbcTemplate(@Qualifier("fixDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
