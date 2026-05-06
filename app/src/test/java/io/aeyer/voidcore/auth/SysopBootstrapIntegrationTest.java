package io.aeyer.voidcore.auth;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Driver;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class SysopBootstrapIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("voidcore")
            .withUsername("postgres")
            .withPassword("postgres")
            .withInitScript("init-extensions.sql");

    static NamedParameterJdbcTemplate jdbc;
    static PasswordHasher hasher;

    @BeforeAll
    static void setup() throws Exception {
        Driver driver = (Driver) Class.forName("org.postgresql.Driver")
                .getDeclaredConstructor().newInstance();
        DataSource ds = new SimpleDriverDataSource(driver,
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new NamedParameterJdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        hasher = new PasswordHasher(new Argon2Properties(1024, 1, 1));
    }

    @BeforeEach
    void cleanSysop() {
        // Fresh-DB bootstrap plants a placeholder sysop before V6 backfills
        // documents. Wipe documents first so the sysop DELETE doesn't trip
        // the documents.author_id FK.
        jdbc.getJdbcTemplate().update("DELETE FROM documents");
        jdbc.getJdbcTemplate().update("DELETE FROM users WHERE is_sysop = true");
    }

    @Test
    void blankPropsAreANoOp() {
        new SysopBootstrap(jdbc, hasher, new SysopProperties("", ""))
                .run(new DefaultApplicationArguments());
        assertThat(countSysops()).isZero();
    }

    @Test
    void firstBootCreatesSysop() {
        new SysopBootstrap(jdbc, hasher, new SysopProperties("SYSOP", "letmein-please-rotate"))
                .run(new DefaultApplicationArguments());
        assertThat(countSysops()).isEqualTo(1);
        String hash = jdbc.getJdbcTemplate().queryForObject(
                "SELECT pw_hash FROM users WHERE handle = 'SYSOP'", String.class);
        assertThat(hash).startsWith("$argon2id$");
        assertThat(hasher.verify(hash, "letmein-please-rotate")).isTrue();
    }

    @Test
    void secondBootIsNoOpWhenSysopExists() {
        SysopBootstrap boot = new SysopBootstrap(jdbc, hasher,
                new SysopProperties("SYSOP", "first-password"));
        boot.run(new DefaultApplicationArguments());
        // Second run with a different env value — the existing sysop should
        // NOT be touched. Re-rotation is a manual sysop action, not a boot
        // side effect.
        new SysopBootstrap(jdbc, hasher,
                new SysopProperties("SYSOP", "different-password"))
                .run(new DefaultApplicationArguments());
        assertThat(countSysops()).isEqualTo(1);
        String hash = jdbc.getJdbcTemplate().queryForObject(
                "SELECT pw_hash FROM users WHERE handle = 'SYSOP'", String.class);
        // The first-password hash still verifies; the second-password does not.
        assertThat(hasher.verify(hash, "first-password")).isTrue();
        assertThat(hasher.verify(hash, "different-password")).isFalse();
    }

    private int countSysops() {
        Integer n = jdbc.getJdbcTemplate().queryForObject(
                "SELECT count(*)::int FROM users WHERE is_sysop = true", Integer.class);
        return n == null ? 0 : n;
    }
}
