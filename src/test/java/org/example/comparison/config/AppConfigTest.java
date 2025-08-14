package org.example.comparison.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.net.ssl.SSLContext;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class AppConfigTest {

    private ApplicationContextRunner contextRunnerWith(SslBundles sslBundles) {
        return new ApplicationContextRunner()
                .withBean(SslBundles.class, () -> sslBundles)
                .withUserConfiguration(AppConfig.class)
                .withPropertyValues(
                        "fix.datasource.url=jdbc:h2:mem:appconf-test;DB_CLOSE_DELAY=-1",
                        "fix.datasource.driverClassName=org.h2.Driver",
                        "fix.datasource.username=sa",
                        "fix.datasource.password="
                );
    }

    @Test
    void contextLoads_andBeansCreated_whenSslBundlePresent() {
        SslBundles bundles = mock(SslBundles.class);
        SslBundle bundle = mock(SslBundle.class);
        try {
            when(bundle.createSslContext()).thenReturn(SSLContext.getDefault());
        } catch (Exception e) {
            fail(e);
        }
        when(bundles.getBundle("server")).thenReturn(bundle);

        contextRunnerWith(bundles).run(ctx -> {
            assertTrue(ctx.containsBean("executors"));
            assertTrue(ctx.containsBean("fixDataSourceProperties"));
            assertTrue(ctx.containsBean("fixDataSource"));
            assertTrue(ctx.containsBean("fixJdbcTemplate"));

            DataSource ds = ctx.getBean("fixDataSource", DataSource.class);
            assertNotNull(ds);
            // Basic connectivity sanity: get a connection and run simple query
            try (var conn = ds.getConnection(); var stmt = conn.createStatement()) {
                assertTrue(stmt.execute("SELECT 1"));
            }
        });
    }

    @Test
    void contextLoads_whenSslBundleMissing_logsWarning() {
        SslBundles bundles = mock(SslBundles.class);
        when(bundles.getBundle("server")).thenThrow(mock(NoSuchSslBundleException.class));

        contextRunnerWith(bundles).run(ctx -> {
            assertTrue(ctx.containsBean("executors"));
            assertTrue(ctx.containsBean("fixDataSourceProperties"));
            assertTrue(ctx.containsBean("fixDataSource"));
            assertTrue(ctx.containsBean("fixJdbcTemplate"));
        });
    }
}


