package io.aeyer.voidcore.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class LegacyTablesDropV12IntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("voidcore")
            .withUsername("postgres")
            .withPassword("postgres")
            .withInitScript("init-extensions.sql");

    @BeforeAll
    static void migrate() throws Exception {
        DataSource ds = new SimpleDriverDataSource(
                loadDriver(),
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword());
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("5")
                .load()
                .migrate();

        try (Connection c = connect();
             Statement s = c.createStatement()) {
            int updated = s.executeUpdate(
                    "UPDATE users " +
                    "SET handle = 'SYSOP', pw_hash = 'x' " +
                    "WHERE pw_hash = 'BOOTSTRAP_SENTINEL'");
            if (updated == 0) {
                s.executeUpdate(
                        "INSERT INTO users (handle, pw_hash, is_sysop) " +
                        "VALUES ('SYSOP', 'x', true)");
            }

            s.executeUpdate(
                    "INSERT INTO bulletins (title, body, pinned) " +
                    "VALUES ('Welcome to VOIDcore', 'Hello world', true)");
            s.executeUpdate(
                    "INSERT INTO files (filename, title, size_bytes, uploader_id, nfo_text) " +
                    "SELECT 'PATTERN9.ZIP', 'Pattern Nine', 1234, id, 'NFO body' " +
                    "FROM users WHERE handle = 'SYSOP'");
        }

        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("12")
                .load()
                .migrate();
    }

    @Test
    void v12DropsLegacyTablesAndPreservesCompatibilityIds() throws SQLException {
        try (Connection c = connect();
             Statement s = c.createStatement()) {

            try (ResultSet rs = s.executeQuery(
                    "SELECT to_regclass('public.files'), to_regclass('public.bulletins')")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isNull();
                assertThat(rs.getString(2)).isNull();
            }

            try (ResultSet rs = s.executeQuery(
                    "SELECT count(*) FROM documents " +
                    "WHERE type_slug = 'release' AND frontmatter ? 'legacy_file_id'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isGreaterThan(0);
            }

            try (ResultSet rs = s.executeQuery(
                    "SELECT count(*) FROM documents " +
                    "WHERE type_slug = 'article' AND frontmatter ? 'legacy_bulletin_id'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isGreaterThan(0);
            }
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static Driver loadDriver() {
        try {
            return (Driver) Class.forName("org.postgresql.Driver")
                    .getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("Postgres driver missing on classpath", e);
        }
    }
}
