package org.example;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

public class DBRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(DBRepository.class);
    private static final int TIMEOUT = 5;

    private final ConfigurationRepository configurationRepository;
    private final Map<String, Map<String, JdbcTemplate>> jdbcTemplates = new HashMap<>();

    public DBRepository(ConfigurationRepository configurationRepository) {
        this.configurationRepository = configurationRepository;
    }

    @SuppressWarnings("unchecked")
    public void initDBConfig() {
        Map<String, Configuration> configurations = configurationRepository.getConfigurations();
        if (configurations == null || configurations.isEmpty()) {
            LOGGER.warn("No configurations found.");
            return;
        }
        for (Map.Entry<String, Configuration> entry : configurations.entrySet()) {
            String system = entry.getKey();
            Configuration config = entry.getValue();
            if (config == null) continue;
            Object dbObj = config.get("db");
            if (!(dbObj instanceof Map)) continue;
            Map<String, Object> dbConfig = (Map<String, Object>) dbObj;
            processDbConnections(system, dbConfig, system);
            Object otherSystemsObj = dbConfig.get("other_systems");
            if (otherSystemsObj instanceof Map) {
                Map<String, Object> otherSystems = (Map<String, Object>) otherSystemsObj;
                for (Map.Entry<String, Object> otherEntry : otherSystems.entrySet()) {
                    String otherSystem = otherEntry.getKey();
                    Object otherDbObj = otherEntry.getValue();
                    if (otherDbObj instanceof Map) {
                        Map<String, Object> otherDbConfig = (Map<String, Object>) otherDbObj;
                        processDbConnections(otherSystem, otherDbConfig, system);
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void processDbConnections(String env, Map<String, Object> dbConfig, String parentSystem) {
        Object connObj = dbConfig.get("conn");
        if (!(connObj instanceof List)) return;
        List<String> connections = (List<String>) connObj;
        for (String conn : connections) {
            if (conn == null || conn.isEmpty()) continue;
            try {
                HikariDataSource ds = DataSourceBuilder.create().url(conn).type(HikariDataSource.class).build();
                try (Connection c = ds.getConnection()) {
                    if (!isValid(c, TIMEOUT)) {
                        LOGGER.warn("Invalid datasource: {}", conn);
                        continue;
                    }
                }
                jdbcTemplates.computeIfAbsent(parentSystem, k -> new HashMap<>()).put(env, new JdbcTemplate(ds));
                break;
            } catch (SQLException ex) {
                LOGGER.error("Failed to create datasource: {}", conn, ex);
            }
        }
    }

    private boolean isValid(Connection conn, int timeout) {
        try {
            return conn != null && conn.isValid(timeout);
        } catch (SQLException e) {
            LOGGER.error("Connection validation failed", e);
            return false;
        }
    }

    public Map<String, Map<String, JdbcTemplate>> getJdbcTemplates() {
        return jdbcTemplates;
    }

    // Placeholder for ConfigurationRepository and Configuration
    // Replace these with your actual implementations
    public interface ConfigurationRepository {
        Map<String, Configuration> getConfigurations();
    }
    public interface Configuration extends Map<String, Object> {}
}
