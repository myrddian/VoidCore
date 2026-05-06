package io.aeyer.voidcore;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jooq.JooqAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boot smoke test. The DB-backed slices (Flyway, jOOQ, Datasource) are excluded
 * here because the project skeleton does not yet contain any migrations or a
 * configured Postgres — those land with the schema ticket and with the
 * Testcontainers-backed integration tests. This test fails if Spring config
 * is broken in a non-DB way (e.g. bean wiring, classpath, profile resolution).
 */
@SpringBootTest(
        classes = VoidCoreApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // AuthConfig is gated on spring.datasource.url being set; clear it here
        // since this DB-less smoke test excludes DataSourceAutoConfiguration.
        properties = "spring.datasource.url="
)
@EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        FlywayAutoConfiguration.class,
        JooqAutoConfiguration.class
})
class VoidCoreApplicationTests {

    @Test
    void contextLoads() {
    }
}
